package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

import mpicbg.models.AbstractAffineModel1D;
import mpicbg.models.AffineModel1D;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
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
 * Normalize layer intensity in N5 data set. The normalization is done by transforming the intensity of each layer
 * relative to the first layer. The transformations are computed based on a column of pixels that have "content"
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
				usage = "Take this downsample level for computing the intensity transformations. Note that that downsampling in z is not supported.")
		private Integer downsampleLevel = 5;

		@Option(name = "--factors",
				usage = "If specified, generates a scale pyramid with given factors, e.g. 2,2,1")
		public String factors;

		@Option(name = "--shift",
				usage = "Shift intensities based on the given mean: 'NONE', 'MEDIAN', or 'MEAN'")
		private ShiftType shift = ShiftType.MEAN;

		@Option(name = "--scale",
				usage = "Scale intensities based on the given method: 'NONE', 'FULL_RANGE', or 'GAUSS'")
		private ScaleType scale = ScaleType.NONE;

		@Option(name = "--cutoff",
				usage = "Cut this fraction of pixels on either side when computing the layer statistics (default: 0.03)")
		private double cutoff = 0.03;

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
		// Compute transformations based on downsampled input
		final List<AffineModel1D> transformations;
		try (final N5Reader n5reader = new N5FSReader(options.n5Path)) {
			final Img<T> downScaledImg = N5Utils.open(n5reader, downScaledInputDataset);
			transformations = computeTransformations(downScaledImg);
		}

		if (transformations.size() != attributes.getDimensions()[2]) {
			throw new IllegalArgumentException("Number of transformations does not match number of layers: " + transformations.size()
					+ " vs. " + attributes.getDimensions()[2] + ". Is the z-dimension downsampled?");
		}

		// Apply transformations to full scale input and save to output dataset
		try (final N5Writer n5Writer = new N5FSWriter(options.n5Path)) {
			n5Writer.createDataset(fullScaleOutputDataset, attributes);
		}

		final List<long[][]> grid = Grid.create(attributes.getDimensions(), attributes.getBlockSize());
		final SparkConf conf = new SparkConf().setAppName("SparkNormalizeLayerIntensityN5");

		try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {

			final JavaRDD<long[][]> parallelizedGrid = sparkContext.parallelize(grid);
			final Broadcast<List<? extends AbstractAffineModel1D<?>>> transformationsBroadcast = sparkContext.broadcast(transformations);
			parallelizedGrid.foreach(gridBlock -> saveFullScaleBlock(transformationsBroadcast.value(), gridBlock));

			final int[] downsampleFactors = parseCSIntArray(options.factors);
			if (downsampleFactors != null) {
				downsampleScalePyramid(sparkContext,
									   new N5PathSupplier(options.n5Path),
									   fullScaleOutputDataset,
									   options.n5DatasetOutput,
									   downsampleFactors);
			}
		}

		// Copy attributes and rebuild 'scales' attribute
		try (final N5Writer n5Writer = new N5FSWriter(options.n5Path)) {
			transferBaseAttributes(n5Writer);
		}
	}


	List<AffineModel1D> computeTransformations(
			final RandomAccessibleInterval<T> rai
	) {

		// Create mask of xy-pixels that have "content" throughout all z-layers
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

		final int nContentPixels = (int) zProjectedContentMask.stream()
				.filter(pixel -> pixel.getInteger() == 1)
				.count();

		// Compute key statistics for each layer
		final double[] layerPixels = new double[nContentPixels];
		final List<LayerStats> layerStats = new ArrayList<>(downScaledStack.size());
		for (final IntervalView<T> currentLayer : downScaledStack) {
			extractContentPixels(currentLayer, zProjectedContentMask, layerPixels);
			final LayerStats stats = LayerStats.from(layerPixels, options.cutoff);
			layerStats.add(stats);
		}

		// Compute intensity transformations for each layer (pure shift or shift + spread)
		final double targetShift = options.shift.from(layerStats.get(0));
		final double targetScale = layerStats.stream()
				.mapToDouble(options.scale::get)
				.max().orElseThrow(NoSuchElementException::new);

		final List<AffineModel1D> models = new ArrayList<>(downScaledStack.size());
		for (final LayerStats stats : layerStats) {
			final AffineModel1D model = new AffineModel1D();
			final double scale = targetScale / options.scale.get(stats);
			final double shift = targetShift - options.shift.from(stats) * scale;
			model.set(scale, shift);
			models.add(model);
		}

		return models;
	}

	private void extractContentPixels(final RandomAccessibleInterval<T> layer, final Img<T> mask, final double[] contentPixels) {
		final Cursor<T> layerCursor = Views.flatIterable(layer).localizingCursor();
		final RandomAccess<T> maskAccess = mask.randomAccess();
		int i = 0;
		while (layerCursor.hasNext()) {
			layerCursor.fwd();
			maskAccess.setPosition(layerCursor);
			if (maskAccess.get().getInteger() == 1) {
				contentPixels[i++] = layerCursor.get().getInteger();
			}
		}
	}

	private RandomAccessibleInterval<T> applyTransformations(
			final RandomAccessibleInterval<T> sourceRaw,
			final List<? extends AbstractAffineModel1D<?>> transformations
	) {
		final List<IntervalView<T>> sourceStack = asZStack(sourceRaw);
		final List<RandomAccessibleInterval<T>> convertedLayers = new ArrayList<>(sourceStack.size());
		final double[] pixel = new double[1];

		for (int z = 0; z < sourceStack.size(); ++z) {
			final AbstractAffineModel1D<?> transformation = transformations.get(z);
			final RandomAccessibleInterval<T> layer = sourceStack.get(z);

			RandomAccessibleInterval<T> convertedLayer = Converters.convert(layer, (s, t) -> {
				// only shift foreground
				if (s.getInteger() > 0) {
					pixel[0] = s.getInteger();
					transformation.applyInPlace(pixel);
					t.setInteger(typeHelper.clip((int) pixel[0]));
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

	private void saveFullScaleBlock(final List<? extends AbstractAffineModel1D<?>> transformations, final long[][] gridBlock) {

		final N5Writer n5Writer = new N5FSWriter(options.n5Path);
		final RandomAccessibleInterval<T> sourceRaw = N5Utils.open(n5Writer, fullScaleInputDataset);
		final RandomAccessibleInterval<T> filteredSource = applyTransformations(sourceRaw, transformations);

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


	/**
	 * Helper class to hold the median, mean, min and max (discarding some cutoff pixels on either side) of a layer.
	 */
	private static class LayerStats {
		public final double median;
		public final double mean;
		public final double std;
		public final double min;
		public final double max;

		private LayerStats(
				final double median,
				final double mean,
				final double std,
				final double min,
				final double max
		) {
			this.median = median;
			this.mean = mean;
			this.std = std;
			this.min = min;
			this.max = max;
		}

		public static LayerStats from(final double[] pixels, final double cutoff) {
			Arrays.sort(pixels);
			final int n = (int) Math.round(pixels.length * cutoff);
			final double median = pixels[pixels.length / 2];
			final double mean = Arrays.stream(pixels).average().orElse(0.0);
			final double std = Math.sqrt(Arrays.stream(pixels)
					.map(v -> (v - mean) * (v - mean))
					.average().orElse(0.0));
			final double min = pixels[n];
			final double max = pixels[pixels.length - n - 1];
			return new LayerStats(median, mean, std, min, max);
		}
	}

	/**
	 * Small helper enum to represent the type of mean used for normalization.
	 */
	private enum ShiftType {
		NONE(stats -> 0.0),
		MEDIAN(stats -> stats.median),
		MEAN(stats -> stats.mean);

		private final Function<LayerStats, Double> function;

		ShiftType(final Function<LayerStats, Double> function) {
			this.function = function;
		}

		public double from(final LayerStats stats) {
			return function.apply(stats);
		}
	}


	/**
	 * Small helper enum to represent the type of scaling used for normalization.
	 * 'GAUSS' scaling is what is used to normalize 8bit FIB-SEM data.
	 */
	private enum ScaleType {
		NONE(stats -> 1.0),
		FULL_RANGE(stats -> stats.max - stats.min),
		GAUSS(stats -> 3 * stats.std);

		private final Function<LayerStats, Double> function;

		ScaleType(final Function<LayerStats, Double> function) {
			this.function = function;
		}

		public double get(final LayerStats stats) {
			return function.apply(stats);
		}
	}


	/**
	 * Helper interface to abstract over the different pixel types (8bit and 16bit).
	 * Provides methods to create images, clip values, and check if a value is outside a threshold.
	 *
	 * @param <T> the pixel type
	 */
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

	/**
	 * Transfer base-level attributes from input dataset to output dataset. Since the downsampling
	 * factors might have changed, the 'scales' attribute is assembled afresh using the new factors
	 * and the actual number of scales in the output dataset. If the input dataset does not have a
	 * 'scales' attribute or if no factors are provided, the scales attribute is not written.
	 */
	public void transferBaseAttributes(final N5Writer n5Writer) {
		final Map<String, Class<?>> attributeTypes = n5Writer.listAttributes(options.n5DatasetInput);
		attributeTypes.forEach((name, type) -> {
			final Object value = n5Writer.getAttribute(options.n5DatasetInput, name, type);
			n5Writer.setAttribute(options.n5DatasetOutput, name, value);
		});

		// Handle 'scales' attribute separately since the downsampling factors might have changed
		// Read actual number of scales from the output and write factors to the base level attributes
		int nScales = n5Writer.list(options.n5DatasetOutput).length;
		final int[][] scales = new int[nScales][3];
		int xScale = 1, yScale = 1, zScale = 1;
		int[] factors = parseCSIntArray(options.factors);

		if (factors == null || n5Writer.getAttribute(options.n5DatasetInput, "scales", int[][].class) == null) {
			// Skip writing scales if no factors are provided
			return;
		}

		// Overwrite scales with the new factors
		for (int i = 0; i < nScales; ++i) {
			scales[i][0] = xScale;
			scales[i][1] = yScale;
			scales[i][2] = zScale;

			xScale *= factors[0];
			yScale *= factors[1];
			zScale *= factors[2];
		}
		n5Writer.setAttribute(options.n5DatasetOutput, "scales", scales);
	}
}
