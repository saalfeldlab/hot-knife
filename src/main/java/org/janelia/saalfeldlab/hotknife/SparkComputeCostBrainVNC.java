package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.tools.boundingbox.TransformedBoxSelectionDialog.Result;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import ij.ImageJ;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class SparkComputeCostBrainVNC  implements Callable<Void>
{
	@Option(names = {"-i", "--n5Path"}, required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"-d", "--n5Dataset"}, required = true, description = "N5 input group for raw data, e.g. /raw")
	private String n5Dataset = null;

	@Option(names = {"-ci", "--costN5Path"}, required = true, description = "N5 input group for raw, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String costN5Path = null;

	@Option(names = {"-cd", "--costN5Dataset"}, required = true, description = "N5 input group for raw, e.g. /raw")
	private String costN5Dataset = null;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		//CommandLine.call(new SparkComputeCostBrainVNC(), args);
		new CommandLine( new SparkComputeCostBrainVNC() ).execute( args );
	}

	/**
	 * Code snippet used to identify the bounding box in the brain volume
	 *
	 * @param n5
	 * @param rawGroup
	 * @param crop
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	public static Interval defineCrop( final String n5Path, final String rawGroup, final Interval crop ) throws IOException
	{
		final N5Reader n5 = new N5FSReader(n5Path);

		final int numScales = n5.list(rawGroup).length;
		final double[][] scales = new double[numScales][];
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = new RandomAccessibleInterval[numScales];

		for (int s = 0; s < numScales; ++s) {
			final String mipmapName = rawGroup + "/s" + s;
			mipmaps[s] = (RandomAccessibleInterval<UnsignedByteType>)N5Utils.openVolatile(n5, mipmapName);
			final double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
			scales[s] = (scale == null) ? new double[] {1, 1, 1} : scale;
		}

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
				new RandomAccessibleIntervalMipmapSource<>(
						mipmaps,
						new UnsignedByteType(),
						scales,
						new FinalVoxelDimensions("px", 1.0, 1.0, 1.0),
						new AffineTransform3D(),
						rawGroup);

		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.min(12, Math.max(1, numProc - 2)));

		final BdvOptions options =
				BdvOptions.options()
				.screenScales(new double[] {0.5})
				.numRenderingThreads(Runtime.getRuntime().availableProcessors());

		final BdvStackSource<?> bdv = Show.mipmapSource(mipmapSource.asVolatile(queue), null, options);

		final Result box = BdvFunctions.selectBox(
				bdv,
				new AffineTransform3D(),
				crop == null ? new FinalInterval( mipmaps[ 0 ] ) : new FinalInterval( crop ),
				new FinalInterval( mipmaps[ 0 ] ) );

		System.out.println( Util.printInterval( box.getInterval() ) );
		// [47204, 46557, 40906] -> [55779, 59038, 55164], dimensions (8576, 12482, 14259)

		n5.close();

		return box.getInterval();
	}

	public static void lowResCost(
			final String n5Path,
			final String n5Dataset,
			final Interval crop ) throws Exception
	{
		final N5Reader n5 = new N5FSReader(n5Path);
		final int numScales = n5.list(n5Dataset).length;
		final double[][] scales = new double[numScales][];
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = new RandomAccessibleInterval[numScales];
		for (int s = 0; s < numScales; ++s) {

			final String mipmapName = n5Dataset + "/s" + s;
			mipmaps[s] = (RandomAccessibleInterval<UnsignedByteType>)N5Utils.open(n5, mipmapName);
			double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
			scales[s] = (scale == null) ? new double[] {1, 1, 1} : scale;

			System.out.println( "scale: "+ Util.printCoordinates( scales[s] ) );
			System.out.println( "dimensions: "+ Util.printInterval( mipmaps[ s ]));

			if ( crop != null )
			{
				final AffineTransform3D mipmapTransform = new AffineTransform3D();
				mipmapTransform.set(
						scales[s][ 0 ], 0, 0, 0.5 * ( scales[s][ 0 ] - 1 ),
						0, scales[s][ 1 ], 0, 0.5 * ( scales[s][ 1 ] - 1 ),
						0, 0, scales[s][ 2 ], 0.5 * ( scales[s][ 2 ] - 1 ) );
				//mipmapTransform.preConcatenate(sourceTransform);
				//mipmapTransforms[ s ] = mipmapTransform;

				double[] min = new double[] {crop.min(0), crop.min(1), crop.min(2)};
				double[] max = new double[] {crop.max(0), crop.max(1), crop.max(2)};

				mipmapTransform.applyInverse(min, min);
				mipmapTransform.applyInverse(max, max);

				System.out.println( "mipmap transform: "+ mipmapTransform );
				System.out.println( "updated interval: "+ Util.printCoordinates( min ) + " >>> " + Util.printCoordinates( max ) );

				if ( scales[s][ 0 ] == 8 )
				{
					final RealInterval bb = new FinalRealInterval(min, max);
					final RandomAccessibleInterval<UnsignedByteType> cropped =
							Views.interval( mipmaps[ s ], Intervals.smallestContainingInterval( bb ) );

					//new ImageJ();
					//ImageJFunctions.show( cropped );

					computeCost( Views.rotate( cropped, 1, 0 ) );
				}
			}
			System.out.println();
		}

	}

	public static void computeCost( final RandomAccessibleInterval<UnsignedByteType> cropped ) throws Exception
	{
		//new ImageJ();
		//ImageJFunctions.show( cropped );

		//ExecutorService executorService =  Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 2);
		ExecutorService executorService =  Executors.newFixedThreadPool(1 );
		//ExecutorService executorService =  Executors.newCachedThreadPool();

		final boolean filter = false;
		final boolean gauss = true;

		final long[] zcorrSize = new long[3];
		cropped.dimensions( zcorrSize );
		final int[] costSteps = new int[]{1,1,1};
		final Long[] gridCoord = new Long[] { 0l, 0l };
		final int costAxis=2;

		final int bandSize=50;
		final float maxSlope=0.04f;
		final float minSlope = 0;
		final float slopeCorrBandFactor=5.5f;
		final int slopeCorrXRange=20;
		final int minGradient = 20;
		final int startThresh = 50;
		final int kernelSize = 5;

		SparkComputeCost.processColumnAlongAxis(
				cropped,
				new FinalInterval( cropped ), 
				filter,
				gauss,
				//costBlockSize,
				//zcorrBlockSize,
				zcorrSize,
				costSteps,
				costAxis,
				gridCoord,
				executorService,
				bandSize,
				minGradient,
				slopeCorrXRange,
				slopeCorrBandFactor,
				maxSlope,
				minSlope,
				startThresh,
				kernelSize);
	}

	private static Pair< RandomAccessibleInterval<UnsignedByteType>, int[] > openCropFullBrain(
			final String n5Path,
			final String n5Dataset,
			final long[] minInterval,
			final long[] maxInterval ) throws IOException
	{
		final N5Reader n5 = new N5FSReader(n5Path);
		final String fullRes = n5Dataset + "/s0";

		final int[] zcorrBlockSize = n5.getAttribute(fullRes, "blockSize", int[].class);
		final RandomAccessibleInterval<UnsignedByteType> fullBrain = (RandomAccessibleInterval<UnsignedByteType>)N5Utils.open(n5, fullRes);
		double[] scale = n5.getAttribute(fullRes, "downsamplingFactors", double[].class);
		scale = (scale == null) ? new double[] {1, 1, 1} : scale;

		//System.out.println( "scale: "+ Util.printCoordinates( scale ) );
		//System.out.println( "dimensions: "+ Util.printInterval( fullBrain ) );

		final RandomAccessibleInterval<UnsignedByteType> cropped =
				Views.rotate(
						Views.interval( fullBrain, minInterval, maxInterval ),
						1,
						0 );

		return new ValuePair<>(cropped, zcorrBlockSize);
	}

	public static void processCostSpark(
			final JavaSparkContext sparkContext,
			final String n5Path,
			final String n5Dataset,
			final Interval cropInterval,
			final String costN5Path,
			final String costN5Dataset ) throws IOException
	{
		// for serialization
		final long[] min = new long[ cropInterval.numDimensions() ];
		final long[] max = new long[ cropInterval.numDimensions() ];

		cropInterval.min( min );
		cropInterval.max( max );

		System.out.println( "crop interval: " + Util.printInterval( cropInterval ) );

		final Pair< RandomAccessibleInterval<UnsignedByteType>, int[] > data = openCropFullBrain( n5Path, n5Dataset, min, max );
		final RandomAccessibleInterval<UnsignedByteType> cropped = data.getA();
		final int[] zcorrBlockSize = data.getB();

		System.out.println( "zcorrBlockSize: " + Util.printCoordinates( zcorrBlockSize ) );

		final long[] zcorrSize = new long[3];
		cropped.dimensions( zcorrSize );

		final int[] costBlockSize = new int[]{
				zcorrBlockSize[1],
				zcorrBlockSize[0], // above we switch x and y
				zcorrBlockSize[2]
		};

		final int[] costSteps = new int[]{6,1,6};
		final int costAxis=2;

		long[] costSize = new long[]{ zcorrSize[0] / costSteps[0], zcorrSize[1] / costSteps[1], zcorrSize[2] / costSteps[2] };

		final N5Writer n5w = new N5FSWriter(costN5Path);

		n5w.createDataset(costN5Dataset, costSize, costBlockSize, DataType.UINT8, new GzipCompression());
		n5w.setAttribute(costN5Dataset, "downsamplingFactors", costSteps);

		n5w.close();

		final ArrayList<Long[]> gridCoords = new ArrayList<>();

		final int gridXSize = (int)Math.ceil(costSize[0] / (float)costBlockSize[0]);
		final int gridZSize = (int)Math.ceil(costSize[2] / (float)costBlockSize[2]);

		System.out.println( "gridXSize: " + gridXSize );
		System.out.println( "gridZSize: " + gridZSize );

		for (long x = 12; x <= 12; x++) {
			for (long z = 1; z <= 1; z++) {
		//for (long x = 0; x < gridXSize; x++) {
		//	for (long z = 0; z < gridZSize; z++) {

				if ( x == 35 ) System.out.println( "z: " + z + ": " + SparkComputeCost.getZcorrInterval(x, z, zcorrSize, zcorrBlockSize, costSteps).min( 2 ));
				gridCoords.add(new Long[]{x, z});
			}
			System.out.println( "x: " + x + ": " + SparkComputeCost.getZcorrInterval(x, 0l, zcorrSize, zcorrBlockSize, costSteps).min( 0 ) );
		}

		System.out.println("Processing " + gridCoords.size() + " grid pairs. " + gridXSize + " by " + gridZSize);
		//System.exit( 0 );

		// Grids are w.r.t cost blocks
		final JavaRDD<Long[]> rddSlices = sparkContext.parallelize(gridCoords);

		final boolean filter = false;
		final boolean gauss = true;

		final int bandSize=50;
		final float maxSlope=0.04f;
		final float minSlope = 0;
		final float slopeCorrBandFactor=5.5f;
		final int slopeCorrXRange=20;
		final int minGradient = 20;
		final int startThresh = 50;
		final int kernelSize = 5;

		rddSlices.foreachPartition( gridCoordPartition -> {

			final ExecutorService executorService =  Executors.newFixedThreadPool(1 );

			gridCoordPartition.forEachRemaining(gridCoord -> {

				try {
					// load, crop, rotate full brain
					// zero-min is needed because the spark blocks assume that
					final RandomAccessibleInterval<UnsignedByteType> brainVNCvolume = Views.zeroMin( openCropFullBrain( n5Path, n5Dataset, min, max ).getA() );

					// cut out the relevant block
					final Interval zcorrInterval = SparkComputeCost.getZcorrInterval(gridCoord[0], gridCoord[1], zcorrSize, zcorrBlockSize, costSteps);
					final RandomAccessibleInterval<UnsignedByteType> zcorr = Views.interval( brainVNCvolume, zcorrInterval );

					System.out.println("Processing inteval " + Util.printInterval( zcorrInterval ) );

					// run cost
					final RandomAccessibleInterval<UnsignedByteType> cost = SparkComputeCost.processColumnAlongAxis(
							zcorr,
							new FinalInterval( zcorr ), 
							filter,
							gauss,
							//costBlockSize,
							//zcorrBlockSize,
							zcorrSize,
							costSteps,
							costAxis,
							gridCoord,
							executorService,
							bandSize,
							minGradient,
							slopeCorrXRange,
							slopeCorrBandFactor,
							maxSlope,
							minSlope,
							startThresh,
							kernelSize);

					ImageJFunctions.show( cost );
					SimpleMultiThreading.threadHaltUnClean();

					// save cost
					System.out.println("Writing blocks");

					final N5Writer n5costWriter = new N5FSWriter(costN5Path);

					// Now loop over blocks and write
					for( int yGrid = 0; yGrid <= Math.ceil(zcorrSize[1] / zcorrBlockSize[1]); yGrid++ ) {
						long[] gridOffset = new long[]{gridCoord[0], yGrid, gridCoord[1]};
						RandomAccessibleInterval<UnsignedByteType> block = Views.interval(
								Views.extendZero( cost ),
								new FinalInterval(
										new long[]{0, yGrid * zcorrBlockSize[1], 0},
										new long[]{cost.dimension(0) - 1, (yGrid + 1) * zcorrBlockSize[1] - 1, cost.dimension(2) - 1}));
						N5Utils.saveBlock(
								block,
								n5costWriter,
								costN5Dataset,
								gridOffset);
					}

					n5costWriter.close();
					/*
				    processColumn(
						  n5Path, costN5Path, zcorrDataset, costDataset, filter, gauss, costBlockSize, zcorrBlockSize, zcorrSize, costSteps, axisMode, gridCoord, executorService,
						  options.bandSize, options.minGradient, options.slopeCorrXRange, options.slopeCorrBandFactor, options.maxSlope,
						  options.minSlope, options.startThresh, options.kernelSize);
					*/
				} catch (Exception e) {
				    e.printStackTrace(); // TODO: is there a reason we are swallowing exceptions?
				}
				
			});

			executorService.shutdown();

			});

	}

	@Override
	public final Void call() throws Exception
	{
		// crop interval (was manually specified using method 'defineCrop')
		final Interval crop = new FinalInterval( new long[] {47204, 46557, 42756+5000}, new long[] {55779-4000, 59038, 53664-5000} );
		//final Interval crop = new FinalInterval( new long[] {47204, 46557, 42756}, new long[] {55779, 59038, 53664} );

		/*
		 * show crop
		 */
		// defineCrop( n5Path, n5Dataset, crop );

		/*
		 * run cost on low-res version
		 */
		// lowResCost(costN5Path, costN5Dataset, crop);

		/*
		 * run full res on spark
		 */
		final SparkConf conf = new SparkConf().setAppName("SparkComputeCostBrainVNC");
		conf.set("spark.driver.bindAddress", "127.0.0.1");
		final JavaSparkContext sc = new JavaSparkContext(conf);

		processCostSpark(sc, n5Path, n5Dataset, crop, costN5Path, costN5Dataset );



		return null;
	}
}
