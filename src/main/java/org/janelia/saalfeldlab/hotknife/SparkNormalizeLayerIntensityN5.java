package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import mpicbg.models.AbstractAffineModel1D;
import mpicbg.models.AffineModel1D;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.N5PathSupplier;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.Option;

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import static org.janelia.saalfeldlab.hotknife.AbstractOptions.parseCSIntArray;
import static org.janelia.saalfeldlab.n5.spark.downsample.scalepyramid.N5ScalePyramidSpark.downsampleScalePyramid;


/**
 * Abstract base class for layer-wise intensity normalization in N5 data sets.
 * Subclasses implement different normalization strategies for different imaging modalities.
 *
 * @param <T> pixel type, determined automatically from the input stack (either 8bit or 16bit)
 */
public abstract class SparkNormalizeLayerIntensityN5<T extends NativeType<T> & IntegerType<T>> implements Serializable {

	/**
	 * Base options class containing common parameters for all normalization strategies.
	 * Subclasses should extend this with their specific options.
	 */
	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path",
				required = true,
				usage = "N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5")
		protected String n5Path = null;

		@Option(name = "--n5DatasetInput",
				required = true,
				usage = "Input N5 dataset, e.g. /render/slab_070_to_079/s075_m119_align_big_block_ic___20240308_072106")
		protected String n5DatasetInput = null;

		@Option(name = "--n5DatasetOutput",
				required = true,
				usage = "Output N5 dataset, e.g. /render/slab_070_to_079/s075_m119_align_big_block_ic___20240308_072106_norm-layer")
		protected String n5DatasetOutput = null;

		@Option(name = "--downsampleLevel",
				usage = "Take this downsample level for computing the intensity transformations. Note that that downsampling in z is not supported.")
		protected Integer downsampleLevel = 5;

		@Option(name = "--factors",
				usage = "If specified, generates a scale pyramid with given factors, e.g. 2,2,1")
		protected String factors;

		@Option(name = "--cutoff",
				usage = "Cut this fraction of pixels on either side when computing the layer statistics (default: 0.03)")
		protected double cutoff = 0.03;

		protected Options() {
			// Default constructor for subclasses
		}

		public double cutoff() {
			return cutoff;
		}

		public String n5Path() {
			return n5Path;
		}

		/**
		 * Read and validate dataset attributes from N5.
		 * Checks that output doesn't exist and input has valid attributes.
		 *
		 * @return the dataset attributes for the full scale input
		 * @throws IOException if N5 access fails
		 * @throws IllegalArgumentException if output exists or input has no attributes
		 */
		public DatasetAttributes readDatasetAttributes() throws IOException {
			try (final N5Reader n5reader = N5Util.createN5Reader(n5Path)) {
				if (n5reader.exists(n5DatasetOutput)) {
					throw new IllegalArgumentException("Normalized data set already exists: " + n5DatasetOutput);
				}

				final String fullScaleInputDataset = n5DatasetInput + "/s0";
				final DatasetAttributes attributes = n5reader.getDatasetAttributes(fullScaleInputDataset);
				if (attributes == null) {
					throw new IllegalArgumentException("no attributes found in " + n5Path + fullScaleInputDataset);
				}
				return attributes;
			}
		}

		public String n5DatasetInput() {
			return n5DatasetInput;
		}

		public String n5DatasetOutput() {
			return n5DatasetOutput;
		}

		public Integer downsampleLevel() {
			return downsampleLevel;
		}
	}



	protected final String fullScaleInputDataset;
	protected final String downScaledInputDataset;
	protected final String fullScaleOutputDataset;
	protected final Options options;
	protected final DatasetAttributes attributes;
	protected final TypeHelper<T> typeHelper;


	protected SparkNormalizeLayerIntensityN5(final Options options, final DatasetAttributes attributes, final TypeHelper<T> typeHelper) {
		fullScaleInputDataset = options.n5DatasetInput + "/s0";
		fullScaleOutputDataset = options.n5DatasetOutput + "/s0";
		downScaledInputDataset = options.n5DatasetInput + "/s" + options.downsampleLevel;
		this.options = options;
		this.attributes = attributes;
		this.typeHelper = typeHelper;
	}

	protected void run() throws IOException {
		// Compute transformations based on downsampled input
		final List<AffineModel1D> transformations;
		try (final N5Reader n5reader = N5Util.createN5Reader(options.n5Path)) {
			final Img<T> downScaledImg = N5Utils.open(n5reader, downScaledInputDataset);
			transformations = computeTransformations(downScaledImg);
		}

		if (transformations.size() != attributes.getDimensions()[2]) {
			throw new IllegalArgumentException("Number of transformations does not match number of layers: " + transformations.size()
					+ " vs. " + attributes.getDimensions()[2] + ". Is the z-dimension downsampled?");
		}

		// Apply transformations to full scale input and save to output dataset
		try (final N5Writer n5Writer = N5Util.createN5Writer(options.n5Path)) {
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
		try (final N5Writer n5Writer = N5Util.createN5Writer(options.n5Path)) {
			transferBaseAttributes(n5Writer);
		}
	}


	/**
	 * Compute the intensity transformations for each layer.
	 * Subclasses implement different strategies for computing these transformations.
	 *
	 * @param rai the downsampled input image
	 * @return list of affine models, one per layer
	 */
	protected abstract List<AffineModel1D> computeTransformations(final RandomAccessibleInterval<T> rai);

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

	protected List<IntervalView<T>> asZStack(final RandomAccessibleInterval<T> rai) {
		final List<IntervalView<T>> stack = new ArrayList<>((int) rai.dimension(2));
		for (int z = 0; z < rai.dimension(2); ++z) {
			stack.add(Views.hyperSlice(rai, 2, z));
		}
		return stack;
	}

	private void saveFullScaleBlock(final List<? extends AbstractAffineModel1D<?>> transformations, final long[][] gridBlock) {

		final N5Writer n5Writer = N5Util.createN5Writer(options.n5Path);
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
	 * Histogram-based statistics for a layer. Holds an integer histogram and computes
	 * rank and accumulated statistics on demand, applying a cutoff to discard outliers.
	 * Can be merged with other instances for parallel computation.
	 */
	protected static class LayerHistogram {
		private final long[] counts;
		private final int offset;  // value v is stored at index v + offset
		private final long totalCount;
		private final double cutoff;

		private LayerHistogram(final long[] counts, final int offset, final long totalCount, final double cutoff) {
			this.counts = counts;
			this.offset = offset;
			this.totalCount = totalCount;
			this.cutoff = cutoff;
		}

		/**
		 * Build a histogram from a list of integer-valued doubles.
		 */
		public static LayerHistogram from(final List<Double> values, final double cutoff) {
			if (values.isEmpty()) {
				return new LayerHistogram(new long[0], 0, 0, cutoff);
			}

			int minVal = Integer.MAX_VALUE;
			int maxVal = Integer.MIN_VALUE;
			for (final double v : values) {
				final int iv = (int) v;
				if (iv < minVal) minVal = iv;
				if (iv > maxVal) maxVal = iv;
			}

			final int offset = -minVal;
			final long[] counts = new long[maxVal - minVal + 1];
			for (final double v : values) {
				counts[(int) v + offset]++;
			}

			return new LayerHistogram(counts, offset, values.size(), cutoff);
		}

		public LayerHistogram merge(final LayerHistogram other) {
			if (totalCount == 0) return other;
			if (other.totalCount == 0) return this;

			final int thisMin = -offset;
			final int thisMax = counts.length - 1 - offset;
			final int otherMin = -other.offset;
			final int otherMax = other.counts.length - 1 - other.offset;

			final int newMin = Math.min(thisMin, otherMin);
			final int newMax = Math.max(thisMax, otherMax);
			final int newOffset = -newMin;
			final long[] newCounts = new long[newMax - newMin + 1];

			for (int i = 0; i < counts.length; i++) {
				newCounts[i - offset + newOffset] += counts[i];
			}
			for (int i = 0; i < other.counts.length; i++) {
				newCounts[i - other.offset + newOffset] += other.counts[i];
			}

			return new LayerHistogram(newCounts, newOffset, totalCount + other.totalCount, cutoff);
		}

		public double median() {
			return valueAtRank(totalCount / 2);
		}

		public double min() {
			final long start = Math.round(totalCount * cutoff);
			return valueAtRank(start);
		}

		public double max() {
			final long start = Math.round(totalCount * cutoff);
			final long end = totalCount - start;
			return valueAtRank(end - 1);
		}

		public double mean() {
			final long start = Math.round(totalCount * cutoff);
			final long end = totalCount - start;

			double sum = 0;
			long cumulative = 0;
			long clippedCount = 0;

			for (int i = 0; i < counts.length; i++) {
				if (counts[i] == 0) continue;
				final long prevCumulative = cumulative;
				cumulative += counts[i];
				final double value = i - offset;

				// How many of this bin's entries fall within [start, end)?
				final long binStart = Math.max(start, prevCumulative);
				final long binEnd = Math.min(end, cumulative);
				if (binEnd > binStart) {
					final long n = binEnd - binStart;
					sum += value * n;
					clippedCount += n;
				}
			}

			return clippedCount > 0 ? sum / clippedCount : 0.0;
		}

		public double std() {
			final double m = mean();
			final long start = Math.round(totalCount * cutoff);
			final long end = totalCount - start;

			double sumSqDiff = 0;
			long cumulative = 0;
			long clippedCount = 0;

			for (int i = 0; i < counts.length; i++) {
				if (counts[i] == 0) continue;
				final long prevCumulative = cumulative;
				cumulative += counts[i];
				final double value = i - offset;

				final long binStart = Math.max(start, prevCumulative);
				final long binEnd = Math.min(end, cumulative);
				if (binEnd > binStart) {
					final long n = binEnd - binStart;
					sumSqDiff += (value - m) * (value - m) * n;
					clippedCount += n;
				}
			}

			return clippedCount > 0 ? Math.sqrt(sumSqDiff / clippedCount) : 0.0;
		}

		private double valueAtRank(final long rank) {
			long cumulative = 0;
			for (int i = 0; i < counts.length; i++) {
				cumulative += counts[i];
				if (cumulative > rank) {
					return i - offset;
				}
			}
			// Return the last non-empty bin
			return counts.length - 1 - offset;
		}
	}

	/**
	 * Small helper enum to represent the type of mean used for normalization.
	 */
	protected enum ShiftType {
		NONE(h -> 0.0),
		MEDIAN(h -> h.median()),
		MEAN(h -> h.mean());

		private final Function<LayerHistogram, Double> function;

		ShiftType(final Function<LayerHistogram, Double> function) {
			this.function = function;
		}

		public double from(final LayerHistogram histogram) {
			return function.apply(histogram);
		}
	}


	/**
	 * Small helper enum to represent the type of scaling used for normalization.
	 * 'GAUSS' scaling is what is used to normalize 8bit FIB-SEM data.
	 */
	protected enum ScaleType {
		NONE(h -> 1.0),
		FULL_RANGE(h -> h.max() - h.min()),
		GAUSS(h -> 4 * h.std());

		private final Function<LayerHistogram, Double> function;

		ScaleType(final Function<LayerHistogram, Double> function) {
			this.function = function;
		}

		public double get(final LayerHistogram histogram) {
			return function.apply(histogram);
		}
	}


	/**
	 * Helper interface to abstract over the different pixel types (8bit and 16bit).
	 * Provides methods to create images, clip values, and check if a value is outside a threshold.
	 *
	 * @param <T> the pixel type
	 */
	protected interface TypeHelper<T extends NativeType<T> & IntegerType<T>> extends Serializable {
		T getType();

		int clip(final int value);
	}

	protected static class ByteHelper implements TypeHelper<UnsignedByteType> {
		@Override
		public UnsignedByteType getType() {
			return new UnsignedByteType();
		}

		@Override
		public int clip(final int value) {
			return UnsignedByteType.getCodedSignedByteChecked(value);
		}

	}

	protected static class ShortHelper implements TypeHelper<UnsignedShortType> {
		@Override
		public UnsignedShortType getType() {
			return new UnsignedShortType();
		}

		@Override
		public int clip(final int value) {
			return UnsignedShortType.getCodedSignedShortChecked(value);
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
