package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

import mpicbg.models.AffineModel1D;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;


/**
 * Layer-wise intensity normalization for FIB-SEM data.
 * Uses all non-zero pixels without masking. Supports both shift and scale transformations.
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
	protected List<AffineModel1D> computeTransformations(final RandomAccessibleInterval<T> rai) {
		final List<IntervalView<T>> stack = asZStack(rai);
		final List<LayerStats> layerStats = new ArrayList<>(stack.size());

		// Compute statistics for each layer using all non-zero pixels
		System.out.println("Computing layer statistics...");
		System.out.println("layer\tmedian\tmean\tstd\tmin\tmax");
		for (int z = 0; z < stack.size(); z++) {
			final List<Double> pixels = new ArrayList<>();
			for (final T pixel : Views.flatIterable(stack.get(z))) {
				if (pixel.getInteger() > 0) {
					pixels.add((double) pixel.getInteger());
				}
			}
			final LayerStats stats = LayerStats.from(pixels, fibSemOptions.cutoff());
			layerStats.add(stats);
			System.out.printf("%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f%n",
					z, stats.median, stats.mean, stats.std, stats.min, stats.max);
		}

		// Determine the target shift and scale based on the layer with the maximum scale
		// which maximizes the expressive range while minimizing the risk of clipping
		final int maxScaleIndex = IntStream.range(0, layerStats.size())
				.boxed()
				.max(Comparator.comparingDouble(i -> fibSemOptions.scale().get(layerStats.get(i))))
				.orElseThrow(NoSuchElementException::new);
		final double targetShift = fibSemOptions.shift().from(layerStats.get(maxScaleIndex));
		final double targetScale = fibSemOptions.scale().get(layerStats.get(maxScaleIndex));
		System.out.printf("Target layer: %d, targetShift: %.2f, targetScale: %.2f%n",
				maxScaleIndex, targetShift, targetScale);

		// Compute intensity transformations for each layer
		final List<AffineModel1D> models = new ArrayList<>(stack.size());
		for (final LayerStats stats : layerStats) {
			final AffineModel1D model = new AffineModel1D();
			final double scale = targetScale / fibSemOptions.scale().get(stats);
			final double shift = targetShift - fibSemOptions.shift().from(stats) * scale;
			model.set(scale, shift);
			models.add(model);
		}

		return models;
	}
}
