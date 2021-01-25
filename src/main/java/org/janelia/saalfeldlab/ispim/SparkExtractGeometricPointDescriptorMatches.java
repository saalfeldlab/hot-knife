/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.ispim;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.MultiConsensusFilter;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;

import com.google.common.reflect.TypeToken;
import com.google.gson.GsonBuilder;

import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import loci.formats.FormatException;
import loci.formats.in.TiffReader;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel2D;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.io.TextFileAccess;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.fastrgldm.FRGLDMMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMMatcher;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import scala.Tuple2;

/**
 * Align a 3D N5 dataset.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command(
		name = "SparkExtractSIFTMatches",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Extract SIFT matches from an iSPIM camera series")
public class SparkExtractGeometricPointDescriptorMatches implements Callable<Void>, Serializable {

	private static final long serialVersionUID = 1030006363999084424L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--id", required = true, description = "Stack key, e.g. Pos012")
	private String id = null;

	@Option(names = "--channel", required = true, description = "Channel key, e.g. Ch488+561+647nm")
	private String channel = null;

	@Option(names = "--cam", required = true, description = "Cam key, e.g. cam1")
	private String cam = null;

	@Option(names = {"-d", "--distance"}, required = false, description = "max distance for two slices to be compared (default: 3)")
	private int distance = 3;

	@Option(names = {"-r", "--redundancy"}, required = false, description = "redundancy for geometric descriptor matching (default: 0)")
	private int redundancy = 1;

	@Option(names = "--minNumInliers", required = false, description = "minimal number of inliers for RANSAC (default: 25)")
	private int minNumInliers = 25;

	@Option(names = "--minIntensity", required = false, description = "min intensity, if minIntensity==maxIntensity determine min/max per slice (default: 0)")
	private double minIntensity = 0;

	@Option(names = "--maxIntensity", required = false, description = "max intensity, if minIntensity==maxIntensity determine min/max per slice (default: 0)")
	private double maxIntensity = 2048;

	@Option(names = "--maxEpsilon", required = true, description = "residual threshold for filter in world pixels")
	private double maxEpsilon = 5.0;

	@Option(names = "--iterations", required = false, description = "number of iterations")
	private int numIterations = 2000;

	public static boolean showOnly = false;

	@SuppressWarnings("serial")
	public static void extractGeometricDescriptorMatches(
			final JavaSparkContext sc,
			final String n5Path,
			final String id,
			final String channel,
			final String cam,
			final int distance,
			final int redundancy,
			final double minIntensity,
			final double maxIntensity,
			final double maxEpsilon,
			final int minNumInliers,
			final int numIterations) throws IOException, FormatException {

		final ArrayList<Slice> stack;
		final String groupName;
		{
			final N5FSWriter n5 = new N5FSWriter(
					n5Path,
					new GsonBuilder().registerTypeAdapter(
							AffineTransform2D.class,
							new AffineTransform2DAdapter()));

			groupName = n5.groupPath(id, channel, cam);

			if (!n5.exists(groupName)) {
				System.err.println("Group '" + groupName + "' does not exist in '" + n5Path + "'.");
				return;
			}

			stack = n5.getAttribute(
					groupName,
					"slices",
					new TypeToken<ArrayList<Slice>>() {}.getType());

			final String featuresGroupName = n5.groupPath(groupName, "DoG-detections");
			if (n5.exists(featuresGroupName))
				n5.remove(featuresGroupName);
			n5.createDataset(
					featuresGroupName,
					new long[] {stack.size()},
					new int[] {1},
					DataType.OBJECT,
					new GzipCompression());

			final String matchesGroupName = n5.groupPath(groupName, "matches");
			if (n5.exists(matchesGroupName))
				n5.remove(matchesGroupName);
			n5.createDataset(
					matchesGroupName,
					new long[] {stack.size(), stack.size()},
					new int[] {1, 1},
					DataType.OBJECT,
					new GzipCompression());
			n5.setAttribute(
					matchesGroupName,
					"distance",
					distance);
		}

		/* get width and height from first slice */
		final int width, height;
		{
			try(final TiffReader firstSliceReader = new TiffReader()) {

				firstSliceReader.setId(stack.get(0).path);
				width = firstSliceReader.getSizeX();
				height = firstSliceReader.getSizeY();
				firstSliceReader.close();
			}
		}

		final ArrayList<Integer> slices = new ArrayList<>();

		for (int i = 0; i < stack.size(); ++i)
			slices.add(new Integer(i));

		System.out.println( "Stack has #slice=" + stack.size() );

		if ( showOnly )
		{
			System.out.println("showing stack only" );
			showStack( stack, slices, width, height );
			SimpleMultiThreading.threadHaltUnClean();
			return;
		}

		final double sigma = 2.5;
		double thr = 0.03;

		int numUnconnected = 0;
		int lastUnconnected = Integer.MAX_VALUE;
		int bestUnconnected = Integer.MAX_VALUE;
		double bestThr = -1;
		boolean extraRun = false;

		do
		{
			// none was good, run the best one again for saving
			if ( thr == bestThr )
				extraRun = true;

			System.out.println( new Date( System.currentTimeMillis() ) + ": running for thr=" + thr );

			final double threshold = thr;
			final boolean limitDetections = true;
			final int maxDetections = 15000;
			final int maxDetectionsTypeIndex = 0; // { "Brightest", "Around median (of those above threshold)", "Weakest (above threshold)" };
	
			final JavaRDD<Integer> rddSlices = sc.parallelize(slices);
	
			/* save features */
			final JavaPairRDD<Integer, Integer> rddFeatures = rddSlices.mapToPair(
					i ->  {
						final Slice sliceInfo = stack.get(i);
						final N5FSWriter n5Writer = new N5FSWriter(n5Path);
						final String datasetName = groupName + "/DoG-detections";
						final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);
	
						try (final TiffReader reader = new TiffReader()) {
							RandomAccessibleInterval slice = null;

							try
							{
								slice = (RandomAccessibleInterval)Opener.openSlice(
										reader,
										sliceInfo.path,
										sliceInfo.index,
										width,
										height);

								if (sliceInfo.affine != null && !sliceInfo.affineTransform().isIdentity() )
								{
									slice = Views.interval( Views.raster( RealViews.affineReal( Views.interpolate(Views.extendZero( slice ), new NLinearInterpolatorFactory()), sliceInfo.affineTransform() ) ), slice );
									System.out.println( "non identify affine transform (index=" + i + "):" + sliceInfo.path + ", " + sliceInfo.index + ": " +  sliceInfo.affineTransform() );
								}
							}
							catch ( Exception e )
							{
								System.out.println( "e:" + e );
								System.exit( 0 );
								//slice = ArrayImgs.unsignedShorts( width, height );
							}

							DoGImgLib2.silent = true;

							final ExecutorService service = Executors.newFixedThreadPool( 1 );

							ArrayList< InterestPoint > points =
									DoGImgLib2.computeDoG(
											Converters.convert(
												(RandomAccessibleInterval<RealType<?>>)slice,
												(a, b) -> b.setReal( a.getRealFloat() ),
											new FloatType()),
											null,
											sigma,
											threshold,
											1, /*localization*/
											false, /*findMin*/
											true, /*findMax*/
											minIntensity, /* min intensity */
											maxIntensity, /* max intensity */
											service, 1 );

							service.shutdown();

							/*
							if ( i.intValue() % 50 == 0 )
							{
								ImagePlus imp1 = ImageJFunctions.show(
										Converters.convert(
												(RandomAccessibleInterval<RealType<?>>)slice,
												(a, b) -> b.setReal(a.getRealFloat()),
											new FloatType())
										);
								imp1.setRoi( mpicbg.ij.util.Util.pointsToPointRoi(
										points ) );
								imp1.resetDisplayRange();
								imp1.setTitle( "s=" + i);
							}
							*/
	
							if ( limitDetections )
								points = (ArrayList< InterestPoint >)InterestPointTools.limitList( maxDetections, maxDetectionsTypeIndex, points );
	
							if (points.size() > 0) {
								n5Writer.writeSerializedBlock(
										points,
										datasetName,
										datasetAttributes,
										new long[] {i});
							}
	
							return new Tuple2<>(i, points.size());
						}
					});
	
			/* cache the booleans, so features aren't regenerated every time */
			rddFeatures.cache();
	
			/* run feature extraction */
			rddFeatures.count();
	
			/* match features */
			final int numNeighbors = 3;
			final double ratioOfDistance = 2.0;
			final double differenceThreshold = Double.MAX_VALUE;
	
			//new ImageJ();
			int max = 0, min = Integer.MAX_VALUE;
			HashMap<Integer, Integer> matchesCount = new HashMap<>();
	
			for ( final Tuple2<Integer, Integer> tuple : rddFeatures.collect() )
			{
				min = Math.min( tuple._2(), min );
				max = Math.max( tuple._2(), max );
				matchesCount.put( tuple._1(), 0 );
			}
	
			System.out.println( "min num detections: " + min );
			System.out.println( "max num detections: " + max );
			
			final JavaRDD<Integer> rddIndices = rddFeatures.filter(pair -> pair._2() > 0).map(pair -> pair._1());
			final JavaPairRDD<Integer, Integer> rddPairs = rddIndices.cartesian(rddIndices).filter(
					pair -> {
						final int diff = pair._2() - pair._1();
						return diff > 0 && diff <= distance;
					});
			final JavaPairRDD<Tuple2<Integer, Integer>, Integer> rddMatches = rddPairs.mapToPair(
					pair -> {
						final N5FSWriter n5Writer = new N5FSWriter(n5Path);
						final String datasetName = groupName + "/DoG-detections";
						final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);
	
						final ArrayList<InterestPoint> ip1 = n5Writer.readSerializedBlock(datasetName, datasetAttributes, new long[] {pair._1()});
						final ArrayList<InterestPoint> ip2 = n5Writer.readSerializedBlock(datasetName, datasetAttributes, new long[] {pair._2()});
	
						// not enough points to build a descriptor
						if ( ip1.size() < numNeighbors + redundancy + 1 || ip2.size() < numNeighbors + redundancy + 1 )
							return new Tuple2<>( new Tuple2<>(pair._1(), pair._2()), 0 );
	
	
						// only works with dim==3
						final HashMap<InterestPoint, InterestPoint > lookup1 = new HashMap<>();
						final HashMap<InterestPoint, InterestPoint > lookup2 = new HashMap<>();
						final ArrayList< InterestPoint> ip1_3d = new ArrayList<>();
						final ArrayList< InterestPoint> ip2_3d = new ArrayList<>();
						for ( final InterestPoint ip : ip1 )
						{
							final double[] l = new double[ ip.getL().length + 1 ];
							for ( int d = 0; d < ip.getL().length; ++d )
								l[ d ] = ip.getL()[ d ];
							l[ ip.getL().length ] = 0;
							final InterestPoint ip_3d = new InterestPoint(ip.getId(), l);
							ip1_3d.add( ip_3d );
							lookup1.put( ip_3d, ip );
						}
						for ( final InterestPoint ip : ip2 )
						{
							final double[] l = new double[ ip.getL().length + 1 ];
							for ( int d = 0; d < ip.getL().length; ++d )
								l[ d ] = ip.getL()[ d ];
							l[ ip.getL().length ] = 0;
							final InterestPoint ip_3d = new InterestPoint(ip.getId(), l);
							ip2_3d.add( ip_3d );
							lookup2.put( ip_3d, ip );
						}
						final List< PointMatch > candidates_3d = 
								new FRGLDMMatcher<>().extractCorrespondenceCandidates(
										ip1_3d,
										ip2_3d,
										redundancy,
										ratioOfDistance ).stream().map( v -> (PointMatch)v).collect( Collectors.toList() );
	
						final ArrayList< PointMatch > candidates = new ArrayList<>();
						for ( final PointMatch pm : candidates_3d )
							candidates.add( new PointMatch( lookup1.get( pm.getP1() ), lookup2.get( pm.getP2() ) ) );
	
						/*
						final List< PointMatch > candidates = 
								new RGLDMMatcher<>().extractCorrespondenceCandidates(
										ip1,
										ip2,
										numNeighbors,
										redundancy,
										ratioOfDistance,
										differenceThreshold ).stream().map( v -> (PointMatch)v).collect( Collectors.toList() );*/ 
	
						final MultiConsensusFilter filter = new MultiConsensusFilter<>(
	//							new Transform.InterpolatedAffineModel2DSupplier(
	//							(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
	//							(Supplier<RigidModel2D> & Serializable)RigidModel2D::new, 0.25),
								(Supplier<TranslationModel2D> & Serializable)TranslationModel2D::new,
	//							(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
								numIterations,
								maxEpsilon,
								0,
								minNumInliers);
	
						final ArrayList<PointMatch> matches = filter.filter(candidates);
	
						if (matches.size() > 0) {
	
							final String matchesDatasetName = groupName + "/matches";
							final DatasetAttributes matchesAttributes = n5Writer.getDatasetAttributes(matchesDatasetName);
							n5Writer.writeSerializedBlock(
									matches,
									matchesDatasetName,
									matchesAttributes,
									new long[] {pair._1(), pair._2()});
						}
	
						//if ( pair._1().intValue() == 50 && pair._2().intValue() == 51 || pair._1().intValue() == 100 && pair._2().intValue() == 101 )
						//	show( stack, width, height, candidates, pair._1().intValue(), pair._2().intValue() );
	
						return new Tuple2<>(
								new Tuple2<>(pair._1(), pair._2()),
								matches.size());
	
					});
	
			/* run matching */
			rddMatches.count();
	
			for ( final Tuple2< Tuple2<Integer, Integer>, Integer > tuple : rddMatches.collect() )
			{
				matchesCount.put( tuple._1()._1(), matchesCount.get( tuple._1()._1() ) + tuple._2() );
				matchesCount.put( tuple._1()._2(), matchesCount.get( tuple._1()._2() ) + tuple._2()  );
			}

			numUnconnected = 0;
			long numMatches = 0;

			for ( final int key : matchesCount.keySet() )
			{
				if ( matchesCount.get( key ) == 0 )
				{
					numUnconnected++;
					System.out.print( key + ", " );
				}
				else
				{
					numMatches += matchesCount.get( key );
				}
			}

			System.out.println( "\nunconnected: " + numUnconnected + " (" + numMatches + "), " + " t=" + threshold );

			if ( numUnconnected < bestUnconnected )
			{
				bestUnconnected = numUnconnected;
				bestThr = threshold;
				System.out.println( "new best." );
			}

			if ( numUnconnected > 0 )
				thr /= 1.5;

			// one final run, none before was good
			if ( thr <= 0.001 || lastUnconnected < numUnconnected )
			{
				System.out.println( "repeating best thr=" + bestThr );
				thr = bestThr;
			}
			else
				lastUnconnected = numUnconnected;

		} while ( !extraRun && numUnconnected > 0 );

		if ( numUnconnected > 0 )
		{
			PrintWriter out = TextFileAccess.openFileWrite( id + "_" + channel +"_" + cam + "." + numUnconnected + ".txt" );
			out.close();
		}
	}

	public static void showStack( final List<Slice> stack, final List<Integer> slices, final int width, final int height ) throws IOException
	{
		ImageStack imgstack = new ImageStack(width, height);

		int j = 0;

		for ( final int i : slices )
		{
			System.out.println( new Date( System.currentTimeMillis() ) + ": Loading slice " + i );
			final Slice sliceInfo = stack.get(i);
	
			try (final TiffReader reader = new TiffReader())
			{
				RandomAccessibleInterval slice = null;
				
				try
				{
					slice = (RandomAccessibleInterval)Opener.openSlice(
									reader,
									sliceInfo.path,
									sliceInfo.index,
									width,
									height);

					if (sliceInfo.affine != null && !sliceInfo.affineTransform().isIdentity() )
					{
						slice = Views.interval( Views.raster( RealViews.affineReal( Views.interpolate(Views.extendZero( slice ), new NLinearInterpolatorFactory()), sliceInfo.affineTransform() ) ), slice );
						System.out.println( "non identify affine transform (index=" + j + "):" + sliceInfo.path + ", " + sliceInfo.index + ": " +  sliceInfo.affineTransform() );
					}
				}
				catch ( FormatException e )
				{
					System.out.println( "e:" + e );
					System.exit( 0 );
					//slice = ArrayImgs.unsignedShorts( width, height );
				}
				imgstack.addSlice( ImageJFunctions.wrapUnsignedShort( slice, "z=" + i ).getProcessor() );
			}
			++j;
		}

		new ImagePlus( "stack", imgstack ).show();
	}

	private static void show( final ArrayList<Slice> stack, final int width, final int height, final ArrayList<PointMatch> matches, final int sliceIndex0, final int sliceIndex1 ) throws IOException, FormatException
	{
		final TiffReader reader = new TiffReader();
		Slice sliceInfo = stack.get( sliceIndex0 );

		final RandomAccessibleInterval slice0 =
				(RandomAccessibleInterval)Opener.openSlice(
						reader,
						sliceInfo.path,
						sliceInfo.index,
						width,
						height);

		sliceInfo = stack.get( sliceIndex1 );

		final RandomAccessibleInterval slice1 =
				(RandomAccessibleInterval)Opener.openSlice(
						reader,
						sliceInfo.path,
						sliceInfo.index,
						width,
						height);

		ImagePlus imp1 = ImageJFunctions.show(
				Converters.convert(
						(RandomAccessibleInterval<RealType<?>>)slice0,
						(a, b) -> b.setReal(a.getRealFloat()),
					new FloatType())
				);
		imp1.setRoi( mpicbg.ij.util.Util.pointsToPointRoi(
						matches.stream().map( pm -> pm.getP1() ).collect( Collectors.toList() ) ) );
		imp1.resetDisplayRange();
		imp1.setTitle( "s=" + sliceIndex0);

		ImagePlus imp2 = ImageJFunctions.show(
				Converters.convert(
						(RandomAccessibleInterval<RealType<?>>)slice1,
						(a, b) -> b.setReal(a.getRealFloat()),
					new FloatType())
				);
		imp2.setRoi( mpicbg.ij.util.Util.pointsToPointRoi(
						matches.stream().map( pm -> pm.getP2() ).collect( Collectors.toList() ) ) );
		imp2.resetDisplayRange();
		imp2.setTitle( "s=" + sliceIndex1);
		SimpleMultiThreading.threadHaltUnClean();
	}

	@Override
	public Void call() throws IOException, FormatException {
		// System property for locally calling spark has to be set, e.g. -Dspark.master=local[4]
		final String sparkLocal = System.getProperty( "spark.master" );

		// only do that if the system property is not set
		if ( sparkLocal == null || sparkLocal.trim().length() == 0 )
		{
			System.out.println( "Spark System property not set: " + sparkLocal );
			System.setProperty( "spark.master", "local[" + Math.max( 1, Runtime.getRuntime().availableProcessors() ) + "]" );
		}

		System.out.println( "Spark System property is: " + System.getProperty( "spark.master" ) );

		final SparkConf conf = new SparkConf().setAppName("SparkExtractGeometricPointDescriptorMatches");

		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		extractGeometricDescriptorMatches(sc, n5Path, id, channel, cam, distance, redundancy, minIntensity, maxIntensity, maxEpsilon, minNumInliers, numIterations);

		sc.close();

		System.out.println( new Date( System.currentTimeMillis() ) + ": Done.");
		//SimpleMultiThreading.threadHaltUnClean();


		return null;
	}

	public static final void main(final String... args) throws IOException {

		System.exit(new CommandLine(new SparkExtractGeometricPointDescriptorMatches()).execute(args));
	}
}
