package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.MultiConsensusFilter;
import org.janelia.saalfeldlab.ispim.imglib2.NonRigidRealRandomAccessible;
import org.janelia.saalfeldlab.ispim.imglib2.st.filter.GaussianFilterFactory;
import org.janelia.saalfeldlab.ispim.imglib2.st.filter.GaussianFilterFactory.WeightType;
import org.janelia.saalfeldlab.ispim.imglib2.st.render.Render;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.Interpolation;
import ij.ImagePlus;
import loci.formats.FormatException;
import mpicbg.models.AffineModel3D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.MovingLeastSquaresTransform;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel3D;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealPoint;
import net.imglib2.RealPointSampleList;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.list.ListImg;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.SimpleReferenceIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.NumericAffineModel3D;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.fastrgldm.FRGLDMMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMMatcher;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import scala.Tuple2;

public class SparkPaiwiseAlignChannelsGeo implements Callable<Void>, Serializable {

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--id", required = true, description = "Stack key, e.g. Pos012")
	private String id = null;

	@Option(names = "--channelA", required = true, description = "Channel A key, e.g. Ch488+561+647nm")
	private String channelA = null;

	@Option(names = "--channelB", required = true, description = "Channel B key, e.g. Ch405nm")
	private String channelB = null;

	@Option(names = "--camA", required = true, description = "CamA key, e.g. cam1")
	private String camA = null;

	@Option(names = "--camB", required = true, description = "CamB key, e.g. cam1")
	private String camB = null;

	@Option(names = {"-b", "--blocksize"}, required = false, description = "blocksize in z for point extraction (default: 20)")
	private int blocksize = 20;

	//@Option(names = "--first", required = false, description = "First slice index, e.g. 0 (default 0)")
	//private int firstSliceIndex = 0;

	//@Option(names = "--last", required = false, description = "Last slice index, e.g. 1000 (default MAX)")
	//private int lastSliceIndex = Integer.MAX_VALUE;

	@Option(names = "--minIntensity", required = false, description = "min intensity, if minIntensity==maxIntensity determine min/max per slice (default: 0)")
	private double minIntensity = 0;

	@Option(names = "--maxIntensity", required = false, description = "max intensity, if minIntensity==maxIntensity determine min/max per slice (default: 4096)")
	private double maxIntensity = 4096;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new SparkPaiwiseAlignChannelsGeo()).execute(args);
	}

	public static class Block implements Serializable
	{
		private static final long serialVersionUID = -5770229677154496831L;

		final int from, to, gaussOverhead;
		final String n5Path, id, channel, cam;
		final double sigma, threshold, minIntensity, maxIntensity;
		final double[] transform;
		final int downsampling;

		public Block(
				final int from,
				final int to,
				final String n5Path,
				final String id,
				final String channel,
				final String cam,
				final AffineTransform2D camtransform,
				final double sigma,
				final double threshold,
				final double minIntensity,
				final double maxIntensity,
				final int downsampling )
		{
			this.from = from;
			this.to = to;
			this.n5Path = n5Path;
			this.id = id;
			this.channel = channel;
			this.cam = cam;
			this.sigma = sigma;
			this.threshold = threshold;
			this.gaussOverhead = DoGImgLib2.radiusDoG( sigma ) * downsampling;
			this.transform = camtransform.getRowPackedCopy();
			this.minIntensity = minIntensity;
			this.maxIntensity = maxIntensity;
			this.downsampling = downsampling;
		}

		public AffineTransform2D getTransform()
		{
			final AffineTransform2D t = new AffineTransform2D();
			t.set( transform );
			return t;
		}
	}

	public static class N5Data
	{
		public HashMap<String, HashMap<String, AffineTransform2D>> camTransforms;
		public HashMap<String, HashMap<String, List<Slice>>> stacks;
		public HashMap<String, RandomAccessible<AffineTransform2D>> alignments;

		public HashMap<String, AffineTransform3D > affine3D;

		public N5FSReader n5;

		public String n5path, id;
		public int lastSliceIndex;
	}

	public static N5Data openN5(
			final String n5Path,
			final String id ) throws IOException
	{
		final N5Data n5data = new N5Data();

		n5data.id = id;
		n5data.n5path = n5Path;
		n5data.n5 = new N5FSReader(
				n5Path,
				new GsonBuilder().
				registerTypeAdapter(
					AffineTransform3D.class,
					new AffineTransform3DAdapter()).
				registerTypeAdapter(
					AffineTransform2D.class,
					new AffineTransform2DAdapter()));

		n5data.camTransforms = n5data.n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, AffineTransform2D>>>() {}.getType());
		ArrayList<String> ids = n5data.n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());

		if (!ids.contains(id))
		{
			System.err.println("Id '" + id + "' does not exist in '" + n5Path + "'.");
			return null;
		}
	
		//System.out.println( new Date(System.currentTimeMillis() ) + ": Loading alignments for " + id );

		n5data.stacks = new HashMap<>();
		n5data.alignments = new HashMap<>();
		n5data.affine3D = new HashMap<>();

		n5data.lastSliceIndex = Integer.MAX_VALUE;

		for (final Entry<String, HashMap<String, AffineTransform2D>> channel : n5data.camTransforms.entrySet()) {
			final HashMap<String, List<Slice>> channelStacks = new HashMap<>();
			n5data.stacks.put(channel.getKey(), channelStacks);

			/* 3d affine if it exists */
			final String affine3dGroupName = id + "/" + channel.getKey();
			if (n5data.n5.exists(affine3dGroupName)) {
				n5data.affine3D.put(
						channel.getKey(),
						n5data.n5.getAttribute( affine3dGroupName, "3d-affine", new TypeToken<AffineTransform3D>(){}.getType() ) );
			}
			else {
				n5data.affine3D.put( channel.getKey(), new AffineTransform3D() );
			}

			/* stack alignment transforms */
			final ArrayList<AffineTransform2D> transforms = n5data.n5.getAttribute(
					id + "/" + channel.getKey(),
					"transforms",
					new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

			final RandomAccessible<AffineTransform2D> alignmentTransforms = Views.extendBorder(new ListImg<>(transforms, transforms.size()));

			n5data.alignments.put(channel.getKey(), alignmentTransforms);

			/* add all camera stacks that exist */
			for (final String camKey : channel.getValue().keySet()) {
				final String groupName = id + "/" + channel.getKey() + "/" + camKey;
				if (n5data.n5.exists(groupName)) {
					final ArrayList<Slice> stack = n5data.n5.getAttribute(
							groupName,
							"slices",
							new TypeToken<ArrayList<Slice>>(){}.getType());
					channelStacks.put(
							camKey,
							stack);

					n5data.lastSliceIndex = Math.min(n5data.lastSliceIndex, stack.size() - 1);
				}
			}
		}

		//System.out.println( new Date(System.currentTimeMillis() ) + ": lastSliceIndex=" + lastSliceIndex + " for " + id );
		//final Gson gson = new GsonBuilder().registerTypeAdapter(
		//		AffineTransform2D.class,
		//		new AffineTransform2DAdapter()).create();

		//System.out.println(gson.toJson(camTransforms));
		//System.out.println(gson.toJson(ids));
		// System.out.println(new Gson().toJson(stacks));

		return n5data;
	}

	public static double align(
			final String n5Path,
			final String id,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final int blockSizeZ,
			final boolean doICP ) throws IOException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		IOFunctions.printIJLog = false;
		System.out.println( new Date(System.currentTimeMillis() ) + ": Opening N5." );

		final N5Data n5data = openN5( n5Path, id );

		System.out.println( new Date(System.currentTimeMillis() ) + ": lastSliceIndex=" + n5data.lastSliceIndex + " for " + id );

		final Gson gson = new GsonBuilder().registerTypeAdapter(
				AffineTransform2D.class,
				new AffineTransform2DAdapter()).create();

		System.out.println(gson.toJson(n5data.camTransforms));
		// System.out.println(new Gson().toJson(stacks));

		//new ImageJ();

		ArrayList<InterestPoint> pointsChA = null;
		ArrayList<InterestPoint> pointsChB = null;

		System.out.println( "loading points ... " );

		try
		{
			final String datasetNameA = id + "/" + channelA + "/Stack-DoG-detections";
			final DatasetAttributes datasetAttributesA = n5data.n5.getDatasetAttributes(datasetNameA);

			final String datasetNameB = id + "/" + channelB + "/Stack-DoG-detections";
			final DatasetAttributes datasetAttributesB = n5data.n5.getDatasetAttributes(datasetNameB);

			pointsChA = n5data.n5.readSerializedBlock(datasetNameA, datasetAttributesA, new long[] {0});
			pointsChB = n5data.n5.readSerializedBlock(datasetNameB, datasetAttributesB, new long[] {0});
		}
		catch ( Exception e ) // java.nio.file.NoSuchFileException
		{
			e.printStackTrace();
			System.out.println( new Date(System.currentTimeMillis() ) + ": Failed to load points for " + id );
			return 0.0;
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": channelA: " + pointsChA.size() + " points for " + id );
		System.out.println( new Date(System.currentTimeMillis() ) + ": channelB: " + pointsChB.size() + " points for " + id );

		System.out.println( "performing block-wise (in z) matching ... " );

		Pair< ArrayList<PointMatch>, Double > resultTmp = matchZSteps( blockSizeZ, doICP, pointsChA, pointsChB, 0, n5data.lastSliceIndex );
		ArrayList<PointMatch> matchesTmp = resultTmp.getA();

		System.out.println( "total matches:" + matchesTmp.size() );

		TranslationModel3D translation = new TranslationModel3D();
		translation.fit( matchesTmp );
		error(matchesTmp, translation );

		AffineModel3D affine = new AffineModel3D();
		affine.fit( matchesTmp );
		error(matchesTmp, affine );

		MovingLeastSquaresTransform3 mls = new MovingLeastSquaresTransform3(); // cuts of correspondences if weight is too small
		mls.setModel( new AffineModel3D() );
		mls.setMatches( matchesTmp );
		error( matchesTmp, mls );

		/*
		// afterwards, compare ICP on affine transformed vs ICP on non-rigid deformed
		System.out.println( "\nApplying affine to ChA points " );

		List< InterestPoint > pointsChANew = new ArrayList<>();

		for ( final InterestPoint p : pointsChA )
		{
			final double[] l = p.getL().clone();
			affine.applyInPlace( l );
			pointsChANew.add( new InterestPoint( p.getId(), l ) );
		}
		matches = matchICP( pointsChANew, pointsChB, firstSliceIndex, localLastSliceIndex, channelA, channelB, camA, camB, camTransforms, stacks, alignments );
		*/

		System.out.println( "Deforming ChA points for " + id );

		List< InterestPoint > pointsChANew = new ArrayList<InterestPoint>();
		for ( final InterestPoint p : pointsChA )
		{
			final double[] l = p.getL().clone();
			mls.applyInPlace( l );
			pointsChANew.add( new InterestPoint( p.getId(), l ) );
		}
		matchesTmp = matchICP( pointsChANew, pointsChB, 0, n5data.lastSliceIndex, channelA, channelB, camA, camB, n5data.camTransforms, n5data.stacks, n5data.alignments );

		/*
		BdvStackSource<?> bdv = null;

		bdv = displayOverlap( bdv, channelB, camB, stacks.get( channelB ).get( camB ), alignments.get( channelB ), camTransforms.get( channelB ).get( camB ), new AffineTransform3D(), firstSliceIndex, localLastSliceIndex );
		bdv.setDisplayRange(0, 512);
		bdv.setColor( new ARGBType( ARGBType.rgba(255, 0, 255, 0)));
		bdv = BdvFunctions.show( renderPoints( pointsChB ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "points ch B", new BdvOptions().addTo( bdv ) );
		bdv = BdvFunctions.show( renderPoints( matchesTmp.stream().map( pm -> pm.getP2() ).collect( Collectors.toList()) ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "matches ch B", new BdvOptions().addTo( bdv ) );

		SimpleMultiThreading.threadHaltUnClean();
		*/

		// fix matches back to use non-deformed points!
		System.out.println( "Restoring ChA points for " + id );

		HashMap<Integer, InterestPoint > lookUpA = new HashMap<>();
		for ( final InterestPoint p : pointsChA )
			lookUpA.put( p.getId(), p );

		ArrayList<PointMatch> matches = new ArrayList<PointMatch>();

		for ( final PointMatch pm : matchesTmp )
			matches.add(
					new PointMatch(
							lookUpA.get( ((InterestPoint)pm.getP1()).getId() ),
							(InterestPoint)pm.getP2() ) );

		// write matches
		System.out.println( "saving matches for " + id + " ... " );

		if ( matches.size() > 0 )
		{
			final N5FSWriter n5Writer = new N5FSWriter(n5Path);

			final String datasetName = id + "/matches_" + channelA + "_" + channelB;

			if (n5Writer.exists(datasetName))
				n5Writer.remove(datasetName);
			
			n5Writer.createDataset(
					datasetName,
					new long[] {1},
					new int[] {1},
					DataType.OBJECT,
					new GzipCompression());

			final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);

			n5Writer.writeSerializedBlock(
					matches,
					datasetName,
					datasetAttributes,
					new long[] {0});
		}

		return resultTmp.getB();
	}

	public static void visualizeDetections(
			final String n5Path,
			final String id,
			final String channel,
			final String cam ) throws FormatException, IOException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final N5Data n5data = openN5( n5Path, id );

		final String datasetNameA = id + "/" + channel + "/Stack-DoG-detections";
		final DatasetAttributes datasetAttributes = n5data.n5.getDatasetAttributes(datasetNameA);

		final ArrayList<InterestPoint> pointsCh = n5data.n5.readSerializedBlock(datasetNameA, datasetAttributes, new long[] {0});

		System.out.println( new Date(System.currentTimeMillis() ) + ": channel '" + channel+ "': " + pointsCh.size() + " points for " + id );

		BdvStackSource<?> bdv = null;

		bdv = displayCam( bdv, channel, cam, n5data.stacks.get( channel ).get( cam ), n5data.alignments.get( channel ), n5data.camTransforms.get( channel ).get( cam ), new AffineTransform3D(), 0, n5data.lastSliceIndex );
		bdv = BdvFunctions.show( renderPoints( pointsCh, false ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "detections", new BdvOptions().addTo( bdv ) );
	}

	public static void visualizeAlignment(
			final String n5Path,
			final String id,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB ) throws FormatException, IOException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final N5Data n5data = openN5( n5Path, id );

		final String datasetNameA = id + "/" + channelA + "/Stack-DoG-detections";
		final DatasetAttributes datasetAttributesA = n5data.n5.getDatasetAttributes(datasetNameA);

		final String datasetNameB = id + "/" + channelB + "/Stack-DoG-detections";
		final DatasetAttributes datasetAttributesB = n5data.n5.getDatasetAttributes(datasetNameB);

		final ArrayList<InterestPoint> pointsChA = n5data.n5.readSerializedBlock(datasetNameA, datasetAttributesA, new long[] {0});
		final ArrayList<InterestPoint> pointsChB = n5data.n5.readSerializedBlock(datasetNameB, datasetAttributesB, new long[] {0});

		System.out.println( new Date(System.currentTimeMillis() ) + ": channelA: " + pointsChA.size() + " points for " + id );
		System.out.println( new Date(System.currentTimeMillis() ) + ": channelB: " + pointsChB.size() + " points for " + id );

		final String datasetName = id + "/matches_" + channelA + "_" + channelB;
		final DatasetAttributes datasetAttributes = n5data.n5.getDatasetAttributes(datasetName);

		final ArrayList<PointMatch> matches = n5data.n5.readSerializedBlock(datasetName, datasetAttributes, new long[] {0});

		System.out.println( new Date(System.currentTimeMillis() ) + ": Loaded " + matches.size() + " matches" );

		BdvStackSource<?> bdv = null;
		/*
		AffineModel3D affine = new AffineModel3D();
		//affine.fit( matches );

		final AffineTransform3D scale = new AffineTransform3D();
		//scale.scale( 0.2, 0.2, 0.85 );
		final AffineTransform3D transformB_a = TransformationTools.getAffineTransform( affine ).preConcatenate( scale );

		bdv = displayCam( bdv, channelA, camA, n5data.stacks.get( channelA ).get( camA ), n5data.alignments.get( channelA ), n5data.camTransforms.get( channelA ).get( camA ), transformB_a, 0, n5data.lastSliceIndex );
		bdv = displayCam( bdv, channelB, camB, n5data.stacks.get( channelB ).get( camB ), n5data.alignments.get( channelB ), n5data.camTransforms.get( channelB ).get( camB ), scale, 0, n5data.lastSliceIndex );
		bdv = BdvFunctions.show( renderPoints( matches.stream().map( pm -> pm.getP2() ).collect( Collectors.toList()), false ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "matches ch B", new BdvOptions().addTo( bdv ).sourceTransform( scale ) );

		ViewISPIMStacksN5.setupRecordMovie( bdv );

		SimpleMultiThreading.threadHaltUnClean();
		*/

		// Ch515+594nm (B) stays fixed, Ch488+561+647nm (A) is transformed
		// because we align Ch405nm to Ch515+594nm
		final double alpha = 1.0;
		final boolean virtual = false;
		final long[] controlPointDistance = new long[] { 100, 100, 20 };

		// get the input image (same coordinate space as correspondences)
		System.out.println( new Date( System.currentTimeMillis() ) + ": Preparing channelA: " + channelA );

		Pair<RealRandomAccessible<UnsignedShortType>, Interval> prepareCamSource =
				ViewISPIMStack.prepareCamSource(
						n5data.stacks.get( channelA ).get( camA ),
						new UnsignedShortType(0),
						Interpolation.NEARESTNEIGHBOR,
						n5data.camTransforms.get( channelA ).get( camA ).inverse(), // pass the forward transform
						new AffineTransform3D(),
						n5data.alignments.get( channelA ),
						0,
						n5data.lastSliceIndex );

		final Interval boundingBox = prepareCamSource.getB();

		// interest points in the pairs of images
		System.out.println( new Date( System.currentTimeMillis() ) + ": Setting up corresponding interest points for channelA: " + channelA );

		final HashSet< SimpleReferenceIP > corrIPs = new HashSet<>();

		double sumDist = 0;
		double minDist = Double.MAX_VALUE;
		double maxDist = -Double.MAX_VALUE;

		for ( final PointMatch pm : matches )
		{
			double dist = Math.sqrt( (pm.getP1().getL()[0] - pm.getP2().getL()[0])*(pm.getP1().getL()[0] - pm.getP2().getL()[0]) + (pm.getP1().getL()[1] - pm.getP2().getL()[1])*(pm.getP1().getL()[1] - pm.getP2().getL()[1]) + (pm.getP1().getL()[2] - pm.getP2().getL()[2])*(pm.getP1().getL()[2] - pm.getP2().getL()[2]) );
			corrIPs.add(
					new SimpleReferenceIP(
							pm.getP1().getL().clone(),
							pm.getP1().getL().clone(),
							pm.getP2().getL().clone() ) );
			
			sumDist += dist;
			maxDist = Math.max( maxDist, dist );
			minDist = Math.min( minDist, dist );
		}

		// TODO: this is after applying the first round of non-rigid!!
		System.out.println( "avg=" + (sumDist/matches.size()) + ", max=" + maxDist + ", minDist=" + minDist );

		// compute Grid
		System.out.println(
				new Date( System.currentTimeMillis() ) + ": Interpolating non-rigid model (a=" + alpha + ") using " + corrIPs.size() + " points and stepsize " +
				Util.printCoordinates( controlPointDistance ) + " Interval: " + Util.printInterval( boundingBox ) );

		RealRandomAccessible< NumericAffineModel3D > /*ModelGrid*/ grid = new ModelGrid( controlPointDistance, boundingBox, corrIPs, alpha, virtual );
		RealRandomAccessible< UnsignedShortType > transformedA = new NonRigidRealRandomAccessible< UnsignedShortType >(grid,  prepareCamSource.getA() );

		System.out.println( new Date( System.currentTimeMillis() ) + ": displaying" );

		//BdvStackSource<?> bdv = null;

		bdv = displayCam( bdv, channelB, camB, n5data.stacks.get( channelB ).get( camB ), n5data.alignments.get( channelB ), n5data.camTransforms.get( channelB ).get( camB ), new AffineTransform3D(), 0, n5data.lastSliceIndex );
		bdv.setDisplayRange(0, 512);
		bdv.setColor( new ARGBType( ARGBType.rgba(255, 0, 255, 0)));
		//bdv = BdvFunctions.show( renderPoints( pointsChB, false ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "points ch B", new BdvOptions().addTo( bdv ) );
		//bdv = BdvFunctions.show( renderPoints( matches.stream().map( pm -> pm.getP2() ).collect( Collectors.toList()), false ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "matches ch B", new BdvOptions().addTo( bdv ) );

		//AffineModel3D affine = new AffineModel3D();
		//affine.fit( matches );
		//bdv = BdvFunctions.show( transformedA, prepareCamSource.getB(), "affine A", new BdvOptions().addTo( bdv ) );
		bdv = BdvFunctions.show( transformedA, prepareCamSource.getB(), "non-rigid A", new BdvOptions().addTo( bdv ) );
		bdv.setDisplayRange(0, 1024);
		bdv.setColor( new ARGBType( ARGBType.rgba(0, 255, 0, 0)));

		ViewISPIMStacksN5.setupRecordMovie( bdv );

		System.out.println( new Date( System.currentTimeMillis() ) + ": done with " + id );


		// cam4 (Ch488+561+647nm) vs cam4 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch488+561+647nm)**
	}

	public static RealRandomAccessible< DoubleType > renderPoints( final Collection< ? extends Point > points, final boolean useW )
	{
		RealPointSampleList< UnsignedByteType > list = new RealPointSampleList<UnsignedByteType>( points.iterator().next().getL().length );

		for ( final Point p : points )
			list.add( new RealPoint( useW ? p.getW() : p.getL() ), new UnsignedByteType( 255 ) );

		return Render.render( list, new GaussianFilterFactory<>( new DoubleType(), 2, WeightType.NONE ) );
	}

	public static class MyRealPoint extends RealPoint
	{
		public MyRealPoint( final double[] position, final boolean copy )
		{
			super( position, false );
		}
	}

	public static RealRandomAccessible< DoubleType > renderPointsNoCopy( final Collection< ? extends Point > points, final double sigma, final boolean useW )
	{
		RealPointSampleList< UnsignedByteType > list = new RealPointSampleList<UnsignedByteType>( points.iterator().next().getL().length );

		for ( final Point p : points )
			list.add( new MyRealPoint( useW ? p.getW() : p.getL(), false ), new UnsignedByteType( 255 ) );

		return Render.render( list, new GaussianFilterFactory<>( new DoubleType(), sigma, WeightType.NONE ) );
	}

	public static class MovingLeastSquaresTransform3 extends MovingLeastSquaresTransform
	{
		private static final long serialVersionUID = 8497961788827439986L;
		@Override
		public void applyInPlace( final double[] location )
		{
			final Collection< PointMatch > weightedMatches = new ArrayList<>();
			final List< Pair< Double, PointMatch > > farAwayWeightedMatches = new ArrayList<>();

			for ( final PointMatch m : matches )
			{
				final double[] l = m.getP1().getL();

				double s = 0;
				for ( int i = 0; i < location.length; ++i )
				{
					final double dx = l[ i ] - location[ i ];
					s += dx * dx;
				}
				if ( s <= 0 )
				{
					final double[] w = m.getP2().getW();
					for ( int i = 0; i < location.length; ++i )
						location[ i ] = w[ i ];
					return;
				}
				final double weight = m.getWeight() * weigh( s );
				final PointMatch mw = new PointMatch( m.getP1(), m.getP2(), weight );
				if ( weight > 0.0001 )
					weightedMatches.add( mw );
				else
					farAwayWeightedMatches.add( new ValuePair<Double, PointMatch>( weight, mw ) );
			}

			if ( weightedMatches.size() < model.getMinNumMatches() )
			{
				// sort by weight
				Collections.sort( farAwayWeightedMatches, (o1, o2 ) -> o1.getA().compareTo( o2.getA() ) );

				while ( weightedMatches.size() < model.getMinNumMatches() && farAwayWeightedMatches.size() > 0 )
				{
					weightedMatches.add( farAwayWeightedMatches.get( 0 ).getB() );
					farAwayWeightedMatches.remove( 0 );
				}
			}

			try
			{
				model.fit( weightedMatches );
				model.applyInPlace( location );
			}
			catch ( final IllDefinedDataPointsException e ){}
			catch ( final NotEnoughDataPointsException e ){}
		}

	}

	public static ArrayList<PointMatch> matchICP(
			List<InterestPoint> pointsChAIn,
			List<InterestPoint> pointsChBIn,
			final int firstSliceIndex,
			final int localLastSliceIndex,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final HashMap<String, HashMap<String, AffineTransform2D>> camTransforms,
			final HashMap<String, HashMap<String, List<Slice>>> stacks,
			final HashMap<String, RandomAccessible<AffineTransform2D>> alignments
			) throws FormatException, IOException
	{
		final Model< ? > model = new AffineModel3D();
		final double maxDistance = 1.0;
		final int maxIterations = 100;

		ArrayList<InterestPoint> pointsChA = new ArrayList<InterestPoint>();
		ArrayList<InterestPoint> pointsChB = new ArrayList<InterestPoint>();

		for ( final InterestPoint ip : pointsChAIn )
			pointsChA.add( ip.duplicate() );

		for ( final InterestPoint ip : pointsChBIn )
			pointsChB.add( ip.duplicate() );

		IterativeClosestPointPairwise<InterestPoint> icp =
				new IterativeClosestPointPairwise<>(
						new IterativeClosestPointParameters( model, maxDistance, maxIterations ) );

		// not enough points to build a descriptor
		if ( pointsChA.size() < model.getMinNumMatches() || pointsChB.size() < model.getMinNumMatches() )
			return null;

		final ArrayList< PointMatch > matches = new ArrayList<>(
				icp.match( pointsChA, pointsChB ).getInliers().stream().map( v -> (PointMatch)v ).collect( Collectors.toList() ) );

		double minZ = localLastSliceIndex;
		double maxZ = firstSliceIndex;

		for ( final PointMatch pm : matches )
		{
			minZ = Math.min( minZ, Math.min( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
			maxZ = Math.max( maxZ, Math.max( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
		}

		System.out.println( "matches: " + matches.size() + " from(z) " + minZ + " to(z) " + maxZ );

		return matches;
	}

	public static ArrayList<PointMatch> matchBlock(
			final List<InterestPoint> pointsChAIn,
			final List<InterestPoint> pointsChBIn,
			final RealInterval intervalA,
			final RealInterval intervalB,
			final boolean fastMatching,
			final int numNeighbors,
			final int redundancy,
			final double ratioOfDistance,
			final int numIterations,
			final double maxEpsilon,
			final int minNumInliers,
			final boolean doICP,
			final double maxDistanceICP,
			final int maxNumIterationsICP,
			final int minNumInliersICP,
			final int numIterationsICP,
			final double maxEpsilonICP )
	{
		final Supplier matchingModel = (Supplier<AffineModel3D> & Serializable)AffineModel3D::new;
		final Supplier icpModel = (Supplier<AffineModel3D> & Serializable)AffineModel3D::new;

		return matchBlock(pointsChAIn, pointsChBIn, intervalA, intervalB, fastMatching, numNeighbors, redundancy,
				ratioOfDistance, numIterations, maxEpsilon, minNumInliers, matchingModel, doICP, maxDistanceICP,
				maxNumIterationsICP, minNumInliersICP, numIterationsICP, maxEpsilonICP, icpModel);
	}

	public static ArrayList<PointMatch> matchBlock(
			final List<InterestPoint> pointsChAIn,
			final List<InterestPoint> pointsChBIn,
			final RealInterval intervalA,
			final RealInterval intervalB,
			final boolean fastMatching,
			final int numNeighbors,
			final int redundancy,
			final double ratioOfDistance,
			final int numIterations,
			final double maxEpsilon,
			final int minNumInliers,
			final Supplier<Model> matchingModel,
			final boolean doICP,
			final double maxDistanceICP,
			final int maxNumIterationsICP,
			final int minNumInliersICP,
			final int numIterationsICP,
			final double maxEpsilonICP,
			final Supplier<Model> icpModel )
	{
		final ArrayList<InterestPoint> pointsChA = new ArrayList<InterestPoint>();
		final ArrayList<InterestPoint> pointsChB = new ArrayList<InterestPoint>();

		for ( final InterestPoint ip : pointsChAIn )
			if ( Intervals.contains( intervalA, ip ) )
			{
				// copy or reset world coordinates as they are used by default to build descriptors
				// but they are changed by the previous RANSAC run if overlapping sets of points
				// are being used
				//for ( int d = 0; d < ip.getL().length; ++d )
				//	ip.getW()[ d ] = ip.getL()[ d ];
				pointsChA.add( ip.duplicate() );
			}

		for ( final InterestPoint ip : pointsChBIn )
			if ( Intervals.contains( intervalB, ip ) )
			{
				//for ( int d = 0; d < ip.getL().length; ++d )
				//	ip.getW()[ d ] = ip.getL()[ d ];

				pointsChB.add( ip.duplicate() );
			}

		if ( Math.min( pointsChA.size(), pointsChB.size() ) < minNumInliersICP )
		{
			System.out.println( "Not enough points to start with (" + pointsChA.size() + " <> " + pointsChB.size() + ")" );
			return new ArrayList<PointMatch>();
		}

		if ( pointsChA.size() < numNeighbors + redundancy + 1 || pointsChB.size() < numNeighbors + redundancy + 1 )
		{
			System.out.println( "Not enough points (" + pointsChA.size() + " <> " + pointsChB.size() + ")" );
			return new ArrayList<PointMatch>();
		}

		ArrayList<PointMatch> matches;

		if ( matchingModel != null )
		{
			final List< PointMatch > candidates;
	
			if ( fastMatching )
			{
				candidates = 
					new FRGLDMMatcher<>().extractCorrespondenceCandidates(
							pointsChA,
							pointsChB,
							redundancy,
							ratioOfDistance ).stream().map( v -> (PointMatch)v).collect( Collectors.toList() );
	
				System.out.println( "candidates (FRGDLDM): " + candidates.size() + " (" + pointsChA.size() + " <> " + pointsChB.size() + ")" );
			}
			else
			{
				candidates = 
					new RGLDMMatcher<>().extractCorrespondenceCandidates(
							pointsChA,
							pointsChB,
							3,
							redundancy,
							ratioOfDistance,
							Double.MAX_VALUE ).stream().map( v -> (PointMatch)v).collect( Collectors.toList() );
	
				System.out.println( "candidates (RGLDMMatcher): " + candidates.size() );
			}
	
			if ( candidates.size() < minNumInliers )
			{
				System.out.println( "Not enough candidates=" + candidates.size() + " (" + pointsChA.size() + " <> " + pointsChB.size() + ")" );
				return new ArrayList<PointMatch>();
			}
	
			final MultiConsensusFilter filter = new MultiConsensusFilter<>(
					matchingModel,
	//				new Transform.InterpolatedAffineModel2DSupplier(
	//				(Supplier<AffineModel3D> & Serializable)AffineModel3D::new,
	//				(Supplier<RigidModel3D> & Serializable)RigidModel3D::new, 0.25),
	//				(Supplier<TranslationModel3D> & Serializable)TranslationModel3D::new,
	//				(Supplier<RigidModel3D> & Serializable)RigidModel3D::new,
					numIterations,
					maxEpsilon,
					0,
					minNumInliers);

			matches = filter.filter(candidates);

			if ( matches.size() > 0 )
				System.out.println( "matches: " + matches.size() );
			else
				System.out.println( "WARNING: NO matches!" );
		}
		else
		{
			matches = null;
		}

		if ( doICP && ( matches == null || matches.size() > 4 ) )
		{
			final Model model = icpModel.get();
			final ArrayList<PointMatch> descriptorMatches = matches;

			try
			{
				if ( matches != null )
				{
					// init with current fit
					model.fit( matches );
					System.out.println( model );
				}

				IterativeClosestPointPairwise<InterestPoint> icp =
						new IterativeClosestPointPairwise<>(
								new IterativeClosestPointParameters( model, maxDistanceICP, maxNumIterationsICP ) );

				// not enough points to build a descriptor
				if ( pointsChA.size() < model.getMinNumMatches() || pointsChB.size() < model.getMinNumMatches() )
					return new ArrayList<PointMatch>();

				final PairwiseResult<InterestPoint> icpResult = icp.match( pointsChA, pointsChB );
				final ArrayList< PointMatch > candidatesICP = new ArrayList<>(
						icpResult.getInliers().stream().map( v -> (PointMatch)v ).collect( Collectors.toList() ) );
				final double avgError = icpResult.getError();

				final MultiConsensusFilter filterICP = new MultiConsensusFilter<>(
										icpModel,
										//(Supplier<AffineModel3D> & Serializable)AffineModel3D::new,
										//(Supplier<TranslationModel3D> & Serializable)TranslationModel3D::new,
										numIterationsICP,
										Math.min( avgError * 3.0, maxEpsilonICP ),
										0,
										minNumInliersICP);

				matches = filterICP.filter( candidatesICP );

				System.out.println( matches.size() + "/" + ( candidatesICP.size() + matches.size() ) + " from ICP." );

			} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
				e.printStackTrace();
				return descriptorMatches;
			}
		}

		return matches;
	}

	public static Pair< ArrayList<PointMatch>, Double > matchZSteps(
			final int desiredBlockSize,
			final boolean doICP,
			List<InterestPoint> pointsChAIn,
			List<InterestPoint> pointsChBIn,
			final int firstSliceIndex,
			final int localLastSliceIndex ) throws FormatException, IOException
	{
		final int numNeighbors = 3;
		final int redundancy = 0;
		final double ratioOfDistance = 5;
		final int numIterations = 100000;
		final double maxEpsilon = 5;
		final int minNumInliers = 20;

		// ICP
		final double maxDistanceICP = maxEpsilon;
		final int maxNumIterationsICP = 100;
		final int minNumInliersICP = 100;
		final int numIterationsICP = 10000;
		final double maxEpsilonICP = maxDistanceICP / 2.0;

		int blocksWithMatches = 0;
		int blocksWithoutMatches = 0;

		// not enough points to build a descriptor
		if ( pointsChAIn.size() < numNeighbors + redundancy + 1 || pointsChBIn.size() < numNeighbors + redundancy + 1 )
			return null;

		final ArrayList<PointMatch> allMatches = new ArrayList<PointMatch>();

		final int numBlocks = Math.max( 1, (int)Math.round( (localLastSliceIndex - firstSliceIndex ) / (double)desiredBlockSize ) );

		final double stepSize = (localLastSliceIndex - firstSliceIndex ) / (double)numBlocks;

		System.out.println( "actual stepSize: " + stepSize + ", desired: " + desiredBlockSize );

		for ( double z = firstSliceIndex; Math.round( z + stepSize / 2 ) < localLastSliceIndex; z += stepSize / 2 )
		{
			System.out.println( "\nmatching from " + z + " to " + (z+stepSize) );

			final RealInterval matchInterval = new FinalRealInterval(
					new double[] {
							-Double.MAX_VALUE,
							-Double.MAX_VALUE,
							z },
					new double[] {
							Double.MAX_VALUE,
							Double.MAX_VALUE,
							(z+stepSize)
					} );

			final ArrayList<PointMatch> matches = matchBlock(pointsChAIn, pointsChBIn, matchInterval, matchInterval,
					true, numNeighbors, redundancy, ratioOfDistance, numIterations, maxEpsilon, minNumInliers, doICP,
					maxDistanceICP, maxNumIterationsICP, minNumInliersICP, numIterationsICP, maxEpsilonICP);

			if ( matches.size() > 0 )
				++blocksWithMatches;
			else
				++blocksWithoutMatches;

			// build two lookup trees for existing Interestpoints that were matched
			HashMap< InterestPoint, PointMatch > p1 = new HashMap<>();
			HashMap< InterestPoint, PointMatch > p2 = new HashMap<>();

			for ( final PointMatch pm : allMatches )
			{
				p1.put( (InterestPoint)pm.getP1(), pm );
				p2.put( (InterestPoint)pm.getP2(), pm );
			}
		
			int sameMatch = 0;
			int differentMatch = 0;
			int added = 0;

			for ( final PointMatch pm : matches )
			{
				InterestPoint ip1 = (InterestPoint)pm.getP1();
				InterestPoint ip2 = (InterestPoint)pm.getP2();
				
				if ( p1.containsKey( ip1 ) )
				{
					if ( ((InterestPoint)p1.get( ip1 ).getP2()).getId() == ip2.getId() )
						++sameMatch;
					else
						++differentMatch;
				}
				else if ( p2.containsKey( ip2 ) )
				{
					if ( ((InterestPoint)p2.get( ip2 ).getP1()).getId() == ip1.getId() )
						++sameMatch;
					else
						++differentMatch;
				}
				else
				{
					allMatches.add( pm );
					++added;
				}
			}

			System.out.println( "added: " + added  + " same: " + sameMatch + " different: " + differentMatch );
		}

		return new ValuePair<>( allMatches, (double)blocksWithMatches / (double)( blocksWithMatches + blocksWithoutMatches ) );
	}

	protected static double getError( List<PointMatch> matches, final CoordinateTransform model ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		TranslationModel3D dummy = new TranslationModel3D();

		for ( final PointMatch pm : matches )
		{
			pm.getP1().apply( model );
			pm.getP2().apply( dummy ); // make sure the world coordinates are ok
		}
		
		return PointMatch.meanDistance( matches );
	}

	protected static double getMaxError( List<PointMatch> matches, final CoordinateTransform model ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		TranslationModel3D dummy = new TranslationModel3D();

		for ( final PointMatch pm : matches )
		{
			pm.getP1().apply( model );
			pm.getP2().apply( dummy ); // make sure the world coordinates are ok
		}
		
		return PointMatch.maxDistance( matches );
	}

	protected static void error( List<PointMatch> matches, final CoordinateTransform model ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		System.out.println( model.getClass().getSimpleName() + " (" + getError( matches, model ) + ") " + model );
	}

	protected static BdvStackSource<?> displayCam(
			final BdvStackSource<?> bdv,
			final String channel,
			final String cam, 
			final List<Slice> slices,
			final RandomAccessible<AffineTransform2D> alignments,
			final AffineTransform2D camTransform,
			final AffineGet transform,
			final int firstSliceIndex,
			final int lastSliceIndex ) throws FormatException, IOException
	{
		/* this is the inverse */
		final String title = channel + " " + cam;

		return ViewISPIMStack.showCamSource(
				bdv,
				title,
				slices,
				new UnsignedShortType(0),
				Interpolation.NEARESTNEIGHBOR,
				camTransform.inverse(), // pass the forward transform
				transform,
				alignments,
				firstSliceIndex,
				lastSliceIndex);
	}

	protected static void viewBlock( final HashMap<String, List<Slice>> ch, final Block block, final int firstSliceIndex, final int localLastSlice, final String n5Path, final String id ) throws IOException, FormatException
	{
		//final HashMap<String, List<Slice>> ch = stacks.get( block.channel );
		final List< Slice > slices = ch.get( block.cam );

		// this is the inverse
		final AffineTransform2D camtransform = block.getTransform();//camTransforms.get( block.channel ).get( block.cam );

		final N5FSReader n5Local = new N5FSReader(
				n5Path,
				new GsonBuilder().registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final ArrayList<AffineTransform2D> transforms = n5Local.getAttribute(
				id + "/" + block.channel,
				"transforms",
				new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

		//if ( block.from != 200 )
		//	return new Tuple2<>(block, new ArrayList<>());

		final RandomAccessible<AffineTransform2D> alignmentTransforms = Views.extendBorder(new ListImg<>(transforms, transforms.size()));

		System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + ", ch=" + block.channel + " (cam=" + block.cam + "): opening images." );

		final Pair<RandomAccessibleInterval< UnsignedShortType >, RandomAccessibleInterval< UnsignedShortType >> imgs =
				PairwiseAlignChannelsUtil.openRandomAccessibleIntervals(
						slices,
						new UnsignedShortType(0),
						Interpolation.NLINEAR,
						camtransform,
						alignmentTransforms,//alignments.get( channelA ),
						Math.max( firstSliceIndex, block.from  - block.gaussOverhead ),
						Math.min( localLastSlice, block.to + block.gaussOverhead ) );

		final ImagePlus impA = ImageJFunctions.wrap(imgs.getA(), block.channel, Executors.newFixedThreadPool( 8 ) ).duplicate();
		impA.setDimensions( 1, impA.getStackSize(), 1 );
		impA.resetDisplayRange();
		impA.show();
		
	}

	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		System.out.println( id );

		//visualizeDetections(n5Path, id, channelA, camA );
		//SimpleMultiThreading.threadHaltUnClean();

		/*
		// System property for locally calling spark has to be set, e.g. -Dspark.master=local[4]
		final String sparkLocal = System.getProperty( "spark.master" );

		// only do that if the system property is not set
		if ( sparkLocal == null || sparkLocal.trim().length() == 0 )
		{
			System.out.println( "Spark System property not set: " + sparkLocal );
			System.setProperty( "spark.master", "local[" + Math.max( 1, Runtime.getRuntime().availableProcessors() ) + "]" );
		}

		System.out.println( "Spark System property is: " + System.getProperty( "spark.master" ) );

		final SparkConf conf = new SparkConf().setAppName("SparkAlignChannels");

		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");


		final ArrayList< Block > blocks = new ArrayList<>();
		blocks.addAll( assembleBlocks( n5Path, id, channelA, camA, blocksize, 2.0, 0.004, minIntensity, maxIntensity ) );
		blocks.addAll( assembleBlocks( n5Path, id, channelB, camB, blocksize, 2.0, 0.004, minIntensity, maxIntensity ) );
		extractPoints( sc, n5Path, blocks );

		sc.close();
		*/

		//visualizeDetections(n5Path, id, channelA, camA );
		//visualizeDetections(n5Path, id, channelB, camB );
		//SimpleMultiThreading.threadHaltUnClean(); //660.9999999999999 to 727.0999999999999

		//align( n5Path, id, channelA, channelB, camA, camB, 25, true );

		visualizeAlignment( n5Path, id, channelA, channelB, camA, camB );

		return null;
	}
}
