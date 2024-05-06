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
package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.imglib2.IterableInterval;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.cost.DagmarCost;
import org.janelia.saalfeldlab.hotknife.cost.PreFilter;
import org.janelia.saalfeldlab.hotknife.util.N5PathSupplier;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;

/**
 * Export a render stack to N5.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkComputeCostMultiSem {

	final static public String ownerFormat = "%s/owner/%s";
	final static public String stackListFormat = ownerFormat + "/stacks";
	final static public String stackFormat = ownerFormat + "/project/%s/stack/%s";
	final static public String stackBoundsFormat = stackFormat  + "/bounds";
	final static public String boundingBoxFormat = stackFormat + "/z/%d/box/%d,%d,%d,%d,%f";
	final static public String renderParametersFormat = boundingBoxFormat + "/render-parameters";

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--inputN5Path", required = true, usage = "input N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "--outputN5Path", required = true, usage = "output N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String outputN5Path = null;

		@Option(name = "--inputN5Group", required = true, usage = "N5 dataset, e.g. /zcorr/Sec26")
		private String inputDatasetName = null;

		@Option(name = "--costN5Group", required = true, usage = "N5 dataset, e.g. /cost/Sec26")
		private String costDatasetName = null;

		@Option(name = "--maskN5Group", required = false, usage = "N5 dataset, e.g. /mask/Sec26")
		private String maskDatasetName = null;

		@Option(name = "--firstStepScaleNumber",
				usage = "scale number for first cost step, e.g. 1 for s1")
		private int firstStepScaleNumber = 1;

		public String getCostDatasetName(final int index) {
			return costDatasetName + "/s" + (firstStepScaleNumber + index);
		}

		@Option(name = "--costSteps",
				aliases = { "-f", "--factors" },
				usage = "Step sizes for computing cost, e.g. 6,6,1. " +
						"Specify multiple values for downsampling where each factor builds on the last.")
		private String[] costStepsStrings = {
				"6,6,1", "2,2,1", "2,2,1", "2,2,1", "2,2,1", "2,2,1", "2,2,1", "2,2,1" //, "1,4,1", "1,4,1", "1,4,1" -- no downsampling in Z ever, too small
		};

		private int[][] costSteps;

		@Option(name = "--outOfBoundsValue", usage = "value to use for out-of-bounds pixels (if not given, estimate from data)")
		private Integer outOfBoundsValue = null;

		@Option(name = "--median", required = false, usage = "uses median (r=3 in z) before cost computation")
		private boolean median = false;

		@Option(name = "--smoothCost", required = false, usage = "smoothes cost in z (s=1.0)")
		private boolean smoothCost = false;

		@Option(name = "--surfaceN5Output", usage = "N5 output group for surface heighfields, e.g. /heightfields/Sec39/v1_acquire_trimmed_sp1, omit to skip surface fit")
		private String surfaceN5Output = null;

		@Option(name = "--surfaceFirstScale", usage = "initial scale index, e.g. 8")
		private int surfaceFirstScale = 8;

		@Option(name = "--surfaceLastScale", usage = "terminal scale index, e.g. 1")
		private int surfaceLastScale = 1;

		@Option(name = "--surfaceMaxDeltaZ", usage = "maximum slope of the surface in original pixels, e.g. 0.25")
		private double surfaceMaxDeltaZ = 0.2;

		@Option(name = "--surfaceInitMaxDeltaZ", usage = "maximum slope of the surface in original pixels in the first scale level (initialization), e.g. 0.3")
		private double surfaceInitMaxDeltaZ = .2;

		@Option(name = "--finalMaxDeltaZ", usage = "maximum slope of the surface in original pixels in the last scale level (s1 usually), e.g. 0.25")
		private double finalMaxDeltaZ = 0.25;

		@Option(name = "--surfaceMinDistance", usage = "minimum distance between the both surfaces, e.g. 15")
		private double surfaceMinDistance = 15;

		@Option(name = "--surfaceMaxDistance", usage = "maximum distance between the both surfaces, e.g. 30")
		private double surfaceMaxDistance = 30;

		@Option(name = "--surfaceBlockSize", usage = "surface block size in pixels, e.g. 128,128")
		private String surfaceBlockSizeString = "128,128";

		private long[] getSurfaceBlockSize() {
			return parseCSLongArray(surfaceBlockSizeString);
		}

		@Option(name = "--localSparkBindAddress", usage = "specify Spark bind address as localhost")
		private boolean localSparkBindAddress = false;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);

			try {
				parser.parseArgument(args);
				costSteps = new int[costStepsStrings.length][3];

				for (int i = 0; i < costStepsStrings.length; i++) {
					parseCSIntArray(costStepsStrings[i], costSteps[i]);
				}

				parsedSuccessfully = true;
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		public int[] getCostSteps(final int index) {
			return costSteps[index];
		}
	}

	public static void computeCost(
			final JavaSparkContext sparkContext,
			final Options options) throws IOException {

		String n5Path = options.n5Path;
		String costN5Path = options.outputN5Path;
		String zcorrDataset = options.inputDatasetName;
		String costDataset = options.getCostDatasetName(0);
		String maskDataset = options.maskDatasetName;
		int[] costSteps = options.getCostSteps(0);

		System.out.println("Computing cost on: " + n5Path + " " + zcorrDataset );
		System.out.println("Cost output: " + costN5Path + " " + costDataset );
		System.out.println("Cost steps: " + costSteps[0] + ", " + costSteps[1] + ", " + costSteps[2] );
		System.out.println("median Z: " + options.median );
		System.out.println("smooth cost Z: " + options.smoothCost );

		final long[] surfaceBlockSize = options.getSurfaceBlockSize();
		System.out.println("surfaceBlockSize: " + Util.printCoordinates(surfaceBlockSize) );
		
		final N5Reader n5 = new N5FSReader(n5Path);
		final N5Writer n5w = new N5FSWriter(costN5Path);

		final int outOfBoundsValue;
		if (options.outOfBoundsValue == null) {
			final String downsampledDataset = n5.groupPath(options.inputDatasetName, "s5");
			final IterableInterval<UnsignedByteType> lastLayer = getLastLayer(n5, downsampledDataset);
			outOfBoundsValue = median(lastLayer);
			System.out.println("Out of bounds value automatically computed to be " + outOfBoundsValue);
		} else {
			outOfBoundsValue = options.outOfBoundsValue;
		}

		int[] zcorrBlockSize = n5.getAttribute(zcorrDataset, "blockSize", int[].class);
		long[] zcorrSize = n5.getAttribute(zcorrDataset, "dimensions", long[].class);

//		int[] costBlockSize = new int[]{
//				zcorrBlockSize[0] / costSteps[0],
//				zcorrBlockSize[1] / costSteps[1],
//				zcorrBlockSize[2] / costSteps[2]
//		};

		int[] costBlockSize = new int[]{
				zcorrBlockSize[0],
				zcorrBlockSize[1],
				zcorrBlockSize[2]
		};

		final long[] costSize = new long[]{ zcorrSize[0] / costSteps[0], zcorrSize[1] / costSteps[1], zcorrSize[2] / costSteps[2] };

		System.out.println( "zcorrBlockSize: " + Util.printCoordinates( zcorrBlockSize ) );
		System.out.println( "zcorrSize: " + Util.printCoordinates( zcorrSize ) );
		System.out.println( "costSteps: " + Util.printCoordinates( costSteps ) );
		System.out.println( "costSize: " + Util.printCoordinates( costSize ) );

		n5w.createDataset(
        		costDataset,
        		costSize,
        		costBlockSize,
        		DataType.UINT8,
        		new GzipCompression());
		n5w.setAttribute(costDataset, "downsamplingFactors", costSteps);
		final ArrayList<Long[]> gridCoords = new ArrayList<>();

		// for multisem grid along xy
		int gridXSize = (int)Math.ceil(costSize[0] / (float)costBlockSize[0]);
		int gridYSize = (int)Math.ceil(costSize[1] / (float)costBlockSize[1]);

		//new ImageJ();
		//for (long x = 53; x <= 53; x++) {
		//	for (long y = 34; y <= 34; y++) {

		for (long x = 0; x < gridXSize; x++) {
			//System.out.println( "x: " + x + ": " + getZcorrInterval(x, 0l, zcorrSize, zcorrBlockSize, costSteps).min( 0 ) );
			for (long y = 0; y < gridYSize; y++) {
				//if ( x == 53 ) System.out.println( "y: " + y + ": " + getZcorrInterval(x, y, zcorrSize, zcorrBlockSize, costSteps).min( 1 ));
				gridCoords.add(new Long[]{x, y});
			}
		}

		System.out.println("Processing " + gridCoords.size() + " grid pairs. " + gridXSize + " by " + gridYSize);
		//System.exit(0);

		// Grids are w.r.t cost blocks
		final JavaRDD<Long[]> rddSlices = sparkContext.parallelize(gridCoords);

		// foreach version

		// rddSlices.foreach(gridCoord -> {
		// //gridCoords.forEach(gridCoord -> {

		// 	//ExecutorService executorService =  Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 2);
		// 	ExecutorService executorService =  Executors.newFixedThreadPool(1);// runs out of threads otherwise
		// 	//ExecutorService executorService =  Executors.newCachedThreadPool();

		// 	try {
		// 	    processColumn(
		// 	    		n5Path, costN5Path, zcorrDataset, costDataset, costBlockSize, zcorrBlockSize, zcorrSize, costSteps, gridCoord, executorService,
		// 				options.getBandSize(), options.getMinGradient(), options.getSlopeCorrXRange(), options.getSlopeCorrBandFactor(), options.getMaxSlope(),
		// 				options.getMinSlope(), options.getStartThresh(), options.getKernelSize());
		// 	} catch (Exception e)
		// 	    {
		// 		e.printStackTrace();
		// 	    }

		// 	executorService.shutdown();
		// });

		final boolean filter = options.median;
		final boolean gauss = options.smoothCost;

		rddSlices.foreachPartition( gridCoordPartition -> {
			//gridCoords.forEach(gridCoord -> {

			//ExecutorService executorService =  Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 2);
			ExecutorService executorService =  Executors.newFixedThreadPool(1 );
			//ExecutorService executorService =  Executors.newCachedThreadPool();

			gridCoordPartition.forEachRemaining(gridCoord -> processColumn(n5Path, costN5Path, zcorrDataset, costDataset, maskDataset, filter, gauss, costBlockSize, zcorrBlockSize, zcorrSize, costSteps, gridCoord, outOfBoundsValue, executorService));

			executorService.shutdown();

		    });

		// done with cost

		final N5PathSupplier n5PathSupplier = new N5PathSupplier(costN5Path);
		for (int i = 1; i < options.costStepsStrings.length; i++) {
			N5DownsamplerSpark.downsample(
					sparkContext,
					n5PathSupplier,
					options.getCostDatasetName(i - 1),
					options.getCostDatasetName(i),
					options.getCostSteps(i),
					costBlockSize
			);
		}

		if (options.surfaceN5Output != null) {
			SparkSurfaceFit sparkSurfaceFit = new SparkSurfaceFit(options.outputN5Path,
																  options.outputN5Path,
																  options.costDatasetName,
																  options.inputDatasetName,
																  options.surfaceN5Output,
																  options.surfaceFirstScale,
																  options.surfaceLastScale,
																  options.surfaceMaxDeltaZ,
																  options.surfaceInitMaxDeltaZ,
																  options.finalMaxDeltaZ, // finaldeltaZ
																  options.surfaceMinDistance,
																  options.surfaceMaxDistance,
																  true, // no need to permute with multi-sem
																  false);
			sparkSurfaceFit.callWithSparkContext(sparkContext,
												 options.getSurfaceBlockSize());
		}
	}

	private static IterableInterval<UnsignedByteType> getLastLayer(final N5Reader n5Reader, final String dataset) {
		final Img<UnsignedByteType> data = N5Utils.open(n5Reader, dataset);
		final long lastLayerIndex = data.dimension(2) - 1;
		return Views.iterable(Views.hyperSlice(data, 2, lastLayerIndex));
	}

	public static int median(final IterableInterval<UnsignedByteType> pixels2D) {
		int[] intensities = new int[(int) pixels2D.dimension(0) * (int) pixels2D.dimension(1)];
		int count = 0;
		for (final UnsignedByteType pixel : pixels2D) {
			final int value = pixel.get();
			if (value > 0) {
				// threshold to avoid background pixels
				intensities[count++] = value;
			}
		}

		intensities = Arrays.copyOf(intensities, count);
		Arrays.sort(intensities);
		final int median;
		if (count % 2 == 1) {
			median = intensities[count / 2];
		} else {
			median = (intensities[count / 2] + intensities[count / 2 - 1]) / 2;
		}

		return median;
	}

	public static void processColumn(
			String n5Path,
			String costN5Path,
			String zcorrDataset,
			String costDataset,
			String maskDataset,
			final boolean filter,
			final boolean gauss,
			int[] costBlockSize,
			int[] zcorrBlockSize,
			long[] zcorrSize,
			int[] costSteps,
			Long[] gridCoord,
			int outOfBoundsValue,
			ExecutorService executorService )
	{
		System.out.println("Processing grid coord: " + gridCoord[0] + " " + gridCoord[1] );

		RandomAccessibleInterval<UnsignedByteType> cost =
				processColumnAlongAxis(n5Path, zcorrDataset, maskDataset, filter, gauss, zcorrBlockSize, zcorrSize, costSteps, gridCoord, outOfBoundsValue, executorService);

		//ImageJFunctions.show( cost );
		//SimpleMultiThreading.threadHaltUnClean();

		System.out.println( "cost: " + Util.printInterval( cost ));
		
		System.out.println("Writing blocks");

        // TODO: wrong dimensions
        N5Writer n5w = new N5FSWriter(costN5Path);

        // Now loop over blocks and write (for multisem, usually just one block in z)
        for( int zGrid = 0; zGrid <= Math.ceil(zcorrSize[2] / zcorrBlockSize[2]); zGrid++ )
        {
            final long[] gridOffset = new long[]{gridCoord[0], gridCoord[1], zGrid }; //TODO: is this in original or cost steps?

            System.out.println( "gridOffset: " + Util.printCoordinates( gridOffset ));

            RandomAccessibleInterval<UnsignedByteType> block = Views.interval(
                    Views.extendZero( cost ),
                    new FinalInterval(
                            new long[]{0, 0, zGrid * zcorrBlockSize[2]},
                            new long[]{cost.dimension(0) - 1, cost.dimension(1) - 1,(zGrid + 1) * zcorrBlockSize[2] - 1 }));

            System.out.println( "block: " + Util.printInterval( block ));

            N5Utils.saveBlock(
                    block,
                    n5w,
                    costDataset,
                    gridOffset);
        }

        //SimpleMultiThreading.threadHaltUnClean();
	}

	private static RandomAccessibleInterval<UnsignedByteType> mergeCosts(RandomAccessibleInterval<UnsignedByteType> topCost, RandomAccessibleInterval<UnsignedByteType> botCost) {

        Img<UnsignedByteType> outCost = ArrayImgs.unsignedBytes(topCost.dimension(0), topCost.dimension(1));

        RandomAccess<UnsignedByteType> topAccess = topCost.randomAccess();
        RandomAccess<UnsignedByteType> botAccess = botCost.randomAccess();

        Cursor<UnsignedByteType> outCur = outCost.localizingCursor();
        while (outCur.hasNext()) {
            outCur.fwd();
            topAccess.setPosition(outCur);
            botAccess.setPosition(outCur);

            outCur.get().set(Math.min(topAccess.get().get(), botAccess.get().get()));
        }

        return outCost;
	}

	public static RandomAccessibleInterval<UnsignedByteType> processColumnAlongAxis(
			String n5Path,
			String zcorrDataset,
			String maskDataset,
			final boolean filter,
			final boolean gauss,
			int[] zcorrBlockSize,
			long[] zcorrSize,
			int[] costSteps,
			Long[] gridCoord,
			int outOfBoundsValue,
			ExecutorService executorService ) {

		final RandomAccessibleInterval<UnsignedByteType> zcorrRaw;
		final RandomAccessibleInterval<UnsignedByteType> maskRaw;
		final RandomAccessible<UnsignedByteType> maskExtended;

        zcorrRaw = N5Utils.open(new N5FSReader(n5Path), zcorrDataset);

        if ( maskDataset != null )
        {
            maskRaw = N5Utils.open(new N5FSReader(n5Path), maskDataset);

            if ( !Intervals.equals(zcorrRaw, maskRaw) )
                throw new RuntimeException( "zCorrRaw interval [" + Util.printInterval(zcorrRaw) + "] and mask interval [" + Util.printInterval(maskRaw) + "] are not the same, quitting." );

            maskExtended = Views.extendZero( maskRaw );
        }
        else
        {
            maskRaw = null;
            maskExtended = null;
        }

        // The cost function is implemented to be processed along dimension = 2, costAxis should be 0 or 2 with the current image data
		// zcorr = Views.permute(zcorr, costAxis, 2);

		final RandomAccessible<UnsignedByteType> zcorrExtended = Views.extendValue(zcorrRaw, outOfBoundsValue);
		final Interval zcorrInterval = getZcorrInterval(gridCoord[0], gridCoord[1], zcorrSize, zcorrBlockSize, costSteps);
		//final RandomAccessibleInterval<UnsignedByteType> zcorr = Views.interval( zcorrExtended, zcorrInterval );

		//ImageJFunctions.show( Views.interval( zcorrExtended, zcorrInterval ) ).setTitle( "input" );
		//if ( maskRaw != null)
		//	ImageJFunctions.show( Views.interval( maskRaw, zcorrInterval ) ).setTitle( "mask" );
		//SimpleMultiThreading.threadHaltUnClean();

		// compute derivative in z and keep only negative values
		// we set the outofbounds to "outsideValue" above, which is about the resin color in case the sample touches the image boundary
		// TODO: the derivative is offset by 0.5 pixels on the bottom, and by 0.5 px on the top towards the other direction

		//final RandomAccessibleInterval<UnsignedByteType> zcorrSubsampled = Views.subsample( zcorr, costSteps[ 0 ], costSteps[ 1 ], costSteps[ 2 ] );

		// by default, there is data everywhere (mask set to 255)
		// we create a projected mask, if at any pixel in z there is no data, the mask will say there is no data
		final RandomAccessibleInterval<UnsignedByteType> mask2d =
				Views.translate(
						ArrayImgs.unsignedBytes(
								new long[] { zcorrInterval.dimension( 0 ), zcorrInterval.dimension( 1 ) } ),
								new long[] { zcorrInterval.min( 0 ), zcorrInterval.min( 1 ) } );

		for ( final UnsignedByteType v : Views.iterable( mask2d ) )
			v.set( 255 );

		if ( maskRaw != null )
		{
			final Cursor<UnsignedByteType> m = Views.iterable( mask2d ).localizingCursor();
			final RandomAccess<UnsignedByteType> maskData = maskExtended.randomAccess();

			while ( m.hasNext() )
			{
				final UnsignedByteType v = m.next();
				maskData.setPosition( m.getIntPosition( 0 ), 0 );
				maskData.setPosition( m.getIntPosition( 1 ), 1 );

				for ( long z = zcorrInterval.min( 2 ); z <= zcorrInterval.max( 2 ); ++z )
				{
					maskData.setPosition( z, 2 );
					if ( maskData.get().get() < 1 )
					{
						v.set( 0 );
						break;
					}
				}
			}
		}

//		ImageJFunctions.show( mask2d );

		final long[] dim = zcorrInterval.dimensionsAsLongArray();
		for ( int d = 0; d < dim.length; ++d )
			dim[ d ] /= costSteps[ d ];

		final RandomAccessibleInterval<UnsignedByteType> derivative = ArrayImgs.unsignedBytes( dim );
		final Cursor<UnsignedByteType> out = Views.iterable( derivative ).localizingCursor();

		final RandomAccess<UnsignedByteType> in = zcorrExtended.randomAccess();
		final RandomAccess<UnsignedByteType> m = mask2d.randomAccess();
		final int n = out.numDimensions();
		final long[] pos = new long[ n ];

		//System.out.println( zcorrInterval.min( 2 ) + ", " + zcorrInterval.max( 2 ) );

		final double[] medianTmp = new double[ 3 ];

		// done TODO: inpaint OR cut out minimal bounding box during Render export --- we do the minimal bounding box using a mask
		// TODO: smooth cost? - but keep in mind we need a bigger interval for that >> Lazy?
		while ( out.hasNext() )
		{
			final UnsignedByteType v = out.next();
			out.localize( pos );

			// transform pos[] to original image coordinates
			for ( int d = 0; d < n; ++d )
				pos[ d ] = pos[ d ] * costSteps[ d ] + zcorrInterval.min( d );

			m.setPosition( pos[ 0 ], 0 );
			m.setPosition( pos[ 1 ], 1 );

			// no data available in at least one of the z-layers (or all)
			if ( m.get().get() == 0 )
			{
				if ( pos[ 2 ] == zcorrInterval.min( 2 ) || pos[ 2 ] == zcorrInterval.max( 2 ) )
					v.set(255 - outOfBoundsValue); // TODO: variable (average gradient from resin to sample)
				else
					v.set( 255 );
			}
			else
			{
				if ( pos[ 2 ] == zcorrInterval.min( 2 ) )
				{
					// the second surface on top we just fake for now (outsideValue all)
					v.set(255 - outOfBoundsValue); // TODO: variable (average gradient from resin to sample)
				}
				else if ( filter )
				{
					in.setPosition( pos );

					final int x0 = (int)Math.round( medianZ3(in, medianTmp) );
					in.fwd( 2 );
					final int x1 = (int)Math.round( medianZ3(in, medianTmp) );
					v.set( 255 - Math.max( 0, x1 - x0 ) ); // only keep "negative" derivatives (only bright-to-dark)
				}
				else //if ( pos[ 2 ] == zcorrInterval.max( 2 ) )
				{
					// on the last layer we do not check whether it is inside or outside the image
					in.setPosition( pos );

					final int x0 = in.get().get();
					in.fwd( 2 );
					final int x1 = in.get().get();
					v.set( 255 - Math.max( 0, x1 - x0 ) ); // only keep "negative" derivatives (only bright-to-dark)
				}
				/*else
				{
					in.setPosition( pos );
		
					final int x0 = in.get().get();
	
					// TODO: this is a hack, ideally we'd want a mask (on-the-fly or saved) to see where images end
					if ( x0 == 0 && isAnyXYNeighboringPixelBlack( in ) )
					{
						v.set( 255 );
						continue;
					}
	
					in.fwd( 2 );
					final int x1 = in.get().get();
	
					if ( x1 == 0 && isAnyXYNeighboringPixelBlack( in ) )
					{
						v.set( 255 );
						continue;
					}
	
					v.set( 255 - Math.max( 0, x1 - x0 ) ); // only keep "negative" derivatives
				}*/
			}
		}

		//ImageJFunctions.show( Views.subsample( Views.zeroMin( Views.interval( zcorrExtended, zcorrInterval ) ), costSteps[ 0 ], costSteps[ 1 ], costSteps[ 2 ] ) );
		//ImageJFunctions.show( derivative ).setTitle( "derivative");

		//return derivative;

		// derivative typically between 105-255, scale it (2.5 brings it back to 105 after gauss of {0,0,1})
		final RandomAccessibleInterval<DoubleType> derivativeConvert = Converters.convertRAI( derivative, (i,o) -> o.setReal(255.0-((255.0-i.getRealDouble())*4)), new DoubleType() );

		//ImagePlus imp = ImageJFunctions.show( derivativeConvert );
		//imp.setDisplayRange( 0, 255 );
		//imp.setTitle( "derivativeConvert" );
		//SimpleMultiThreading.threadHaltUnClean();

		if ( gauss )
		{
			final RandomAccessibleInterval<DoubleType> derivativeSmooth = ArrayImgs.doubles( dim );
			Gauss3.gauss( new double[] {0,0,1 }, Views.extendValue( derivativeConvert, 255 ), derivativeSmooth );
	
			final RandomAccessibleInterval<UnsignedByteType> derivativeSmoothConvert = Converters.convertRAI(derivativeSmooth, (i, o) -> o.set( (int)Math.round( Math.max(0, Math.min( 255.0, i.get()))) ), new UnsignedByteType() );
	
			return derivativeSmoothConvert;
		}
		else
		{
			final RandomAccessibleInterval<UnsignedByteType> derivativeConvert8Bit = Converters.convertRAI(derivativeConvert, (i, o) -> o.set( (int)Math.round( Math.max(0, Math.min( 255.0, i.get()))) ), new UnsignedByteType() );
			return derivativeConvert8Bit;
		}

		/*
		return processColumnAlongAxis(
				zcorr,
				zcorrInterval, 
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
				kernelSize);*/
	}

	private static final double medianZ3( final RandomAccess<? extends RealType<?>> in, final double[] medianTmp )
	{
		medianTmp[ 0 ] = in.get().getRealDouble();
		in.fwd( 2 );
		medianTmp[ 1 ] = in.get().getRealDouble();
		in.bck( 2 );
		in.bck( 2 );
		medianTmp[ 2 ] = in.get().getRealDouble();
		in.fwd( 2 );

		return Util.median( medianTmp );
	}

	protected static < T extends IntegerType<T>> boolean isAnyXYNeighboringPixelBlack( final RandomAccess<T> in )
	{
		// is it outside image boundaries??
		for ( int e = 0; e <=1; ++e )
		{
			in.bck( e );
			if ( in.get().getInteger() == 0 )
				return true;
			in.fwd( e );
			in.fwd( e );
			if ( in.get().getInteger() == 0 )
				return true;
			in.bck( e );
		}
		return false;
	}

	public static RandomAccessibleInterval<UnsignedByteType> processColumnAlongAxis(
			final RandomAccessibleInterval<UnsignedByteType> zcorr,
			final Interval zcorrInterval,
			final boolean filter,
			final boolean gauss,
			long[] zcorrSize,
			int[] costSteps,
			int costAxis,
			Long[] gridCoord,
			ExecutorService executorService,
			int bandSize,
			int minGradient,
			int slopeCorrXRange,
			float slopeCorrBandFactor,
			float maxSlope,
			float minSlope,
			int startThresh,
			int kernelSize) throws Exception {

		/*
		final N5Reader n5 = new N5FSReader(n5Path);

		RandomAccessibleInterval<UnsignedByteType> zcorr = N5Utils.open(n5, zcorrDataset);

		// The cost function is implemented to be processed along dimension = 2, costAxis should be 0 or 2 with the current image data
		zcorr = Views.permute(zcorr, costAxis, 2);

		RandomAccessible<UnsignedByteType> zcorrExtended = Views.extendZero(zcorr);

		Interval zcorrInterval = getZcorrInterval(gridCoord[0], gridCoord[1], zcorrSize, zcorrBlockSize, costSteps);

		zcorr = Views.interval( zcorrExtended, zcorrInterval );
		*/

		Img<UnsignedByteType> cost = ArrayImgs.unsignedBytes(
								     (long) Math.ceil((float)zcorrInterval.dimension(0) / (float)costSteps[0]),
								     (long) Math.ceil((float)zcorrInterval.dimension(1) / (float)costSteps[1]),
								     (long) Math.ceil((float)zcorrInterval.dimension(2) / (float)costSteps[2]));
		RandomAccess<UnsignedByteType> costAccess = cost.randomAccess();

		//System.out.println("Zcorr block size: " + Arrays.toString( zcorrBlockSize ) );
		System.out.println("Zcorr interval: " + Util.printInterval( zcorrInterval ) );
		System.out.println("Cost dimensions: " + Arrays.toString(Intervals.dimensionsAsIntArray(cost)) );

		//new ij.ImageJ();
		//ImageJFunctions.show( zcorr );
		//SimpleMultiThreading.threadHaltUnClean();

		// Loop over slices and populate cost
		int maxZ = (int) Math.min(zcorrInterval.dimension(2), (zcorrSize[2] - gridCoord[1] * zcorrInterval.dimension(2)));// handle remaining boundary
		for( int zIdx = 0; zIdx < maxZ; zIdx += costSteps[2] ) {
		    RandomAccessibleInterval<UnsignedByteType> slice = Views.zeroMin(Views.hyperSlice(zcorr, 2, zIdx + zcorrInterval.min(2)));

			RandomAccess<UnsignedByteType> sliceAccess = slice.randomAccess();
			ArrayImg<UnsignedByteType, ByteArray> sliceCopy = ArrayImgs.unsignedBytes(slice.dimension(0), slice.dimension(1));

			System.out.println( zIdx + " " + zcorr.max(2) + " Slice dimensions: " + Arrays.toString(Intervals.dimensionsAsIntArray(slice)));
			System.out.println("Slice copy dimensions: " + Arrays.toString(Intervals.dimensionsAsIntArray(sliceCopy)));

			Cursor<UnsignedByteType> cc = Views.zeroMin(sliceCopy).localizingCursor();
			try {
			while( cc.hasNext() ) {
				cc.fwd();
				sliceAccess.setPosition(cc);
				cc.get().set(sliceAccess.get());// FIXME: currently getting an index error here from the set() call
			}
			} catch (Exception e) {
				System.out.println("Exception: " + zIdx + ":" + gridCoord[0] + ", " + gridCoord[1] + " :: " + cc.getDoublePosition(0) + ", " + cc.getDoublePosition(1));
				e.printStackTrace();
			}

			//ImageJFunctions.show( sliceCopy ).setTitle( "sliceCopy");

			System.out.println("Compute resin.");

			DagmarCost costFn = new DagmarCost();

			costFn.setBandSize(bandSize);
			costFn.setMinGradient(minGradient);
			costFn.setSlopeCorrXRange(slopeCorrXRange);
			costFn.setSlopeCorrBandFactor(slopeCorrBandFactor);
			costFn.setMaxSlope(maxSlope);
			costFn.setMinSlope(minSlope);
			costFn.setStartThresh(startThresh);
			costFn.setKernelSize(kernelSize);

			// Compute cost on subsampled
			//Img<FloatType> costSlice = DagmarCost.computeResin(sliceCopy, costSteps[0], executorService);

			//ImagePlus imp = new ImagePlus("/Users/spreibi/Documents/Janelia/Projects/Male CNS+VNC Alignment/07m/BR-Sec37/raw.tif");
			//sliceCopy = ArrayImgs.unsignedBytes( (byte[])imp.getProcessor().getPixels(), new long[] { imp.getWidth(), imp.getHeight() } );
			//ImageJFunctions.show( sliceCopy );

			final Interval originalInterval = new FinalInterval( new long[] { 0, 0}, new long[] { slice.dimension(0) - 1, slice.dimension(1) - 1 } );
			final Interval interval =
					PreFilter.filter(
							new ByteProcessor( (int)slice.dimension(0), (int)slice.dimension(1), sliceCopy.update( null ).getCurrentStorageArray() ),
							filter );

			final RandomAccessibleInterval< FloatType > costSliceFullRes;

			if ( interval != null )
			{
				// not completely empty (otherwise just stays black)

				// run on the cut out area where there are actual images (influences cost function!)
				RandomAccessibleInterval<FloatType> costSliceFullResRaw = costFn.computeResin( Views.interval( sliceCopy, interval ), true, false, executorService);

				if ( gauss )
				{
					double s = costSteps[0];
					System.out.println( Math.sqrt( s*s - 0.5*0.5 ) );
					Gauss3.gauss( new double[] { Math.sqrt( s*s - 0.5*0.5 ), 0 }, Views.extendBorder( costSliceFullResRaw ), costSliceFullResRaw );
				}

				costSliceFullResRaw = Views.interval( Views.extendZero( Views.translate( costSliceFullResRaw, interval.min( 0 ), interval.min( 1 ) ) ), originalInterval );

				// TODO? if an entire column is full of zeros, cost should be uniformly 255
				// right now: wherever the intensity is 0 the cost is 255
				costSliceFullRes =
						Converters.convertRAI(
								sliceCopy,
								costSliceFullResRaw,
								(i0, i1, o) -> { if ( i0.get() == 0 ) o.set( 255 ); else o.set( i1.get() ); },
								new FloatType() );
			}
			else
			{
				costSliceFullRes = 
						Converters.convertRAI(
								sliceCopy,
								(i0, o) -> { o.set( 255 ); },
								new FloatType() );
			}

			//ImageJFunctions.show( costSliceFullRes ).setDisplayRange(0, 255);;
			//SimpleMultiThreading.threadHaltUnClean();

			SubsampleIntervalView<FloatType> costSlice =
					Views.subsample(
							costSliceFullRes,
							costSteps[0],
							1);

			//ImageJFunctions.show( costSlice );
			//SimpleMultiThreading.threadHaltUnClean();

			//System.out.println("Cost slice dimensions: " + Arrays.toString(Intervals.dimensionsAsIntArray(costSlice)));

			costAccess = Views.hyperSlice( cost, 2, zIdx / costSteps[2] ).randomAccess();

			Cursor<FloatType> csCur = Views.iterable(costSlice).localizingCursor();
			while( csCur.hasNext() ) {
				csCur.fwd();
				costAccess.setPosition(csCur);

				costAccess.get().set((int) csCur.get().get());
			}


			//ImageJFunctions.show( cost );
			//SimpleMultiThreading.threadHaltUnClean();
		}

		return cost;//CostUtils.floatAsUnsignedByte(CostUtils.initializeCost(cost));
	}

	protected static Interval getZcorrInterval(Long gridX, Long gridY, long[] zcorrSize, int[] zcorrBlockSize, int[] costSteps) {
		long startX = ( gridX * costSteps[0] ) * zcorrBlockSize[0];
		long startZ = 0;
		long startY = ( gridY * costSteps[1] ) * zcorrBlockSize[1];
		long stopX = ( ( gridX + 1 ) * costSteps[0] ) * zcorrBlockSize[0] - 1;
		long stopZ = zcorrSize[2] - 1;
		long stopY = ( ( gridY + 1 ) * costSteps[1] ) * zcorrBlockSize[1] - 1;
		return new FinalInterval(
				new long[]{startX, startY, startZ},
				new long[]{stopX, stopY, stopZ});
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName("SparkComputeCostMultiSem");
		if (options.localSparkBindAddress) {
			conf.set("spark.driver.bindAddress", "127.0.0.1");
		}
		final JavaSparkContext sc = new JavaSparkContext(conf);
		
		//final JavaSparkContext sc = null;
		
		computeCost(sc, options);

		sc.close();

	}
}
