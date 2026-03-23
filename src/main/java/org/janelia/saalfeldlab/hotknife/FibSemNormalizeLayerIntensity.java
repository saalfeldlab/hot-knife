package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

import mpicbg.models.AffineModel1D;

import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;


/**
 * Layer-wise intensity normalization for FIB-SEM data.
 * Uses all non-zero pixels without masking. Supports both shift and scale transformations.
 * <p>
 * Histogram computation is parallelized via Spark: the downsampled volume is split into
 * 3D chunks, each chunk produces per-layer histograms of pixel values, which are merged
 * across partitions and then on the driver.
 *
 * @param <T> pixel type (8bit or 16bit)
 */
public class FibSemNormalizeLayerIntensity<T extends NativeType<T> & IntegerType<T>> extends SparkNormalizeLayerIntensityN5<T> {

	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	protected static class Options extends SparkNormalizeLayerIntensityN5.Options implements Serializable {

		@Option(name = "--shift",
				usage = "Shift intensities based on the given statistic: 'NONE', 'MEDIAN', or 'MEAN'")
		private ShiftType shift = ShiftType.MEAN;

		@Option(name = "--scale",
				usage = "Scale intensities based on the given method: 'NONE', 'FULL_RANGE', or 'GAUSS'")
		private ScaleType scale = ScaleType.NONE;

		protected Options(final String[] args) {
			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				parsedSuccessfully = true;
			} catch (final Exception e) {
				e.printStackTrace(System.err);
				parser.printUsage(System.err);
			}
		}

		protected ShiftType shift() {
			return shift;
		}

		protected ScaleType scale() {
			return scale;
		}

		@Override
		public String toString() {
			return "FibSemNormalizeLayerIntensity.Options { " +
				   super.toString() +
				   ", shift=" + shift +
				   ", scale=" + scale +
				   " }";
		}
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);
		if (!options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final DatasetAttributes attributes = options.readDatasetAttributes();

		if (attributes.getDataType() == DataType.UINT8) {
			new FibSemNormalizeLayerIntensity<>(options, attributes, new ByteHelper()).run();
		} else if (attributes.getDataType() == DataType.UINT16) {
			new FibSemNormalizeLayerIntensity<>(options, attributes, new ShortHelper()).run();
		} else {
			throw new IllegalArgumentException("Unsupported data type: " + attributes.getDataType());
		}
	}

	private final Options fibSemOptions;

	private FibSemNormalizeLayerIntensity(final Options options, final DatasetAttributes attributes, final TypeHelper<T> typeHelper) {
		super(options, attributes, typeHelper);
		this.fibSemOptions = options;
	}

	@Override
	protected List<AffineModel1D> computeTransformations(
			final JavaSparkContext sparkContext,
			final DatasetAttributes downscaledAttributes) {

		final int nLayers = (int) downscaledAttributes.getDimensions()[2];

		logMessage("computeTransformations: entry, nLayers="  + nLayers);

		final LayerHistogram[] layerHistograms = computeHistograms(sparkContext, downscaledAttributes, nLayers);

		return histogramsToTransformations(layerHistograms);
	}

	private LayerHistogram[] computeHistograms(
			final JavaSparkContext sparkContext,
			final DatasetAttributes downscaledAttributes,
			final int nLayers) {


		logMessage("computeHistograms: entry, nLayers="  + nLayers);

		final List<long[][]> grid = Grid.create(
				downscaledAttributes.getDimensions(), downscaledAttributes.getBlockSize());

		final String n5Path = options.n5Path;
		final String dsInputDataset = downScaledInputDataset;
		final double cutoff = fibSemOptions.cutoff();

		final List<LayerHistogram[]> partitionResults = sparkContext.parallelize(grid)
				.mapPartitions(blocks -> {
					final N5Reader n5 = N5Util.createN5Reader(n5Path);
					final RandomAccessibleInterval<T> img = N5Utils.open(n5, dsInputDataset);

					final LayerHistogram[] merged = new LayerHistogram[nLayers];

					while (blocks.hasNext()) {
						processPixelBlock(img, blocks.next(), merged, cutoff);
					}

					return Collections.singletonList(merged).iterator();
				})
				.collect();

		// Merge partition results on driver
		final LayerHistogram[] globalHistograms = new LayerHistogram[nLayers];
		for (final LayerHistogram[] partResult : partitionResults) {
			for (int i = 0; i < nLayers; i++) {
				if (partResult[i] != null) {
					if (globalHistograms[i] == null) {
						globalHistograms[i] = partResult[i];
					} else {
						globalHistograms[i] = globalHistograms[i].absorb(partResult[i]);
					}
				}
			}
		}

		logMessage("computeHistograms: exit, returning "  + globalHistograms.length + " histograms");

		return globalHistograms;
	}

	private static <T extends NativeType<T> & IntegerType<T>> void processPixelBlock(
			final RandomAccessibleInterval<T> img,
			final long[][] gridBlock,
			final LayerHistogram[] merged,
			final double cutoff) {

		final int zStart = (int) gridBlock[0][2];
		final int zSize = (int) gridBlock[1][2];

		final FinalInterval interval = Intervals.createMinSize(
				gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
				gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);
		final RandomAccessibleInterval<T> chunk = Views.interval(img, interval);
		final long zMin = chunk.min(2);

		for (int z = 0; z < zSize; z++) {
			final int globalZ = zStart + z;

			final RandomAccessibleInterval<T> layer = Views.hyperSlice(chunk, 2, zMin + z);

			final List<Double> pixels = new ArrayList<>();
			final Cursor<T> cursor = Views.flatIterable(layer).cursor();

			while (cursor.hasNext()) {
				final int val = cursor.next().getInteger();
				if (val > 0) {
					pixels.add((double) val);
				}
			}

			if (!pixels.isEmpty()) {
				final LayerHistogram blockHistogram = LayerHistogram.from(pixels, cutoff);
				if (merged[globalZ] == null) {
					merged[globalZ] = blockHistogram;
				} else {
					merged[globalZ].absorb(blockHistogram);
				}
			}
		}
	}

	private List<AffineModel1D> histogramsToTransformations(final LayerHistogram[] layerHistograms) {

		logMessage("histogramsToTransformations: entry");

		// Print diagnostic output
		System.out.println("Computing layer statistics...");
		System.out.println("layer\tmedian\tmean\tstd\tmin\tmax");
		for (int z = 0; z < layerHistograms.length; z++) {
			final LayerHistogram histogram = layerHistograms[z];
			if (histogram != null) {
				System.out.printf("%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f%n",
								  z, histogram.median(), histogram.mean(), histogram.std(), histogram.min(), histogram.max());
			} else {
				System.out.printf("%d\t-\t-\t-\t-\t-%n", z);
			}
		}

		// Precompute scale and shift values
		final double[] scaleValues = new double[layerHistograms.length];
		final double[] shiftValues = new double[layerHistograms.length];
		for (int i = 0; i < layerHistograms.length; i++) {
			if (layerHistograms[i] != null) {
				scaleValues[i] = fibSemOptions.scale().get(layerHistograms[i]);
				shiftValues[i] = fibSemOptions.shift().from(layerHistograms[i]);
			}
		}

		// Determine the target shift and scale based on the layer with the maximum scale
		final int maxScaleIndex = IntStream.range(0, scaleValues.length)
				.boxed()
				.max(Comparator.comparingDouble(i -> scaleValues[i]))
				.orElseThrow(NoSuchElementException::new);
		final double targetShift = shiftValues[maxScaleIndex];
		final double targetScale = scaleValues[maxScaleIndex];
		System.out.printf("Target layer: %d, targetShift: %.2f, targetScale: %.2f%n",
						  maxScaleIndex, targetShift, targetScale);

		// Compute intensity transformations for each layer
		final List<AffineModel1D> models = new ArrayList<>(layerHistograms.length);
		for (int i = 0; i < layerHistograms.length; i++) {
			final AffineModel1D model = new AffineModel1D();
			final double scale = targetScale / scaleValues[i];
			final double shift = targetShift - shiftValues[i] * scale;
			model.set(scale, shift);
			models.add(model);
		}

		logMessage("histogramsToTransformations: exit, returning " + models.size() + " models");

		return models;
	}

	private static void logMessage(final String message) {
		org.janelia.saalfeldlab.hotknife.util.Util.logMessage(FibSemNormalizeLayerIntensity.class.getName(), message);
	}
}
