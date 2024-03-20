package org.janelia.saalfeldlab.hotknife;

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.N5PathSupplier;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.janelia.saalfeldlab.hotknife.AbstractOptions.parseCSIntArray;
import static org.janelia.saalfeldlab.n5.spark.downsample.scalepyramid.N5ScalePyramidSpark.downsampleScalePyramid;

public class SparkAdjustLayerIntensityN5 {

	// lower threshold to be considered as content
	public static final int LOWER_THRESHOLD = 20;
	// upper threshold to be considered as content
	public static final int UPPER_THRESHOLD = 120;
	// downscale level for computing shifts
	public static final int DOWNSCALE_LEVEL = 5;


	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput",
				required = true,
				usage = "Input N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5")
		private String n5PathInput = null;

		@Option(name = "--n5DatasetInput",
				required = true,
				usage = "Input N5 dataset, e.g. /render/slab_070_to_079/s075_m119_align_big_block_ic___20240308_072106")
		private String n5DatasetInput = null;

		@Option(
				name = "--factors",
				usage = "If specified, generates a scale pyramid with given factors, e.g. 2,2,1")
		public String factors;

		@Option(name = "--invert", usage = "Invert before saving to N5, e.g. for MultiSEM")
		private boolean invert = false;

		public Options(final String[] args) {
			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				parsedSuccessfully = true;
			} catch (final Exception e) {
				e.printStackTrace(System.err);
				parser.printUsage(System.err);
			}
		}
	}

	private static void processAndSaveFullScaleBlock(final String n5PathInput,
													 final String n5PathOutput,
													 final String datasetName, // should be s0
													 final String datasetNameOutput,
													 final List<Double> shifts,
													 final long[] dimensions,
													 final int[] blockSize,
													 final long[][] gridBlock,
													 final boolean invert) {

		final N5Reader n5Input = new N5FSReader(n5PathInput);
		final N5Writer n5Output = new N5FSWriter(n5PathOutput);

		final RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5Input, datasetName);
		final RandomAccessibleInterval<UnsignedByteType> filteredSource = applyShifts(sourceRaw, shifts, invert);

		final FinalInterval gridBlockInterval =
				Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
										gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);

		N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
								  n5Output,
								  datasetNameOutput,
								  new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
								  gridBlock[2],
								  new UnsignedByteType());
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final SparkAdjustLayerIntensityN5.Options options = new SparkAdjustLayerIntensityN5.Options(args);
		if (!options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final SparkConf conf = new SparkConf().setAppName("SparkNormalizeN5");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");

		final N5Reader n5Input = new N5FSReader(options.n5PathInput);

		final String fullScaleInputDataset = options.n5DatasetInput + "/s5";
		final int[] blockSize = n5Input.getAttribute(fullScaleInputDataset, "blockSize", int[].class);
		final long[] dimensions = n5Input.getAttribute(fullScaleInputDataset, "dimensions", long[].class);

		final int[] gridBlockSize = new int[]{blockSize[0] * 8, blockSize[1] * 8, blockSize[2]};
		final List<long[][]> grid = Grid.create(dimensions, gridBlockSize, blockSize);

		final String downScaledDataset = options.n5DatasetInput + "/s" + DOWNSCALE_LEVEL;
		final Img<UnsignedByteType> downScaledImg = N5Utils.open(n5Input, downScaledDataset);

		final List<Double> shifts = computeShifts(downScaledImg);

		final N5Writer n5Output = new N5FSWriter(options.n5PathInput);
		final String invertedName = options.invert ? "_inverted" : "";
		final String outputDataset = options.n5DatasetInput + "_zAdjusted" + invertedName;
		final String fullScaleOutputDataset = outputDataset + "/s0";

		if (n5Output.exists(fullScaleOutputDataset)) {
			final String fullPath = options.n5PathInput + fullScaleOutputDataset;
			throw new IllegalArgumentException("Intensity-adjusted data set exists: " + fullPath);
		}

		n5Output.createDataset(fullScaleOutputDataset, dimensions, blockSize, DataType.UINT8, new GzipCompression());

		final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid);
		pGrid.foreach(block -> processAndSaveFullScaleBlock(options.n5PathInput,
															options.n5PathInput,
															fullScaleInputDataset,
															fullScaleOutputDataset,
															shifts,
															dimensions,
															blockSize,
															block,
															options.invert));
		n5Output.close();
		n5Input.close();

		final int[] downsampleFactors = parseCSIntArray(options.factors);
		if (downsampleFactors != null) {
			downsampleScalePyramid(sparkContext,
								   new N5PathSupplier(options.n5PathInput),
								   fullScaleOutputDataset,
								   outputDataset,
								   downsampleFactors);
		}

		sparkContext.close();
	}

	private static List<IntervalView<UnsignedByteType>> asZStack(final RandomAccessibleInterval<UnsignedByteType> rai) {
		final List<IntervalView<UnsignedByteType>> stack = new ArrayList<>((int) rai.dimension(2));
		for (int z = 0; z < rai.dimension(2); ++z) {
			stack.add(Views.hyperSlice(rai, 2, z));
		}
		return stack;
	}

	private static List<Double> computeShifts(RandomAccessibleInterval<UnsignedByteType> rai) {

		// create mask from pixels that have "content" throughout the stack
		final List<IntervalView<UnsignedByteType>> downScaledStack = asZStack(rai);
		final Img<UnsignedByteType> contentMask = ArrayImgs.unsignedBytes(downScaledStack.get(0).dimensionsAsLongArray());
		for (final UnsignedByteType pixel : contentMask) {
			pixel.set(1);
		}

		for (final IntervalView<UnsignedByteType> layer : downScaledStack) {
			LoopBuilder.setImages(layer, contentMask)
					.forEachPixel((a, b) -> {
						if (a.get() < LOWER_THRESHOLD || a.get() > UPPER_THRESHOLD) {
							b.set(0);
						}
					});
		}

		final AtomicLong maskSize = new AtomicLong(0);
		for (final UnsignedByteType pixel : contentMask) {
			if (pixel.get() == 1) {
				maskSize.incrementAndGet();
			}
		}

		// compute average intensity of content pixels in each layer
		final List<Double> contentAverages = new ArrayList<>(downScaledStack.size());
		for (final IntervalView<UnsignedByteType> layer : downScaledStack) {
			final AtomicLong sum = new AtomicLong(0);
			LoopBuilder.setImages(layer, contentMask)
					.forEachPixel((a, b) -> {
						if (b.get() == 1) {
							sum.addAndGet(a.get());
						}
					});
			contentAverages.add((double) sum.get() / maskSize.get());
		}

		// compute shifts for adjusting intensities relative to the first layer
		final double fixedPoint = contentAverages.get(0);
		return contentAverages.stream().map(a -> a - fixedPoint).collect(Collectors.toList());
	}

	private static RandomAccessibleInterval<UnsignedByteType> applyShifts(
			final RandomAccessibleInterval<UnsignedByteType> sourceRaw,
			final List<Double> shifts,
			final boolean invert) {

		final List<IntervalView<UnsignedByteType>> sourceStack = asZStack(sourceRaw);
		final List<RandomAccessibleInterval<UnsignedByteType>> convertedLayers = new ArrayList<>(sourceStack.size());

		for (int z = 0; z < sourceStack.size(); ++z) {
			final byte shift = (byte) Math.round(shifts.get(z));
			final RandomAccessibleInterval<UnsignedByteType> layer = sourceStack.get(z);

			RandomAccessibleInterval<UnsignedByteType> convertedLayer = Converters.convert(layer, (s, t) -> {
				// only shift foreground
				if (s.get() > 0) {
					t.set(s.get() - shift);
				} else {
					t.set(0);
				}
			}, new UnsignedByteType());

			convertedLayers.add(convertedLayer);
		}

		RandomAccessibleInterval<UnsignedByteType> target = Views.stack(convertedLayers);

		if (invert) {
			target = Converters.convertRAI(target, (in, out) -> out.set(255 - in.get()), new UnsignedByteType());
		}

		return target;
	}
}
