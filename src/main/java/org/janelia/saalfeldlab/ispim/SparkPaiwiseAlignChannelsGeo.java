package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Random;
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
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.Interpolation;
import ij.ImageJ;
import ij.ImagePlus;
import loci.formats.FormatException;
import mpicbg.models.AffineModel3D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.MovingLeastSquaresTransform;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.list.ListImg;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.CorrespondingIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.InterpolatingNonRigidRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.NonRigidTools;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.NonrigidIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.SimpleReferenceIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.NumericAffineModel3D;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.fastrgldm.FRGLDMMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointParameters;
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

	@Option(names = "--first", required = false, description = "First slice index, e.g. 0 (default 0)")
	private int firstSliceIndex = 0;

	@Option(names = "--last", required = false, description = "Last slice index, e.g. 1000 (default MAX)")
	private int lastSliceIndex = Integer.MAX_VALUE;

	@Option(names = "--tryLoadingPoints", required = false, description = "Try to load previously saved points (default: false)")
	private boolean tryLoadingPoints = false;

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
		final String channel, cam;
		final double sigma, threshold, minIntensity, maxIntensity;
		final double[] transform;

		public Block( final int from, final int to, final String channel, final String cam, final AffineTransform2D camtransform, final double sigma, final double threshold, final double minIntensity, final double maxIntensity )
		{
			this.from = from;
			this.to = to;
			this.channel = channel;
			this.cam = cam;
			this.sigma = sigma;
			this.threshold = threshold;
			this.gaussOverhead = DoGImgLib2.radiusDoG( 2.0 );
			this.transform = camtransform.getRowPackedCopy();
			this.minIntensity = minIntensity;
			this.maxIntensity = maxIntensity;
		}

		public AffineTransform2D getTransform()
		{
			final AffineTransform2D t = new AffineTransform2D();
			t.set( transform );
			return t;
		}
	}

	public static void alignChannels(
			final JavaSparkContext sc,
			final String n5Path,
			final String id,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final double minIntensity,
			final double maxIntensity,
			final int firstSliceIndex,
			final int lastSliceIndex,
			final int blockSize,
			final boolean tryLoadingPoints ) throws FormatException, IOException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		System.out.println( new Date(System.currentTimeMillis() ) + ": Opening N5." );

		final N5FSReader n5 = new N5FSReader(
				n5Path,
				new GsonBuilder().registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final HashMap<String, HashMap<String, AffineTransform2D>> camTransforms = n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, AffineTransform2D>>>() {}.getType());
		final ArrayList<String> ids = n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());

		if (!ids.contains(id))
		{
			System.err.println("Id '" + id + "' does not exist in '" + n5Path + "'.");
			return;
		}
	
		System.out.println( new Date(System.currentTimeMillis() ) + ": Loading alignments." );

		final HashMap<String, HashMap<String, List<Slice>>> stacks = new HashMap<>();
		final HashMap<String, RandomAccessible<AffineTransform2D>> alignments = new HashMap<>();

		int localLastSliceIndex = lastSliceIndex;

		for (final Entry<String, HashMap<String, AffineTransform2D>> channel : camTransforms.entrySet()) {
			final HashMap<String, List<Slice>> channelStacks = new HashMap<>();
			stacks.put(channel.getKey(), channelStacks);

			/* stack alignment transforms */
			final ArrayList<AffineTransform2D> transforms = n5.getAttribute(
					id + "/" + channel.getKey(),
					"transforms",
					new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

			final RandomAccessible<AffineTransform2D> alignmentTransforms = Views.extendBorder(new ListImg<>(transforms, transforms.size()));

			alignments.put(channel.getKey(), alignmentTransforms);

			/* add all camera stacks that exist */
			for (final String camKey : channel.getValue().keySet()) {
				final String groupName = id + "/" + channel.getKey() + "/" + camKey;
				if (n5.exists(groupName)) {
					final ArrayList<Slice> stack = n5.getAttribute(
							groupName,
							"slices",
							new TypeToken<ArrayList<Slice>>(){}.getType());
					channelStacks.put(
							camKey,
							stack);

					localLastSliceIndex = Math.min(localLastSliceIndex, stack.size() - 1);
				}
			}
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": localLastSliceIndex=" + localLastSliceIndex );
		final Gson gson = new GsonBuilder().registerTypeAdapter(
				AffineTransform2D.class,
				new AffineTransform2DAdapter()).create();

		System.out.println(gson.toJson(camTransforms));
		System.out.println(gson.toJson(ids));
		// System.out.println(new Gson().toJson(stacks));

		new ImageJ();

		ArrayList<InterestPoint> pointsChA = null;
		ArrayList<InterestPoint> pointsChB = null;

		if ( tryLoadingPoints )
		{
			System.out.println( "trying to load points ... " );

			try
			{
				final String datasetNameA = id + "/" + channelA + "/Stack-DoG-detections";
				final DatasetAttributes datasetAttributesA = n5.getDatasetAttributes(datasetNameA);
	
				final String datasetNameB = id + "/" + channelB + "/Stack-DoG-detections";
				final DatasetAttributes datasetAttributesB = n5.getDatasetAttributes(datasetNameB);
	
				pointsChA = n5.readSerializedBlock(datasetNameA, datasetAttributesA, new long[] {0});
				pointsChB = n5.readSerializedBlock(datasetNameB, datasetAttributesB, new long[] {0});
			}
			catch ( Exception e ) // java.nio.file.NoSuchFileException
			{
				pointsChA = pointsChB = null;
				e.printStackTrace();
			}
		}

		if ( pointsChA == null || pointsChB == null )
		{
			if ( tryLoadingPoints )
				System.out.println( "could not load points ... " );

			System.out.println( "extracting points ... " );

			final int numSlices = localLastSliceIndex - firstSliceIndex + 1;
			final int numBlocks = numSlices / blockSize + (numSlices % blockSize > 0 ? 1 : 0);
			final int localLastSlice = localLastSliceIndex;
			final ArrayList<Block> blocks = new ArrayList<>();
	
			for ( int i = 0; i < numBlocks; ++i )
			{
				final int from  = i * blockSize + firstSliceIndex;
				final int to = Math.min( localLastSliceIndex, from + blockSize - 1 );
	
				final Block blockChannelA = new Block(from, to, channelA, camA, camTransforms.get( channelA ).get( camA ), 2.0, /*0.02*/0.007, minIntensity, maxIntensity );
				final Block blockChannelB = new Block(from, to, channelB, camB, camTransforms.get( channelB ).get( camB ), 2.0, /*0.01*/0.007, minIntensity, maxIntensity );
	
				blocks.add( blockChannelA );
				blocks.add( blockChannelB );
		
				System.out.println( "block " + i + ": " + from + " >> " + to );

				/*
				// visible error: from=200, to=219, ch=Ch515+594nm (cam=cam1) Pos012
				if ( blockChannelB.from == 200 )
				{
					new ImageJ();
					viewBlock( stacks.get( blockChannelB.channel ), blockChannelB, firstSliceIndex, localLastSlice, n5Path, id );
					SimpleMultiThreading.threadHaltUnClean();
				}*/
			}
	
			final JavaRDD<Block> rddSlices = sc.parallelize( blocks );
	
			final JavaPairRDD<Block, ArrayList< InterestPoint >> rddFeatures = rddSlices.mapToPair(
				block ->
				{
					final HashMap<String, List<Slice>> ch = stacks.get( block.channel );
					final List< Slice > slices = ch.get( block.cam );
	
					/* this is the inverse */
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
									camtransform.inverse(), // pass the forward transform
									alignmentTransforms,//alignments.get( channelA ),
									Math.max( firstSliceIndex, block.from  - block.gaussOverhead ),
									Math.min( localLastSlice, block.to + block.gaussOverhead ) );
	
					System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + ", ch=" + block.channel + " (cam=" + block.cam + "): finding points." );
	
					final ExecutorService service = Executors.newFixedThreadPool( 1 );
					final ArrayList< InterestPoint > initialPoints =
							DoGImgLib2.computeDoG(
									imgs.getA(),
									imgs.getB(),
									block.sigma,
									block.threshold,
									1, /*localization*/
									false, /*findMin*/
									true, /*findMax*/
									block.minIntensity, /* min intensity */
									block.maxIntensity, /* max intensity */
									service,
									1 );
	
					service.shutdown();
	
					// exclude points that lie within the Gauss overhead
					final ArrayList< InterestPoint > points = new ArrayList<>();
	
					for ( final InterestPoint ip : initialPoints )
						if ( ip.getDoublePosition( 2 ) > block.from - 0.5 && ip.getDoublePosition( 2 ) < block.to + 0.5 )
							points.add( ip );
	
					System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + ", ch=" + block.channel + " (cam=" + block.cam + "): " + points.size() + " points" );
	
					/*if ( block.from == 200 )
					{
						final ImagePlus impA = ImageJFunctions.wrap(imgs.getA(), block.channel, Executors.newFixedThreadPool( 8 ) ).duplicate();
						impA.setDimensions( 1, impA.getStackSize(), 1 );
						impA.resetDisplayRange();
						impA.show();
		
						//final ImagePlus impAw = ImageJFunctions.wrap(imgs.getB(), block.channel + "_w", Executors.newFixedThreadPool( 8 ) ).duplicate();
						//impAw.setDimensions( 1, impAw.getStackSize(), 1 );
						//impAw.resetDisplayRange();
						//impAw.show();
	
						final long[] dim = new long[ imgs.getA().numDimensions() ];
						final long[] min = new long[ imgs.getA().numDimensions() ];
						imgs.getA().dimensions( dim );
						imgs.getA().min( min );
	
						final RandomAccessibleInterval< FloatType > dots = Views.translate( ArrayImgs.floats( dim ), min );
						final RandomAccess< FloatType > rDots = dots.randomAccess();
	
						for ( final InterestPoint ip : points )
						{
							for ( int d = 0; d < dots.numDimensions(); ++d )
								rDots.setPosition( Math.round( ip.getFloatPosition( d ) ), d );
	
							rDots.get().setOne();
						}
	
						Gauss3.gauss( 1, Views.extendZero( dots ), dots );
						final ImagePlus impP = ImageJFunctions.wrap( dots, "detections_" + block.channel, Executors.newFixedThreadPool( 8 ) ).duplicate();
						impP.setDimensions( 1, impP.getStackSize(), 1 );
						impP.setSlice( impP.getStackSize() / 2 );
						impP.resetDisplayRange();
						impP.show();
					}*/
	
					return new Tuple2<>(block, points);
				});
	
			/* cache the booleans, so features aren't regenerated every time */
			rddFeatures.cache();
	
			/* collect the results */
			final List<Tuple2<Block, ArrayList< InterestPoint >>> results = rddFeatures.collect();
	
			pointsChA = new ArrayList<>();
			pointsChB = new ArrayList<>();
	
			for ( final Tuple2<Block, ArrayList< InterestPoint >> tuple : results )
			{
				if ( tuple._1().channel.equals( channelA ) )
					pointsChA.addAll( tuple._2() );
				else
					pointsChB.addAll( tuple._2() );

				if ( tuple._2().size() == 0 )
					System.out.println( "Warning: block " + tuple._1.from + " has 0 detections");
			}

			System.out.println( "fixing ids (they were duplicate due to paralell processing) ... " );

			for ( int i = 0; i < pointsChA.size(); ++i )
				pointsChA.set( i, new InterestPoint( i, pointsChA.get( i ).getL() ) );

			for ( int i = 0; i < pointsChB.size(); ++i )
				pointsChB.set( i, new InterestPoint( i, pointsChB.get( i ).getL() ) );

			System.out.println( "saving points ... " );

			final N5FSWriter n5Writer = new N5FSWriter(n5Path);

			if (pointsChA.size() > 0)
			{
				final String featuresGroupName = n5Writer.groupPath(id + "/" + channelA, "Stack-DoG-detections");
	
				if (n5Writer.exists(featuresGroupName))
					n5Writer.remove(featuresGroupName);
				
				n5Writer.createDataset(
						featuresGroupName,
						new long[] {1},
						new int[] {1},
						DataType.OBJECT,
						new GzipCompression());
	
				final String datasetName = id + "/" + channelA + "/Stack-DoG-detections";
				final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);
	
				n5Writer.writeSerializedBlock(
						pointsChA,
						datasetName,
						datasetAttributes,
						new long[] {0});
			}
	
			if (pointsChB.size() > 0)
			{
				final String featuresGroupName = n5Writer.groupPath(id + "/" + channelB, "Stack-DoG-detections");
	
				if (n5Writer.exists(featuresGroupName))
					n5Writer.remove(featuresGroupName);
				
				n5Writer.createDataset(
						featuresGroupName,
						new long[] {1},
						new int[] {1},
						DataType.OBJECT,
						new GzipCompression());
	
				final String datasetName = id + "/" + channelB + "/Stack-DoG-detections";
				final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);
	
				n5Writer.writeSerializedBlock(
						pointsChB,
						datasetName,
						datasetAttributes,
						new long[] {0});
			}
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": channelA: " + pointsChA.size() + " points" );
		System.out.println( new Date(System.currentTimeMillis() ) + ": channelB: " + pointsChB.size() + " points" );

		//
		// alignment
		//
		ArrayList<PointMatch> matches = null;

		// try to load the matches as well
		if ( tryLoadingPoints )
		{
			try
			{
				final String datasetName = id + "/matches_" + channelA + "_" + channelB;
				final DatasetAttributes datasetAttributes = n5.getDatasetAttributes(datasetName);

				matches = n5.readSerializedBlock(datasetName, datasetAttributes, new long[] {0});

				System.out.println( new Date(System.currentTimeMillis() ) + ": Loaded " + matches.size() + " matches" );
			}
			catch ( Exception e ) // java.nio.file.NoSuchFileException
			{
				matches = null;
			}
		}

		if ( matches == null )
		{
			if ( tryLoadingPoints )
				System.out.println( "could not load matches ... " );

			System.out.println( "performing block-wise (in z) matching ... " );

			ArrayList<PointMatch> matchesTmp = matchSteps( 10, pointsChA, pointsChB, firstSliceIndex, localLastSliceIndex, channelA, channelB, camA, camB, camTransforms, stacks, alignments );
	
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

			System.out.println( "\nDeforming ChA points " );

			List< InterestPoint > pointsChANew = new ArrayList<InterestPoint>();
			for ( final InterestPoint p : pointsChA )
			{
				if ( p.getId() == 100 ) System.out.print( Util.printCoordinates( p.getL() ) + " mapped to " );
				final double[] l = p.getL().clone();
				mls.applyInPlace( l );
				pointsChANew.add( new InterestPoint( p.getId(), l ) );
				if ( p.getId() == 100 ) System.out.println( Util.printCoordinates( l ) );
			}
			matchesTmp = matchICP( pointsChANew, pointsChB, firstSliceIndex, localLastSliceIndex, channelA, channelB, camA, camB, camTransforms, stacks, alignments );

			// fix matches back to use non-deformed points!
			System.out.println( "\nRestoring ChA points " );

			HashMap<Integer, InterestPoint > lookUpA = new HashMap<>();
			for ( final InterestPoint p : pointsChA )
				lookUpA.put( p.getId(), p );

			matches = new ArrayList<PointMatch>();

			for ( final PointMatch pm : matchesTmp )
				matches.add(
						new PointMatch(
								lookUpA.get( ((InterestPoint)pm.getP1()).getId() ),
								(InterestPoint)pm.getP2() ) );

			// write matches
			System.out.println( "saving matches ... " );

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
		}

		/*
		BdvStackSource<?> bdv = null;

		final AffineTransform3D transformB_a = TransformationTools.getAffineTransform( affine ).inverse();

		bdv = displayOverlap( bdv, channelA, camA, stacks.get( channelA ).get( camA ), alignments.get( channelA ), camTransforms.get( channelA ).get( camA ), new AffineTransform3D(), firstSliceIndex, localLastSliceIndex );
		bdv = displayOverlap( bdv, channelB, camB, stacks.get( channelB ).get( camB ), alignments.get( channelB ), camTransforms.get( channelB ).get( camB ), transformB_a, firstSliceIndex, localLastSliceIndex );
		*/

		// Ch515+594nm (B) stays fixed, Ch488+561+647nm (A) is transformed
		// because we align Ch405nm to Ch515+594nm
		final double alpha = 1.0;
		final boolean virtual = false;
		final long[] controlPointDistance = new long[] { 100, 100, 100 };

		// get the input image (same coordinate space as correspondences)
		System.out.println( new Date( System.currentTimeMillis() ) + ": Preparing channelA: " + channelA );

		Pair<RealRandomAccessible<UnsignedShortType>, Interval> prepareCamSource =
				ViewISPIMStack.prepareCamSource(
						stacks.get( channelA ).get( camA ),
						new UnsignedShortType(0),
						Interpolation.NEARESTNEIGHBOR,
						camTransforms.get( channelA ).get( camA ).inverse(), // pass the forward transform
						new AffineTransform3D(),
						alignments.get( channelA ),
						firstSliceIndex,
						localLastSliceIndex);

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
		
		BdvStackSource<?> bdv = null;

		bdv = displayOverlap( bdv, channelB, camB, stacks.get( channelB ).get( camB ), alignments.get( channelB ), camTransforms.get( channelB ).get( camB ), new AffineTransform3D(), firstSliceIndex, localLastSliceIndex );
		bdv.setDisplayRange(0, 512);
		bdv.setColor( new ARGBType( ARGBType.rgba(255, 0, 255, 0)));

		//AffineModel3D affine = new AffineModel3D();
		//affine.fit( matches );
		//bdv = BdvFunctions.show( transformedA, prepareCamSource.getB(), "affine A", new BdvOptions().addTo( bdv ) );
		bdv = BdvFunctions.show( transformedA, prepareCamSource.getB(), "non-rigid A", new BdvOptions().addTo( bdv ) );
		bdv.setDisplayRange(0, 1024);
		bdv.setColor( new ARGBType( ARGBType.rgba(0, 255, 0, 0)));

		System.out.println( new Date( System.currentTimeMillis() ) + ": done" );


		// cam4 (Ch488+561+647nm) vs cam4 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch488+561+647nm)**
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

	public static ArrayList<PointMatch> matchSteps(
			final int numBlocks,
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
		final int numNeighbors = 3;
		final int redundancy = 1;
		final double ratioOfDistance = 3;
		final int numIterations = 1000;
		final double maxEpsilon = 0.5;
		final int minNumInliers = 200;

		// not enough points to build a descriptor
		if ( pointsChAIn.size() < numNeighbors + redundancy + 1 || pointsChBIn.size() < numNeighbors + redundancy + 1 )
			return null;

		final ArrayList<PointMatch> allMatches = new ArrayList<PointMatch>();

		final double stepSize = (localLastSliceIndex - firstSliceIndex ) / (double)numBlocks;
		
		for ( double z = firstSliceIndex; z<= localLastSliceIndex; z += stepSize / 2 )
		{
			System.out.println( "\nmatching form " + z + " to " + (z+stepSize) );

			ArrayList<InterestPoint> pointsChA = new ArrayList<InterestPoint>();
			ArrayList<InterestPoint> pointsChB = new ArrayList<InterestPoint>();

			for ( final InterestPoint ip : pointsChAIn )
				if ( ip.getL()[ 2 ] >= z && ip.getL()[ 2 ] <= (z+stepSize) )
				{
					// copy or reset world coordinates as they are used by default to build descriptors
					// but they are changed by the previous RANSAC run if overlapping sets of points
					// are being used
					//for ( int d = 0; d < ip.getL().length; ++d )
					//	ip.getW()[ d ] = ip.getL()[ d ];
					pointsChA.add( ip.duplicate() );
				}

			for ( final InterestPoint ip : pointsChBIn )
				if ( ip.getL()[ 2 ] >= z && ip.getL()[ 2 ] <= (z+stepSize) )
				{
					//for ( int d = 0; d < ip.getL().length; ++d )
					//	ip.getW()[ d ] = ip.getL()[ d ];

					pointsChB.add( ip.duplicate() );
				}

			if ( pointsChA.size() < numNeighbors + redundancy + 1 || pointsChB.size() < numNeighbors + redundancy + 1 )
			{
				System.out.println( "No matches." );
				continue;
			}

			final List< PointMatch > candidates = 
					new FRGLDMMatcher<>().extractCorrespondenceCandidates(
							pointsChA,
							pointsChB,
							redundancy,
							ratioOfDistance ).stream().map( v -> (PointMatch)v).collect( Collectors.toList() );
	
			/*final List< PointMatch > candidates = 
					new RGLDMMatcher<>().extractCorrespondenceCandidates(
							pointsChA,
							pointsChB,
							3,
							redundancy,
							ratioOfDistance,
							Double.MAX_VALUE ).stream().map( v -> (PointMatch)v).collect( Collectors.toList() );*/
	
			double minZ = localLastSliceIndex;
			double maxZ = firstSliceIndex;
	
			for ( final PointMatch pm : candidates )
			{
				minZ = Math.min( minZ, Math.min( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
				maxZ = Math.max( maxZ, Math.max( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
			}
	
			System.out.println( "candidates (FRGDLDM): " + candidates.size() + " from(z) " + minZ + " to(z) " + maxZ );
	
			final MultiConsensusFilter filter = new MultiConsensusFilter<>(
	//				new Transform.InterpolatedAffineModel2DSupplier(
					(Supplier<AffineModel3D> & Serializable)AffineModel3D::new,
	//				(Supplier<RigidModel3D> & Serializable)RigidModel3D::new, 0.25),
	//				(Supplier<TranslationModel3D> & Serializable)TranslationModel3D::new,
	//				(Supplier<RigidModel3D> & Serializable)RigidModel3D::new,
					numIterations,
					maxEpsilon,
					0,
					minNumInliers);
	
			final ArrayList<PointMatch> matches = filter.filter(candidates);
	
			minZ = localLastSliceIndex;
			maxZ = firstSliceIndex;
	
			for ( final PointMatch pm : matches )
			{
				minZ = Math.min( minZ, Math.min( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
				maxZ = Math.max( maxZ, Math.max( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
			}

			if ( matches.size() > 0 )
				System.out.println( "matches: " + matches.size() + " from(z) " + minZ + " to(z) " + maxZ );
			else
				System.out.println( "WARNING: NO matches!" );


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

		return allMatches;
	}

	protected static void error( List<PointMatch> matches, final CoordinateTransform model ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		TranslationModel3D dummy = new TranslationModel3D();

		for ( final PointMatch pm : matches )
		{
			pm.getP1().apply( model );
			pm.getP2().apply( dummy ); // make sure the world coordinates are ok
		}
		
		System.out.println( model.getClass().getSimpleName() + " (" + PointMatch.meanDistance( matches ) + ")" + model );
	}

	protected static BdvStackSource<?> displayOverlap(
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

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		// System property for locally calling spark has to be set, e.g. -Dspark.master=local[4]
		final String sparkLocal = System.getProperty( "spark.master" );

		// only do that if the system property is not set
		if ( sparkLocal == null || sparkLocal.trim().length() == 0 )
		{
			System.out.println( "Spark System property not set: " + sparkLocal );
			System.setProperty( "spark.master", "local[" + Math.max( 1, Runtime.getRuntime().availableProcessors() / 3 * 2 ) + "]" );
		}

		System.out.println( "Spark System property is: " + System.getProperty( "spark.master" ) );

		final SparkConf conf = new SparkConf().setAppName("SparkAlignChannels");

		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		alignChannels(
				sc,
				n5Path,
				id,
				channelA,
				channelB,
				camA,
				camB,
				minIntensity,
				maxIntensity,
				firstSliceIndex,
				lastSliceIndex,
				blocksize,
				tryLoadingPoints );

		sc.close();

		return null;
	}
}
