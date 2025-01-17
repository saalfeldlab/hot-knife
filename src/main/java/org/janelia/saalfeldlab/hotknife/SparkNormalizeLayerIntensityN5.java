package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.IntervalView;
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

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

import static org.janelia.saalfeldlab.hotknife.AbstractOptions.parseCSIntArray;
import static org.janelia.saalfeldlab.n5.spark.downsample.scalepyramid.N5ScalePyramidSpark.downsampleScalePyramid;


/**
 * Normalize layer intensity in N5 data set. The normalization is done by shifting the intensity of each layer relative
 * to the first layer. The shifts are computed based on the average intensity of a column of pixels that have "content"
 * throughout the stack (i.e. pixels that are not background).
 *
 * @param <T> pixel type; determined automatically from the input stack
 */
public class SparkNormalizeLayerIntensityN5<T extends NativeType<T> & NumericType<T>> {

	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path",
				required = true,
				usage = "N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5")
		private String n5Path = null;

		@Option(name = "--n5DatasetInput",
				required = true,
				usage = "Input N5 dataset, e.g. /render/slab_070_to_079/s075_m119_align_big_block_ic___20240308_072106")
		private String n5DatasetInput = null;

		@Option(name = "--n5DatasetOutput",
				required = true,
				usage = "Output N5 dataset, e.g. /render/slab_070_to_079/s075_m119_align_big_block_ic___20240308_072106_norm-layer")
		private String n5DatasetOutput = null;

		@Option(name = "--downsampleLevel",
				usage = "Take this downsample level for computing the intensity shifts.")
		private Integer downsampleLevel = 5;

		@Option(name = "--factors",
				usage = "If specified, generates a scale pyramid with given factors, e.g. 2,2,1")
		public String factors;

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

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final SparkNormalizeLayerIntensityN5.Options options = new SparkNormalizeLayerIntensityN5.Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final N5Reader n5reader = new N5FSReader(options.n5Path);

		if (n5reader.exists(options.n5DatasetOutput)) {
			throw new IllegalArgumentException("Normalized data set already exists: " + options.n5DatasetOutput);
		}

		final String fullScaleInputDataset = options.n5DatasetInput + "/s0";
		final DatasetAttributes attributes = n5reader.getDatasetAttributes(fullScaleInputDataset);

		final List<long[][]> grid = Grid.create(attributes.getDimensions(), attributes.getBlockSize());

		final N5Writer n5Writer = new N5FSWriter(options.n5Path);
		final String fullScaleOutputDataset = options.n5DatasetOutput + "/s0";

		final String downScaledDataset = options.n5DatasetInput + "/s" + options.downsampleLevel;
		final Img<UnsignedByteType> downScaledImg = N5Utils.open(n5reader, downScaledDataset);
		final List<Double> shifts = computeShifts(downScaledImg);

		n5Writer.createDataset(fullScaleOutputDataset, attributes);

		final SparkConf conf = new SparkConf().setAppName("SparkNormalizeLayerIntensityN5");
		try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {

			final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid);
			pGrid.foreach(gridBlock -> saveFullScaleBlock(options.n5Path,
														  fullScaleInputDataset,
														  fullScaleOutputDataset,
														  shifts,
														  attributes.getDimensions(),
														  attributes.getBlockSize(),
														  gridBlock));
			n5Writer.close();
			n5reader.close();

			final int[] downsampleFactors = parseCSIntArray(options.factors);
			if (downsampleFactors != null) {
				downsampleScalePyramid(sparkContext,
									   new N5PathSupplier(options.n5Path),
									   fullScaleOutputDataset,
									   options.n5DatasetOutput,
									   downsampleFactors);
			}
		}
	}


	private static List<Double> computeShifts(RandomAccessibleInterval<UnsignedByteType> rai) {

		// create mask from pixels that have "content" throughout the stack
		final List<IntervalView<UnsignedByteType>> downScaledStack = asZStack(rai);
		final Img<UnsignedByteType> contentMask = ArrayImgs.unsignedBytes(downScaledStack.get(0).dimensionsAsLongArray());
		for (final UnsignedByteType pixel : contentMask) {
			pixel.set(1);
		}

		final int lowerThreshold = 20;
		final int upperThreshold = 120;
		for (final IntervalView<UnsignedByteType> layer : downScaledStack) {
			LoopBuilder.setImages(layer, contentMask)
					.forEachPixel((a, b) -> {
						if (a.get() < lowerThreshold || a.get() > upperThreshold) {
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
			final List<Double> shifts ) {

		final List<IntervalView<UnsignedByteType>> sourceStack = asZStack(sourceRaw);
		final List<RandomAccessibleInterval<UnsignedByteType>> convertedLayers = new ArrayList<>(sourceStack.size());

		for (int z = 0; z < sourceStack.size(); ++z) {
			final byte shift = (byte) Math.round(shifts.get(z));
			final RandomAccessibleInterval<UnsignedByteType> layer = sourceStack.get(z);

			RandomAccessibleInterval<UnsignedByteType> convertedLayer = Converters.convert(layer, (s, t) -> {
				// only shift foreground
				if (s.get() > 0) {
					t.set( Math.max(0, Math.min( 255, s.get() - shift)));
				} else {
					t.set(0);
				}
			}, new UnsignedByteType());

			convertedLayers.add(convertedLayer);
		}

		return Views.stack(convertedLayers);
	}

	private static List<IntervalView<UnsignedByteType>> asZStack(final RandomAccessibleInterval<UnsignedByteType> rai) {
		final List<IntervalView<UnsignedByteType>> stack = new ArrayList<>((int) rai.dimension(2));
		for (int z = 0; z < rai.dimension(2); ++z) {
			stack.add(Views.hyperSlice(rai, 2, z));
		}
		return stack;
	}

	private static void saveFullScaleBlock(final String n5Path,
										   final String datasetName, // should be s0
										   final String datasetNameOutput,
										   final List<Double> shifts,
										   final long[] dimensions,
										   final int[] blockSize,
										   final long[][] gridBlock ) {

		final N5Writer n5Writer = new N5FSWriter(n5Path);

		final RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5Writer, datasetName);

		final RandomAccessibleInterval<UnsignedByteType> filteredSource = applyShifts(sourceRaw, shifts);

		final FinalInterval gridBlockInterval =
				Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
										gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);

		N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
								  n5Writer,
								  datasetNameOutput,
								  new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
								  gridBlock[2],
								  new UnsignedByteType());
	}
}
