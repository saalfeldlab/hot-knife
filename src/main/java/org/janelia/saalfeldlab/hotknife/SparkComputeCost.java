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

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.cost.DagmarCost;
import org.janelia.saalfeldlab.hotknife.cost.PreFilter;
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
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;

/**
 * Export a render stack to N5.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkComputeCost {

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

		@Option(name = "--firstStepScaleNumber",
				usage = "scale number for first cost step, e.g. 1 for s1")
		private int firstStepScaleNumber = 1;

		public String getCostDatasetName(final int index) {
			return costDatasetName + "/s" + (firstStepScaleNumber + index);
		}

		@Option(name = "--costSteps",
				aliases = { "-f", "--factors" },
				usage = "Step sizes for computing cost, e.g. 6,1,6. " +
						"Specify multiple values for downsampling where each factor builds on the last.")
		private String[] costStepsStrings = {
				"6,1,6", "3,1,3", "3,1,3", "3,1,3", "3,1,3", "1,4,1", "1,4,1", "1,4,1"
		};
		private int[][] costSteps;

		@Option(name = "--axisMode", required = true, usage = "Which axis to compute along (or both)? 0, 2, or 02")
		private String axisMode = null;

		@Option(name = "--bandSize", required = false, usage = "Band size for computing distribution of resin")
		private int bandSize = 20;

		@Option(name = "--minGradient", required = false, usage = "Minimum gradient is an upper limit on gradient magnitude")
		private int minGradient = 20;

		@Option(name = "--slopeCorrXRange", required = false, usage = "The width of the band (along x-axis) for computing slope correction")
    	private int slopeCorrXRange = 10;

		@Option(name = "--slopeCorrBandFactor", required = false, usage = "Factor (times band size) for requiring the start of tissue")
    	private float slopeCorrBandFactor = 3.5f;

		@Option(name = "--maxSlope", required = false, usage = "Max slope of for slope correction")
		private float maxSlope = 0.04f;

		@Option(name = "--minSlope", required = false, usage = "Min slope of for slope correction")
		private float minSlope = 0;

		@Option(name = "--startThresh", required = false, usage = "Start threshold for detecting the start of the band region")
		private int startThresh = 50;

		@Option(name = "--kernelSize", required = false, usage = "Kernel size used for computing gradient used in cost stats calculation")
    	private int kernelSize = 5;// for valsCount

		@Option(name = "--normalizeImage", required = false, usage = "uses contrast normalization and median before cost computation")
		private boolean normalizeImage = false;

		@Option(name = "--downsampleCostX", required = false, usage = "properly downsamples cost according to the cost step size (e.g. 6)")
		private boolean downsampleCostX = false;

		@Option(name = "--surfaceN5Output", usage = "N5 output group for surface heighfields, e.g. /heightfields/Sec39/v1_acquire_trimmed_sp1, omit to skip surface fit")
		private String surfaceN5Output = null;

		@Option(name = "--surfaceFirstScale", usage = "initial scale index, e.g. 8")
		private int surfaceFirstScale = 8;

		@Option(name = "--surfaceLastScale", usage = "terminal scale index, e.g. 1")
		private int surfaceLastScale = 1;

		@Option(name = "--surfaceMaxDeltaZ", usage = "maximum slope of the surface in original pixels, e.g. 0.25")
		private double surfaceMaxDeltaZ = 0.25;

		@Option(name = "--surfaceInitMaxDeltaZ", usage = "maximum slope of the surface in original pixels in the first scale level (initialization), e.g. 0.3")
		private double surfaceInitMaxDeltaZ = .3;

		@Option(name = "--surfaceMinDistance", usage = "minimum distance between the both surfaces, e.g. 2000")
		private double surfaceMinDistance = 2000;

		@Option(name = "--surfaceMaxDistance", usage = "maximum distance between the both surfaces, e.g. 4000")
		private double surfaceMaxDistance = 4000;

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
		int[] costSteps = options.getCostSteps(0);
		String axisMode = options.axisMode;

		System.out.println("Computing cost on: " + n5Path + " " + zcorrDataset );
		System.out.println("Cost output: " + costN5Path + " " + costDataset );
		System.out.println("Cost steps: " + costSteps[0] + ", " + costSteps[1] + ", " + costSteps[2] );
		System.out.println("Axis mode: " + axisMode );
		System.out.println("Normalize image: " + options.normalizeImage );
		System.out.println("downsampleCostX: " + options.downsampleCostX );

		final N5Reader n5 = new N5FSReader(n5Path);
		final N5Writer n5w = new N5FSWriter(costN5Path);

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

		long[] costSize = new long[]{ zcorrSize[0] / costSteps[0], zcorrSize[1] / costSteps[1], zcorrSize[2] / costSteps[2] };

		n5w.createDataset(
        		costDataset,
        		costSize,
        		costBlockSize,
        		DataType.UINT8,
        		new GzipCompression());
		n5w.setAttribute(costDataset, "downsamplingFactors", costSteps);
		final ArrayList<Long[]> gridCoords = new ArrayList<>();

//		int gridXSize = (int)Math.ceil(zcorrSize[0] / (float)costBlockSize[0]);
//		int gridZSize = (int)Math.ceil(zcorrSize[2] / (float)costBlockSize[2]);

		int gridXSize = (int)Math.ceil(costSize[0] / (float)costBlockSize[0]);
		int gridZSize = (int)Math.ceil(costSize[2] / (float)costBlockSize[2]);

		for (long x = 25; x < 26; x++) {
			for (long z = 23; z < 24; z++) {
		//for (long x = 0; x < gridXSize; x++) {
		//	for (long z = 0; z < gridZSize; z++) {
				if ( x == 35 ) System.out.println( "z: " + z + ": " + getZcorrInterval(x, z, zcorrSize, zcorrBlockSize, costSteps).min( 2 ));
				gridCoords.add(new Long[]{x, z});
			}
			System.out.println( "x: " + x + ": " + getZcorrInterval(x, 0l, zcorrSize, zcorrBlockSize, costSteps).min( 0 ) );
		}
		//System.exit( 0 );
		System.out.println("Processing " + gridCoords.size() + " grid pairs. " + gridXSize + " by " + gridZSize);

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

		final boolean filter = options.normalizeImage;
		final boolean gauss = options.downsampleCostX;

		rddSlices.foreachPartition( gridCoordPartition -> {
			//gridCoords.forEach(gridCoord -> {

			//ExecutorService executorService =  Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 2);
			ExecutorService executorService =  Executors.newFixedThreadPool(1 );
			//ExecutorService executorService =  Executors.newCachedThreadPool();

			gridCoordPartition.forEachRemaining(gridCoord -> {

				try {
				    processColumn(
						  n5Path, costN5Path, zcorrDataset, costDataset, filter, gauss, costBlockSize, zcorrBlockSize, zcorrSize, costSteps, axisMode, gridCoord, executorService,
						  options.bandSize, options.minGradient, options.slopeCorrXRange, options.slopeCorrBandFactor, options.maxSlope,
						  options.minSlope, options.startThresh, options.kernelSize);
				} catch (Exception e) {
				    e.printStackTrace(); // TODO: is there a reason we are swallowing exceptions?
				}
				
			    });

			executorService.shutdown();

		    });

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
																  options.surfaceMinDistance,
																  options.surfaceMaxDistance,
																  false);
			sparkSurfaceFit.callWithSparkContext(sparkContext);
		}
	}

	// serializable downsample supplier for spark
	public static class N5PathSupplier implements N5WriterSupplier {
		private final String path;
		public N5PathSupplier(final String path) {
			this.path = path;
		}
		@Override
		public N5Writer get()
				throws IOException {
			return new N5FSWriter(path);
		}
	}

	public static void processColumn(
			String n5Path,
			String costN5Path,
			String zcorrDataset,
			String costDataset,
			final boolean filter,
			final boolean gauss,
			int[] costBlockSize,
			int[] zcorrBlockSize,
			long[] zcorrSize,
			int[] costSteps,
			String axisMode,
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
		System.out.println("Processing grid coord: " + gridCoord[0] + " " + gridCoord[1] + " costAxis: " + axisMode);

		RandomAccessibleInterval<UnsignedByteType> cost;
		if (axisMode.equals("2")) {// This is the original mode
			cost = processColumnAlongAxis(n5Path, zcorrDataset, filter, gauss, zcorrBlockSize, zcorrSize, costSteps, 2, gridCoord, executorService, bandSize, minGradient, slopeCorrXRange, slopeCorrBandFactor, maxSlope, minSlope, startThresh, kernelSize);
		} else if (axisMode.equals("0")) {// Compute along axis 0
			cost = processColumnAlongAxis(n5Path, zcorrDataset, filter, gauss, zcorrBlockSize, zcorrSize, costSteps, 0, gridCoord, executorService, bandSize, minGradient, slopeCorrXRange, slopeCorrBandFactor, maxSlope, minSlope, startThresh, kernelSize);
		} else if (axisMode.equals("02")) {// Compute along both 0 and 2 then combine
			RandomAccessibleInterval<UnsignedByteType> cost2 = processColumnAlongAxis(n5Path, zcorrDataset, filter, gauss, zcorrBlockSize, zcorrSize, costSteps, 2, gridCoord, executorService, bandSize, minGradient, slopeCorrXRange, slopeCorrBandFactor, maxSlope, minSlope, startThresh, kernelSize);
			RandomAccessibleInterval<UnsignedByteType> cost0 = processColumnAlongAxis(n5Path, zcorrDataset, filter, gauss, zcorrBlockSize, zcorrSize, costSteps, 0, gridCoord, executorService, bandSize, minGradient, slopeCorrXRange, slopeCorrBandFactor, maxSlope, minSlope, startThresh, kernelSize);
			cost = mergeCosts(cost0, cost2);
		} else {
			throw new IllegalArgumentException("axisMode unknown: " + axisMode);
		}

		//ImageJFunctions.show( cost );
		//SimpleMultiThreading.threadHaltUnClean();

		System.out.println("Writing blocks");

		final N5Writer n5w = new N5FSWriter(costN5Path);

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
					n5w,
					costDataset,
					gridOffset);
		}
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
			final boolean filter,
			final boolean gauss,
			int[] zcorrBlockSize,
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

		final N5Reader n5 = new N5FSReader(n5Path);

		RandomAccessibleInterval<UnsignedByteType> zcorr = N5Utils.open(n5, zcorrDataset);

		// The cost function is implemented to be processed along dimension = 2, costAxis should be 0 or 2 with the current image data
		zcorr = Views.permute(zcorr, costAxis, 2);

		RandomAccessible<UnsignedByteType> zcorrExtended = Views.extendZero(zcorr);

		Interval zcorrInterval = getZcorrInterval(gridCoord[0], gridCoord[1], zcorrSize, zcorrBlockSize, costSteps);

		zcorr = Views.interval( zcorrExtended, zcorrInterval );

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
				kernelSize);
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

		new ij.ImageJ();
		ImageJFunctions.show( zcorr );
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

			ImageJFunctions.show( sliceCopy ).setTitle( "sliceCopy");

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

			ImageJFunctions.show( costSliceFullRes ).setDisplayRange(0, 255);;
			SimpleMultiThreading.threadHaltUnClean();

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

	private static Interval getZcorrInterval(Long gridX, Long gridZ, long[] zcorrSize, int[] zcorrBlockSize, int[] costSteps) {
		long startX = ( gridX * costSteps[0] ) * zcorrBlockSize[0];
		long startY = 0;
		long startZ = ( gridZ * costSteps[2] ) * zcorrBlockSize[2];
		long stopX = ( ( gridX + 1 ) * costSteps[0] ) * zcorrBlockSize[0] - 1;
		long stopY = zcorrSize[1] - 1;
		long stopZ = ( ( gridZ + 1 ) * costSteps[2] ) * zcorrBlockSize[2] - 1;
		return new FinalInterval(
				new long[]{startX, startY, startZ},
				new long[]{stopX, stopY, stopZ});
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName("SparkComputeCost");
		conf.set("spark.driver.bindAddress", "127.0.0.1");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		
		//final JavaSparkContext sc = null;
		
		computeCost(sc, options);

		sc.close();

	}
}
