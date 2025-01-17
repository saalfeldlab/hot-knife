package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.IntervalView;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.N5PathSupplier;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
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
 * @param <T> pixel type, determined automatically from the input stack (either 8bit or 16bit)
 */
public class SparkNormalizeLayerIntensityN5<T extends NativeType<T> & IntegerType<T>> implements Serializable {

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

		final DatasetAttributes attributes;
		try (final N5Reader n5reader = new N5FSReader(options.n5Path)) {
			if (n5reader.exists(options.n5DatasetOutput)) {
				throw new IllegalArgumentException("Normalized data set already exists: " + options.n5DatasetOutput);
			}

			final String fullScaleInputDataset = options.n5DatasetInput + "/s0";
			attributes = n5reader.getDatasetAttributes(fullScaleInputDataset);
		}

		if (attributes.getDataType() == DataType.UINT8) {
			new SparkNormalizeLayerIntensityN5<>(options, attributes, new ByteHelper()).run();
		} else if (attributes.getDataType() == DataType.UINT16) {
			new SparkNormalizeLayerIntensityN5<>(options, attributes, new ShortHelper()).run();
		} else {
			throw new IllegalArgumentException("Unsupported data type: " + attributes.getDataType());
		}
	}


	private final String fullScaleInputDataset;
	private final String downScaledInputDataset;
	private final String fullScaleOutputDataset;
	private final Options options;
	private final DatasetAttributes attributes;
	private final TypeHelper<T> typeHelper;


	private SparkNormalizeLayerIntensityN5(final Options options, final DatasetAttributes attributes, final TypeHelper<T> typeHelper) {
		fullScaleInputDataset = options.n5DatasetInput + "/s0";
		fullScaleOutputDataset = options.n5DatasetOutput + "/s0";
		downScaledInputDataset = options.n5DatasetInput + "/s" + options.downsampleLevel;
		this.options = options;
		this.attributes = attributes;
		this.typeHelper = typeHelper;
	}

	private void run() throws IOException {

		final List<Double> shifts;
		try (final N5Reader n5reader = new N5FSReader(options.n5Path)) {
			final Img<T> downScaledImg = N5Utils.open(n5reader, downScaledInputDataset);
			shifts = computeShifts(downScaledImg);
		}

		try (final N5Writer n5Writer = new N5FSWriter(options.n5Path)) {
			n5Writer.createDataset(fullScaleOutputDataset, attributes);
		}

		final List<long[][]> grid = Grid.create(attributes.getDimensions(), attributes.getBlockSize());
		final SparkConf conf = new SparkConf().setAppName("SparkNormalizeLayerIntensityN5");

		try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {

			final JavaRDD<long[][]> parallelizedGrid = sparkContext.parallelize(grid);
			final Broadcast<List<Double>> shiftsBroadcast = sparkContext.broadcast(shifts);
			parallelizedGrid.foreach(gridBlock -> saveFullScaleBlock(shiftsBroadcast.value(), gridBlock));

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

	private List<Double> computeShifts(RandomAccessibleInterval<T> rai) {

		// create mask from pixels that have "content" throughout the stack
		final List<IntervalView<T>> downScaledStack = asZStack(rai);
		final Img<T> zProjectedContentMask = typeHelper.createImg(downScaledStack.get(0).dimensionsAsLongArray());
		for (final T pixel : zProjectedContentMask) {
			pixel.setOne();
		}

		for (final IntervalView<T> layer : downScaledStack) {
			LoopBuilder.setImages(layer, zProjectedContentMask)
					.forEachPixel((a, b) -> {
						if (typeHelper.isOutsideThreshold(a.getInteger())) {
							b.setZero();
						}
					});
		}

		// compute average intensity of content pixels in each layer
		final List<Double> contentAverages = new ArrayList<>(downScaledStack.size());
		for (final IntervalView<T> layer : downScaledStack) {
			final DoubleSummaryStatistics stats = new DoubleSummaryStatistics();
			LoopBuilder.setImages(layer, zProjectedContentMask)
					.forEachPixel((a, b) -> {
						if (b.getInteger() == 1) {
							stats.accept(a.getInteger());
						}
					});
			contentAverages.add(stats.getAverage());
		}

		// compute shifts for adjusting intensities relative to the first layer
		final double fixedPoint = contentAverages.get(0);
		return contentAverages.stream().map(a -> a - fixedPoint).collect(Collectors.toList());
	}

	private RandomAccessibleInterval<T> applyShifts(
			final RandomAccessibleInterval<T> sourceRaw,
			final List<Double> shifts
	) {
		final List<IntervalView<T>> sourceStack = asZStack(sourceRaw);
		final List<RandomAccessibleInterval<T>> convertedLayers = new ArrayList<>(sourceStack.size());

		for (int z = 0; z < sourceStack.size(); ++z) {
			final double shift = shifts.get(z);
			final RandomAccessibleInterval<T> layer = sourceStack.get(z);

			RandomAccessibleInterval<T> convertedLayer = Converters.convert(layer, (s, t) -> {
				// only shift foreground
				if (s.getInteger() > 0) {
					t.setInteger(typeHelper.clip((int) Math.round(s.getInteger() - shift)));
				} else {
					t.setZero();
				}
			}, typeHelper.getType());

			convertedLayers.add(convertedLayer);
		}

		return Views.stack(convertedLayers);
	}

	private List<IntervalView<T>> asZStack(final RandomAccessibleInterval<T> rai) {
		final List<IntervalView<T>> stack = new ArrayList<>((int) rai.dimension(2));
		for (int z = 0; z < rai.dimension(2); ++z) {
			stack.add(Views.hyperSlice(rai, 2, z));
		}
		return stack;
	}

	private void saveFullScaleBlock(final List<Double> shifts, final long[][] gridBlock) {

		final N5Writer n5Writer = new N5FSWriter(options.n5Path);
		final RandomAccessibleInterval<T> sourceRaw = N5Utils.open(n5Writer, fullScaleInputDataset);
		final RandomAccessibleInterval<T> filteredSource = applyShifts(sourceRaw, shifts);

		final FinalInterval gridBlockInterval =
				Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
										gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);

		N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
								  n5Writer,
								  fullScaleOutputDataset,
								  attributes,
								  gridBlock[2],
								  typeHelper.getType());
	}


	private interface TypeHelper<T extends NativeType<T> & IntegerType<T>> extends Serializable {
		T getType();
		Img<T> createImg(final long[] dimensions);
		int clip(final int value);
		boolean isOutsideThreshold(final int value);
	}

	private static class ByteHelper implements TypeHelper<UnsignedByteType> {
		@Override
		public UnsignedByteType getType() {
			return new UnsignedByteType();
		}

		@Override
		public Img<UnsignedByteType> createImg(final long[] dimensions) {
			return ArrayImgs.unsignedBytes(dimensions);
		}

		@Override
		public int clip(final int value) {
			return UnsignedByteType.getCodedSignedByteChecked(value);
		}

		@Override
		public boolean isOutsideThreshold(final int value) {
			return (value < 20 || value > 200);
		}
	}

	private static class ShortHelper implements TypeHelper<UnsignedShortType> {
		@Override
		public UnsignedShortType getType() {
			return new UnsignedShortType();
		}

		@Override
		public Img<UnsignedShortType> createImg(final long[] dimensions) {
			return ArrayImgs.unsignedShorts(dimensions);
		}

		@Override
		public int clip(final int value) {
			return UnsignedShortType.getCodedSignedShortChecked(value);
		}

		@Override
		public boolean isOutsideThreshold(final int value) {
			return (value < 5000 || value > 60000);
		}
	}
}
