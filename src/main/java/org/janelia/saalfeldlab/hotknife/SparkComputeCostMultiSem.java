/**
 * License: GPL
 * -
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 * -
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * -
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.N5Path;
import org.janelia.saalfeldlab.hotknife.util.N5PathSupplier;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.parallel.Parallelization;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

/**
 * Export a render stack to N5.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkComputeCostMultiSem {

	@SuppressWarnings("DefaultAnnotationParam")
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

		//@Option(name = "--outOfBoundsValue", usage = "value to use for out-of-bounds pixels (if not given, estimate from data)")
		//private Integer outOfBoundsValue = null;

		@Option(name = "--topLayerCost", usage = "value to use for top cost layer (default: 105)")
		private Integer topLayerCost = 105;

		@Option(name = "--bottomLayerCost", usage = "value to use for bottom cost layer (default: 250)")
		private Integer bottomLayerCost = 250;

		@Option(name = "--intensityRange",
				usage = "Optional raw-intensity clip range as 'min,max' (in input data units, before " +
						"uint8 conversion). When set, the input is converted to uint8 by " +
						"clamp((raw - min) * 255 / (max - min), 0, 255), preserving dynamic range for " +
						"high-bit-depth inputs (uint16/float). Example: " +
						"'--intensityRange 0,1500' for data with background ~200 and specks ~2000. " +
						"Default: unset (matches pre-zarr3 behavior: passthrough for uint8 input, " +
						"raw * 255 / typeMax for higher-bit-depth inputs, which can crush 12-bit-in-uint16 " +
						"values into a tiny dynamic range — supply --intensityRange in that case).")
		private String intensityRangeString = null;

		private double[] intensityRange = null;

		public double[] getIntensityRange() { return intensityRange; }

		@Option(name = "--textureCost",
				usage = "Window radius (in input pixels) for local-std-dev texture cost. When > 0, " +
						"replaces the intensity-derivative cost with the z-derivative of a per-voxel " +
						"std-dev computed over a (2r+1)x(2r+1) XY window via summed-area tables. " +
						"Runtime is independent of window radius (O(W*H) per slice). The input is " +
						"pre-blurred with a small Gauss to suppress pixel-scale grain so OOF doesn't " +
						"score as tissue, and the std is amplified before clamping so tissue saturates " +
						"to a uniform-bright proxy interior. Pairs with --smoothProxy (post-smooth) and " +
						"--proxyThreshold (post-smooth threshold). Amplification reuses " +
						"--zIntensityScale (defaults to 1.0 if unset). Default: 0 (use intensity-based cost).")
		private double textureCost = 0.0;

		@Option(name = "--smoothProxy",
				usage = "Gaussian sigma (in input pixels) for post-smoothing the --textureCost proxy. " +
						"Merges isolated tissue patches and suppresses outlier spikes before the z-derivative " +
						"(use 10-30 to fill gaps between mFOV tiles). Only active when --textureCost > 0. " +
						"Default: 0.0 (no post-smooth).")
		private double smoothProxy = 0.0;

		@Option(name = "--proxyThreshold",
				usage = "Threshold (0-255) applied to the --textureCost proxy after post-smoothing. " +
						"Proxy values strictly below this are set to 0, zeroing out regions with low " +
						"texture energy (OOF background, substrate) while preserving tissue signal. " +
						"Only active when --textureCost > 0.  Default: 0 (no suppression).")
		private double proxyThreshold = 0.0;

		@Option(name = "--zIntensityScale",
				usage = "scale factor for an abs z-intensity-derivative cost: " +
						"cost = 255 - clamp(|I(z+1) - I(z-1)| * scale, 0, 255). " +
						"0 keeps the signed bright-to-dark cost; > 0 switches to the abs cost " +
						"(captures both fade-in and fade-out boundaries, also skips the global image " +
						"inversion). Default: 0.0.")
		private double zIntensityScale = 0.0;

		@Option(name = "--median", usage = "uses median (r=3 in z) before cost computation")
		private boolean median = false;

		@Option(name = "--smoothCost", usage = "smoothes cost in z (s=1.0)")
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

		@Option(name = "--surfaceMaxDistance", usage = "maximum distance between the both surfaces, e.g. 30 (specify a zero or negative value to set relative to dataset size, e.g. -4)")
		private double surfaceMaxDistance = 30;

		@Option(name = "--surfaceBlockSize", usage = "surface block size in pixels, e.g. 128,128")
		private String surfaceBlockSizeString = "128,128";

		private long[] getSurfaceBlockSize() {
			return parseCSLongArray(surfaceBlockSizeString);
		}

		@Option(name = "--localSparkBindAddress", usage = "specify Spark bind address as localhost")
		private boolean localSparkBindAddress = false;

		@Option(name = "--debugMode", usage = "enable debug mode to process only specific blocks")
		private boolean debugMode = false;

		@Option(name = "--debugBlockX", usage = "X coordinate of block to process in debug mode (e.g., 53)")
		private Long debugBlockX = null;

		@Option(name = "--debugBlockY", usage = "Y coordinate of block to process in debug mode (e.g., 34)")
		private Long debugBlockY = null;


		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);

			try {
				parser.parseArgument(args);
				costSteps = new int[costStepsStrings.length][3];

				for (int i = 0; i < costStepsStrings.length; i++) {
					parseCSIntArray(costStepsStrings[i], costSteps[i]);
				}

				if (intensityRangeString != null) {
					final String[] parts = intensityRangeString.split(",");
					if (parts.length != 2) {
						throw new CmdLineException(parser, new IllegalArgumentException(
								"--intensityRange must be 'min,max', got: " + intensityRangeString));
					}
					intensityRange = new double[] {
							Double.parseDouble(parts[0].trim()),
							Double.parseDouble(parts[1].trim())
					};
					if (intensityRange[1] <= intensityRange[0]) {
						throw new CmdLineException(parser, new IllegalArgumentException(
								"--intensityRange max must exceed min: " + intensityRangeString));
					}
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

        /** @return surfaceMaxDistance if surfaceMaxDistance >= 0 else inputDatasetDimensions[2] + surfaceMaxDistance */
        public double getSurfaceMaxDeltaZ(final long[] inputDatasetDimensions) {
            return surfaceMaxDistance > 0 ? surfaceMaxDistance : inputDatasetDimensions[2] + surfaceMaxDistance;
        }
	}

	private static void computeCost(
			final JavaSparkContext sparkContext,
			final Options options) throws IOException {

		logMessage("computeCost: entry");

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

		final N5Reader n5 = N5Util.createN5Reader(n5Path);

		// Skip N5Writer creation in debug mode
		final N5Writer n5w;
		if (options.debugMode) {
			System.out.println("Debug mode: Skipping N5Writer creation for output");
			n5w = null;
		} else {
			n5w = N5Util.createN5Writer(costN5Path);
		}

		/*
		final int outOfBoundsValue;
		if (options.outOfBoundsValue == null) {

			final String s5DatasetName;
			if (options.inputDatasetName.endsWith("s0")) {
				s5DatasetName = options.inputDatasetName.replaceFirst("s0$", "s5");
			} else {
				s5DatasetName = options.inputDatasetName + "/s5";
			}

			if (n5.datasetExists(s5DatasetName)) {
				final IterableInterval<UnsignedByteType> lastLayer = getLastLayer(n5, s5DatasetName);
				outOfBoundsValue = median(lastLayer);
				System.out.println("Out of bounds value automatically computed to be " + outOfBoundsValue);
			} else {
				throw new RuntimeException("Cannot compute out of bounds value automatically because " + s5DatasetName +
                                           " does not exist under " + n5Path);
			}

		} else {
			outOfBoundsValue = options.outOfBoundsValue;
		}

		System.out.println( "outOfBoundsValue=" + outOfBoundsValue );
		System.exit( 0 );
		*/

		final org.janelia.saalfeldlab.n5.DatasetAttributes zcorrAttrs = n5.getDatasetAttributes(zcorrDataset);
		int[] zcorrBlockSize = zcorrAttrs.getBlockSize();
		long[] zcorrSize = zcorrAttrs.getDimensions();


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
        System.out.println( "surfaceMaxDeltaZ: " + options.getSurfaceMaxDeltaZ( zcorrSize ) );

		// Skip dataset creation in debug mode
		if (!options.debugMode) {
			n5w.createDataset(
					costDataset,
					costSize,
					costBlockSize,
					DataType.UINT8,
					new GzipCompression());
			n5w.setAttribute(costDataset, "downsamplingFactors", costSteps);
		}
		final ArrayList<Long[]> gridCoords = new ArrayList<>();

		// for multisem grid along xy
		int gridXSize = (int)Math.ceil(costSize[0] / (float)costBlockSize[0]);
		int gridYSize = (int)Math.ceil(costSize[1] / (float)costBlockSize[1]);

		// Debug mode: process only specific blocks
		if (options.debugMode) {
			System.out.println("Debug mode: === DEBUG MODE ENABLED ===");
			if (options.debugBlockX != null && options.debugBlockY != null) {
				System.out.println("Debug mode: Processing single block: [" + options.debugBlockX + ", " + options.debugBlockY + "]");
				gridCoords.add(new Long[]{options.debugBlockX, options.debugBlockY});
			} else {
				// Default: process just the middle block
				long midX = gridXSize / 2;
				long midY = gridYSize / 2;
				System.out.println("Debug mode: No specific debug blocks specified, processing middle block: [" + midX + ", " + midY + "]");
				gridCoords.add(new Long[]{midX, midY});
			}
		} else {
			for (long x = 0; x < gridXSize; x++) {
				for (long y = 0; y < gridYSize; y++) {
					gridCoords.add(new Long[]{x, y});
				}
			}
		}

		logMessage("computeCost: processing " + gridCoords.size() + " grid pairs. " + gridXSize + " by " + gridYSize);
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
		final boolean debugMode = options.debugMode;


		final int topLayerCost = options.topLayerCost;
		final int  bottomLayerCost = options.bottomLayerCost;
		final double smoothProxy = options.smoothProxy;
		final double textureCost = options.textureCost;
		final double proxyThreshold = options.proxyThreshold;
		final double zIntensityScale = options.zIntensityScale;
		final double[] intensityRange = options.getIntensityRange();

		// Initialize ImageJ if in debug mode
		if (options.debugMode) {
			System.out.println("Debug mode: Initializing ImageJ for visualization...");
			new ij.ImageJ();
		}

		rddSlices.foreachPartition( gridCoordPartition ->
			gridCoordPartition.forEachRemaining( gridCoord ->
				processColumn(
						n5Path, costN5Path, zcorrDataset, costDataset, maskDataset, filter, gauss, debugMode, costBlockSize, zcorrBlockSize, zcorrSize, costSteps, gridCoord, topLayerCost, bottomLayerCost, smoothProxy, textureCost, proxyThreshold, zIntensityScale, intensityRange)));

		// done with cost

		if (options.debugMode) {
			System.out.println("Debug mode: Skipping downsampling and surface fitting");
			SimpleMultiThreading.threadHaltUnClean();
			return;
		}

		logMessage("computeCost: downsampling cost steps");
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

		logMessage("computeCost: exit");
	}

    private static void computeSurfaceFit(final JavaSparkContext sparkContext,
                                          final Options options,
                                          final double maxDeltaZ)
            throws IOException {

        logMessage("computeSurfaceFit: entry, n5Path=" + options.outputN5Path +
                   ", outGroup=" + options.surfaceN5Output);

        SparkSurfaceFit sparkSurfaceFit = new SparkSurfaceFit(options.outputN5Path,
                                                              options.outputN5Path,
                                                              options.costDatasetName,
                                                              options.inputDatasetName,
                                                              options.surfaceN5Output,
                                                              options.surfaceFirstScale,
                                                              options.surfaceLastScale,
                                                              options.surfaceMaxDeltaZ,
                                                              options.surfaceInitMaxDeltaZ,
                                                              options.finalMaxDeltaZ,
                                                              options.surfaceMinDistance,
                                                              maxDeltaZ,
                                                              true, // no need to permute with multi-sem
                                                              false);
        sparkSurfaceFit.callWithSparkContext(sparkContext,
                                             options.getSurfaceBlockSize());

        logMessage("computeSurfaceFit: exit");
    }

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static RandomAccessibleInterval<UnsignedByteType> openAsUint8(
			final N5Reader n5, final String dataset, final double[] intensityRange) {
		final RandomAccessibleInterval raw = N5Utils.open(n5, dataset);
		final Object pixelType = Util.getTypeFromInterval(raw);
		if (intensityRange != null) {
			// Explicit raw-value clip range: uint8 = clamp((raw - min) * 255 / (max - min), 0, 255).
			// Maps high-bit-depth inputs (uint16/float) into a useful uint8 dynamic range when the
			// default type-max scaling below would crush them.
			final double cmin = intensityRange[0];
			final double crange = intensityRange[1] - intensityRange[0];
			return Converters.convertRAI(
					(RandomAccessibleInterval<RealType<?>>) raw,
					(i, o) -> o.set((int) Math.round(Math.max(0.0, Math.min(255.0,
							(i.getRealDouble() - cmin) * 255.0 / crange)))),
					new UnsignedByteType());
		}
		// Pre-zarr3 fallback: passthrough for uint8, otherwise scale by 255 / typeMax.
		if (pixelType instanceof UnsignedByteType) {
			return (RandomAccessibleInterval<UnsignedByteType>) raw;
		}
		final double maxVal = ((RealType<?>) pixelType).getMaxValue();
		return Converters.convertRAI(
				(RandomAccessibleInterval<RealType<?>>) raw,
				(i, o) -> o.set((int) (i.getRealDouble() * 255.0 / maxVal)),
				new UnsignedByteType());
	}

	public static void processColumn(
			String n5Path,
			String costN5Path,
			String zcorrDataset,
			String costDataset,
			String maskDataset,
			final boolean filter,
			final boolean gauss,
			final boolean debugMode,
			int[] costBlockSize,
			int[] zcorrBlockSize,
			long[] zcorrSize,
			int[] costSteps,
			Long[] gridCoord,
			//int outOfBoundsValue,
			int topLayerCost,
			int bottomLayerCost,
			final double smoothProxy,
			final double textureCost,
			final double proxyThreshold,
			final double zIntensityScale,
			final double[] intensityRange )
	{
		System.out.println("Processing grid coord: " + gridCoord[0] + " " + gridCoord[1] );

		RandomAccessibleInterval<UnsignedByteType> cost =
				processColumnAlongAxis(n5Path, zcorrDataset, maskDataset, filter, gauss, debugMode, zcorrBlockSize, zcorrSize, costSteps, gridCoord, topLayerCost, bottomLayerCost, smoothProxy, textureCost, proxyThreshold, zIntensityScale, intensityRange);

		if (debugMode) {
			ImageJFunctions.show( cost, "Cost Block [" + gridCoord[0] + "," + gridCoord[1] + "]" );
		}

		System.out.println( "cost: " + Util.printInterval( cost ));

		// Skip writing in debug mode
		if (debugMode) {
			System.out.println("Debug mode: Skipping N5 write operations");
			return;
		}

		System.out.println("Writing blocks");

        // TODO: wrong dimensions
        N5Writer n5w = N5Util.createN5Writer(costN5Path);

        // Now loop over blocks and write (for multisem, usually just one block in z)
        for( int zGrid = 0; zGrid <= Math.ceil(zcorrSize[2] / (double) zcorrBlockSize[2]); zGrid++ )
        {
            final long[] gridOffset = new long[]{gridCoord[0], gridCoord[1], zGrid }; //TODO: is this in original or cost steps?

            System.out.println( "gridOffset: " + Util.printCoordinates( gridOffset ));

            RandomAccessibleInterval<UnsignedByteType> block = Views.interval(
                    Views.extendZero( cost ),
                    new FinalInterval(
                            new long[]{0, 0, zGrid * (long) zcorrBlockSize[2]},
                            new long[]{cost.dimension(0) - 1, cost.dimension(1) - 1,(zGrid + 1) * (long) zcorrBlockSize[2] - 1 }));

            System.out.println( "block: " + Util.printInterval( block ));

            N5Utils.saveBlock(
                    block,
                    n5w,
                    costDataset,
                    gridOffset);
        }

        //SimpleMultiThreading.threadHaltUnClean();
	}

	public static RandomAccessibleInterval<UnsignedByteType> processColumnAlongAxis(
			String n5Path,
			String zcorrDataset,
			String maskDataset,
			final boolean filter,
			final boolean gauss,
			final boolean debugMode,
			int[] zcorrBlockSize,
			long[] zcorrSize,
			int[] costSteps,
			Long[] gridCoord,
			//int outOfBoundsValue,
			int topLayerCost,
			int bottomLayerCost,
			final double smoothProxy,
			final double textureCost,
			final double proxyThreshold,
			final double zIntensityScale,
			final double[] intensityRange ) {

		RandomAccessibleInterval<UnsignedByteType> zcorrRaw;
		final RandomAccessibleInterval<UnsignedByteType> maskRaw;
		final RandomAccessible<UnsignedByteType> maskExtended;

        zcorrRaw = openAsUint8(N5Util.createN5Reader(n5Path), zcorrDataset, intensityRange);

        if ( maskDataset != null )
        {
            // The mask is a label image (0 = no data), not intensity, so it must NOT be
            // rescaled by --intensityRange — doing so would clamp its values to 0 and make
            // the whole column read as "no data". Always open it with the default conversion.
            RandomAccessibleInterval<UnsignedByteType> maskRawTmp = openAsUint8(N5Util.createN5Reader(n5Path), maskDataset, null);
            maskRaw = maskRawTmp;

            if ( !Intervals.equals(zcorrRaw, maskRaw) )
                throw new RuntimeException( "zCorrRaw interval [" + Util.printInterval(zcorrRaw) + "] and mask interval [" + Util.printInterval(maskRaw) + "] are not the same, quitting." );

            maskExtended = Views.extendZero( maskRaw );
        }
        else
        {
            maskRaw = null;
            maskExtended = null;
        }

        System.out.println("********* process along column axis");

        // The cost function is implemented to be processed along dimension = 2, costAxis should be 0 or 2 with the current image data
		// zcorr = Views.permute(zcorr, costAxis, 2);

		final Interval zcorrInterval = getZcorrInterval(gridCoord[0], gridCoord[1], zcorrSize, zcorrBlockSize, costSteps);


        // The signed Multi-SEM cost (255 - max(0, I(z+1) - I(z))) needs the image inverted so that
        // resin->tissue shows up as a bright->dark transition. The abs cost is direction-independent
        // and operates on the original intensities. Texture cost also operates on original intensities.
        if ( zIntensityScale <= 0 && textureCost <= 0 ) {
            zcorrRaw = Converters.convertRAI( zcorrRaw, (i,o) -> {o.set( 255-i.get());}, new UnsignedByteType() );
        }
		final RandomAccessible<UnsignedByteType> zcorrExtended;
		if ( textureCost > 0 ) {

			System.out.println("********* inside texture cost (local-std-dev)");
			// Local-std-dev texture proxy via summed-area tables (one for I, one for I²).
			// Per-pixel query is O(1) lookups regardless of window radius:
			//   sum  = SAT (x+r+1, y+r+1) - SAT (x-r, y+r+1) - SAT (x+r+1, y-r) + SAT (x-r, y-r)
			//   sum2 = SAT2(...)                                              (same with I²)
			//   var  = sum2/n - (sum/n)²    proxy = min(255, sqrt(max(0, var)))
			// Border handling: the query window is clipped to image bounds and n is
			// recomputed from the actual area — equivalent to extendBorder + crop.
			// Runtime O(W·H) per slice — independent of window radius.
			// Honors --smoothProxy (post-smooth) and --proxyThreshold (post-smooth threshold).
			final int radius = (int) Math.max( 1, Math.round( textureCost ) );
			final long[] dims = zcorrInterval.dimensionsAsLongArray();
			final long[] origin = zcorrInterval.minAsLongArray();
			final int W = (int) dims[ 0 ];
			final int H = (int) dims[ 1 ];
			final int Z = (int) dims[ 2 ];

			// Saturation factor: amplifies std before clamping to [0,255] so tissue (std typically
			// 30-80 in input units) saturates at 255 — giving a uniform-bright tissue interior and
			// a much sharper tissue/OOF gap. 4× pushes tissue solidly into the saturated range
			// while leaving OOF (std ~5-15) distinct at 20-60.
			final double stdSaturation = 4.0;
			// Compute the element count in long so the int multiplication W*H*Z can't silently
			// overflow to a negative/small size. A single ArrayImg byte[] is capped at
			// Integer.MAX_VALUE elements regardless, so fail loudly if a column exceeds that.
			final long numElements = (long) W * H * Z;
			if ( numElements > Integer.MAX_VALUE )
				throw new IllegalArgumentException(
						"Texture-cost column too large for a single buffer: W*H*Z = " + numElements +
						" (" + W + "x" + H + "x" + Z + ") exceeds Integer.MAX_VALUE. " +
						"Reduce the XY block size or increase costSteps." );
			final byte[] proxyBuf = new byte[ (int) numElements ];
			final Img<UnsignedByteType> proxy = ArrayImgs.unsignedBytes( proxyBuf, dims );
			final RandomAccessibleInterval<UnsignedByteType> proxyTr = Views.translate( proxy, origin );

			// Per-z-slice SAT build + query, dispatched across the ambient
			// Parallelization.getTaskExecutor() — same TaskExecutor / ForkJoinPool that Gauss3
			// uses elsewhere, so no new thread pool and no new oversubscription class.
			// Each worker materializes only its own slice of zcorrRaw into a local W*H byte
			// buffer (avoids holding the whole W*H*Z volume) and allocates its own SAT buffers.
			final long ox = origin[ 0 ];
			final long oy = origin[ 1 ];
			final long oz = origin[ 2 ];
			final RandomAccessibleInterval<UnsignedByteType> srcRA = zcorrRaw;
			final List<Integer> zRange = IntStream.range( 0, Z ).boxed().collect( Collectors.toList() );
			Parallelization.getTaskExecutor().forEach( zRange, zi -> {
				// Materialize this slice into a flat byte[] for fast row-major SAT reads.
				final byte[] sliceBuf = new byte[ W * H ];
				{
					final FinalInterval slice = new FinalInterval(
							new long[] { ox, oy, oz + zi },
							new long[] { ox + W - 1, oy + H - 1, oz + zi } );
					final Cursor<UnsignedByteType> inC = Views.flatIterable( Views.interval( srcRA, slice ) ).cursor();
					for ( int i = 0; i < W * H; ++i ) {
						sliceBuf[ i ] = (byte) inC.next().get();
					}
				}

				final long[] sat  = new long[ ( W + 1 ) * ( H + 1 ) ];
				final long[] sat2 = new long[ ( W + 1 ) * ( H + 1 ) ];
				final int zOff = zi * W * H;

				// Build summed-area tables for this slice using running per-row sums.
				for ( int yi = 0; yi < H; ++yi ) {
					final int satRow  = ( yi + 1 ) * ( W + 1 );
					final int satPrev = yi * ( W + 1 );
					long rowSum  = 0;
					long rowSum2 = 0;
					for ( int xi = 0; xi < W; ++xi ) {
						final int v = sliceBuf[ yi * W + xi ] & 0xFF;
						rowSum  += v;
						rowSum2 += (long) v * v;
						sat [ satRow + xi + 1 ] = sat [ satPrev + xi + 1 ] + rowSum;
						sat2[ satRow + xi + 1 ] = sat2[ satPrev + xi + 1 ] + rowSum2;
					}
				}

				// Query SAT per pixel: clipped (2r+1)² window → mean, variance, std.
				for ( int yi = 0; yi < H; ++yi ) {
					final int y0 = Math.max( 0, yi - radius );
					final int y1 = Math.min( H, yi + radius + 1 );
					final int sa = y0 * ( W + 1 );
					final int sb = y1 * ( W + 1 );
					for ( int xi = 0; xi < W; ++xi ) {
						final int x0 = Math.max( 0, xi - radius );
						final int x1 = Math.min( W, xi + radius + 1 );
						final long s  = sat [ sb + x1 ] - sat [ sa + x1 ] - sat [ sb + x0 ] + sat [ sa + x0 ];
						final long s2 = sat2[ sb + x1 ] - sat2[ sa + x1 ] - sat2[ sb + x0 ] + sat2[ sa + x0 ];
						final int n = ( x1 - x0 ) * ( y1 - y0 );
						final double mean = (double) s / n;
						final double var  = (double) s2 / n - mean * mean;
						final double std  = Math.sqrt( Math.max( 0.0, var ) );
						final int amp = (int) Math.round( std * stdSaturation );
						proxyBuf[ zOff + yi * W + xi ] = (byte) Math.min( 255, amp );
					}
				}
			} );

			// Optional XY post-smooth: merges isolated tissue patches and suppresses outlier
			// spikes before the z-derivative runs. Eagerly materialized into a flat byte buffer.
			final RandomAccessibleInterval<UnsignedByteType> smoothedProxy;
			if ( smoothProxy > 0 ) {
				final Img<UnsignedByteType> smoothProxyImg = ArrayImgs.unsignedBytes( dims );
				final RandomAccessibleInterval<UnsignedByteType> smoothProxyTr = Views.translate( smoothProxyImg, origin );
				Gauss3.gauss( new double[] { smoothProxy, smoothProxy, 0.0 },
						Views.extendBorder( proxyTr ), smoothProxyTr );
				smoothedProxy = smoothProxyTr;
			} else {
				smoothedProxy = proxyTr;
			}

			// Post-smooth activation threshold (zeros background regions).
			if ( proxyThreshold > 0 ) {
				final int activationThresh = (int) Math.ceil( proxyThreshold );
				int smoothMin = 255, smoothMax = 0;
				long zeroed = 0, total = 0;
				for ( final UnsignedByteType pix : Views.iterable( smoothedProxy ) ) {
					final int v = pix.get();
					smoothMin = Math.min( smoothMin, v );
					smoothMax = Math.max( smoothMax, v );
					total++;
					if ( v < activationThresh ) { pix.set( 0 ); zeroed++; }
				}
				System.out.println( "proxyThreshold (post-smooth): std proxy range [" + smoothMin + ", " + smoothMax
						+ "], zeroed " + zeroed + "/" + total + " pixels (thresh=" + activationThresh + ")" );
			}

			zcorrExtended = Views.extendBorder( smoothedProxy );
		} else {
			zcorrExtended = Views.extendBorder( zcorrRaw );//Views.extendValue(zcorrRaw, outOfBoundsValue);
		}

		if (debugMode) {
			System.out.println("Debug mode: Displaying input data...");
			ImageJFunctions.show( Views.interval( zcorrExtended, zcorrInterval ), "Input [" + gridCoord[0] + "," + gridCoord[1] + "]" );
			if ( maskRaw != null) {
				ImageJFunctions.show( Views.interval( maskRaw, zcorrInterval ), "Mask [" + gridCoord[0] + "," + gridCoord[1] + "]" );
			}
		}

		// compute derivative in z and keep only negative values
		// we set the outofbounds to "outsideValue" above, which is about the resin color in case the sample touches the image boundary
		// TODO: the derivative is offset by 0.5 pixels on the bottom, and by 0.5 px on the top towards the other direction

		//final RandomAccessibleInterval<UnsignedByteType> zcorrSubsampled = Views.subsample( zcorr, costSteps[ 0 ], costSteps[ 1 ], costSteps[ 2 ] );

		// by default, there is data everywhere (mask set to 255)
		// we create a projected mask, if at any pixel in z there is no data, the mask will say there is no data
		final RandomAccessibleInterval<UnsignedByteType> mask2d =
				Views.translate(
						ArrayImgs.unsignedBytes(
								zcorrInterval.dimension( 0 ), zcorrInterval.dimension(1 )),
						zcorrInterval.min( 0 ), zcorrInterval.min(1 ));

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
					v.set( topLayerCost );//v.set(255 - outOfBoundsValue); // TODO: variable (average gradient from resin to sample)
				else
					v.set( 255 );
			}
			else
			{
				if ( pos[ 2 ] == zcorrInterval.min( 2 ) )
				{
					// the second surface on top we just fake for now (outsideValue all)
					v.set( topLayerCost );//v.set(255 - outOfBoundsValue); // TODO: variable (average gradient from resin to sample)
				}
				else if ( pos[ 2 ] == zcorrInterval.max( 2 ) )
				{
					// the second surface on top we just fake for now (outsideValue all)
					v.set( bottomLayerCost );//v.set(255 - outOfBoundsValue); // TODO: variable (average gradient from resin to sample)
				}
				else if ( zIntensityScale > 0 || textureCost > 0 )
				{
					// Abs z-derivative of (pre-smoothed) intensity OR the texture proxy. Captures a
					// boundary as a fade in either direction. When in texture mode and the user did
					// not set --zIntensityScale, default the amplification to 1.0.
					final double scale = zIntensityScale > 0 ? zIntensityScale : 1.0;
					final long zCur = pos[ 2 ];
					final int k = 1;

					pos[ 2 ] = zCur - k; in.setPosition( pos );
					final double iLow  = filter ? medianZ3( in, medianTmp ) : in.get().get();
					pos[ 2 ] = zCur + k; in.setPosition( pos );
					final double iHigh = filter ? medianZ3( in, medianTmp ) : in.get().get();
					pos[ 2 ] = zCur;

					v.set( 255 - (int)Math.min( 255, Math.round( Math.abs( iHigh - iLow ) * scale ) ) );
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

		if (debugMode) {
			System.out.println("Debug mode: Displaying derivative...");
			ImageJFunctions.show( derivative, "Derivative [" + gridCoord[0] + "," + gridCoord[1] + "]" );
		}

		// derivative typically between 105-255, scale it (2.5 brings it back to 105 after gauss of {0,0,1})
		final RandomAccessibleInterval<DoubleType> derivativeConvert = Converters.convertRAI( derivative, (i,o) -> o.setReal(255.0-((255.0-i.getRealDouble())*4)), new DoubleType() );

		if (debugMode) {
			System.out.println("Debug mode: Displaying derivative converted...");
			ij.ImagePlus imp = ImageJFunctions.show( derivativeConvert, "Derivative Converted [" + gridCoord[0] + "," + gridCoord[1] + "]" );
			imp.setDisplayRange( 0, 255 );
		}

		if ( gauss )
		{
			final RandomAccessibleInterval<DoubleType> derivativeSmooth = ArrayImgs.doubles( dim );
			Gauss3.gauss( new double[] {0,0,1 }, Views.extendValue( derivativeConvert, 255 ), derivativeSmooth );

			if (debugMode) {
				System.out.println("Debug mode: Displaying derivative smoothed...");
				ij.ImagePlus imp = ImageJFunctions.show( derivativeSmooth, "Derivative Smoothed [" + gridCoord[0] + "," + gridCoord[1] + "]" );
				imp.setDisplayRange( 0, 255 );
			}

			return Converters.convertRAI(derivativeSmooth, (i, o) -> o.set((int) Math.round(Math.max(0, Math.min(255.0, i.get())))), new UnsignedByteType());
		}
		else
		{
			return Converters.convertRAI(derivativeConvert, (i, o) -> o.set((int) Math.round(Math.max(0, Math.min(255.0, i.get())))), new UnsignedByteType());
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

	private static double medianZ3(final RandomAccess<? extends RealType<?>> in, final double[] medianTmp)
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

	protected static Interval getZcorrInterval(Long gridX, Long gridY, long[] zcorrSize, int[] zcorrBlockSize, int[] costSteps) {
		long startX = ( gridX * costSteps[0] ) * zcorrBlockSize[0];
		long startZ = 0;
		long startY = ( gridY * costSteps[1] ) * zcorrBlockSize[1];
		long stopX = ( ( gridX + 1 ) * costSteps[0] ) * zcorrBlockSize[0] - 1;
		long stopZ = zcorrSize[2] - 1;
		long stopY = ( ( gridY + 1 ) * costSteps[1] ) * zcorrBlockSize[1] - 1;
		// Clip to the actual input volume; without this, columns at the right/bottom edge
		// extend far past zcorrSize and produce huge intervals filled by Views.extendBorder.
		stopX = Math.min( stopX, zcorrSize[0] - 1 );
		stopY = Math.min( stopY, zcorrSize[1] - 1 );
		return new FinalInterval(
				new long[]{startX, startY, startZ},
				new long[]{stopX, stopY, stopZ});
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		System.out.println( "DEBUGMODE: " + options.debugMode );

		final SparkConf conf = new SparkConf().setAppName("SparkComputeCostMultiSem");
		if (options.localSparkBindAddress) {
			conf.set("spark.driver.bindAddress", "127.0.0.1");
		}
		final JavaSparkContext sc = new JavaSparkContext(conf);

		//final JavaSparkContext sc = null;

		computeCostAndSurfaceFit(options, sc);

		sc.close();

	}

	public static void computeCostAndSurfaceFit(final Options options,
												final JavaSparkContext sc)
			throws IOException {

		try (final N5Reader n5 = new N5Path(options.outputN5Path).openReader()) {

			final String firstCostDataset = options.getCostDatasetName(0);
			if (n5.exists(firstCostDataset)) {
				logMessage("computeCostAndSurfaceFit: outputN5Path " + options.outputN5Path +
						   " firstCostDataset " + firstCostDataset + " already exists, skipping cost computation");
			} else {
				computeCost(sc, options);
			}

			if (options.surfaceN5Output != null) {

				if (n5.exists(options.surfaceN5Output)) {
					logMessage("computeCostAndSurfaceFit: outputN5Path " + options.outputN5Path +
							   " surfaceN5Output " + options.surfaceN5Output + " already exists, skipping surface fitting");
				} else {
					final long[] inputDimensions = n5.getDatasetAttributes(options.inputDatasetName).getDimensions();
					final double maxDeltaZ = options.getSurfaceMaxDeltaZ(inputDimensions);
					computeSurfaceFit(sc, options, maxDeltaZ);
				}

			}
		}
	}

	private static void logMessage(final String message) {
		org.janelia.saalfeldlab.hotknife.util.Util.logMessage(SparkComputeCostMultiSem.class.getName(), message);
	}
}
