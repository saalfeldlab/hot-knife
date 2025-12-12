package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import mpicbg.models.AffineModel1D;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;


/**
 * Layer-wise intensity normalization for multi-SEM data with non-flat substrate boundaries.
 * Computes intensity shifts between adjacent layers, using only pixels where both layers
 * have valid tissue (within threshold). This naturally handles non-flat substrate boundaries
 * by excluding pixel pairs where one is substrate.
 * <p>
 * Only supports 8-bit data and shift transformations (no scaling).
 */
public class MultiSemNormalizeLayerIntensity extends SparkNormalizeLayerIntensityN5<UnsignedByteType> {

	/**
	 * Aggregation method for computing the shift between adjacent layers.
	 */
	public enum AggregationType {
		MEDIAN,
		MEAN
	}

	@SuppressWarnings({"FieldMayBeFinal", "unused", "FieldCanBeLocal"})
	public static class Options extends SparkNormalizeLayerIntensityN5.Options implements Serializable {

		@Option(name = "--aggregation",
				usage = "Aggregation method for shift computation: 'MEDIAN' (default, more robust) or 'MEAN'")
		private AggregationType aggregation = AggregationType.MEDIAN;

		@Option(name = "--lowerThreshold",
				usage = "Lower intensity threshold for valid tissue pixels (default: 20)")
		private int lowerThreshold = 20;

		@Option(name = "--upperThreshold",
				usage = "Upper intensity threshold for valid tissue pixels (default: 200)")
		private int upperThreshold = 200;

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

		public AggregationType aggregation() {
			return aggregation;
		}

		public int lowerThreshold() {
			return lowerThreshold;
		}

		public int upperThreshold() {
			return upperThreshold;
		}
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);
		if (!options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final DatasetAttributes attributes = options.readDatasetAttributes();

		if (attributes.getDataType() != DataType.UINT8) {
			throw new IllegalArgumentException("MultiSemNormalizeLayerIntensity only supports 8-bit data, found: " + attributes.getDataType());
		}

		new MultiSemNormalizeLayerIntensity(options, attributes).run();
	}

	private final Options multiSemOptions;

	private MultiSemNormalizeLayerIntensity(final Options options, final DatasetAttributes attributes) {
		super(options, attributes, new ByteHelper());
		this.multiSemOptions = options;
	}

	@Override
	protected List<AffineModel1D> computeTransformations(final RandomAccessibleInterval<UnsignedByteType> rai) {
		final List<IntervalView<UnsignedByteType>> stack = asZStack(rai);
		final List<AffineModel1D> models = new ArrayList<>(stack.size());

		// First layer has identity transform (reference)
		final AffineModel1D identity = new AffineModel1D();
		identity.set(1.0, 0.0);
		models.add(identity);

		double cumulativeShift = 0.0;

		// Compute shifts between adjacent layers
		for (int z = 0; z < stack.size() - 1; z++) {
			final IntervalView<UnsignedByteType> currentLayer = stack.get(z);
			final IntervalView<UnsignedByteType> nextLayer = stack.get(z + 1);

			// Collect valid shifts between adjacent layers
			final List<Double> shifts = new ArrayList<>();
			final Cursor<UnsignedByteType> cursorCurrent = Views.flatIterable(currentLayer).cursor();
			final Cursor<UnsignedByteType> cursorNext = Views.flatIterable(nextLayer).cursor();

			while (cursorCurrent.hasNext()) {
				final int valCurrent = cursorCurrent.next().getInteger();
				final int valNext = cursorNext.next().getInteger();

				// Only record shift if BOTH pixels are within threshold (valid tissue)
				if (isWithinThreshold(valCurrent) && isWithinThreshold(valNext)) {
					shifts.add((double) (valNext - valCurrent));
				}
			}

			// Use LayerStats for robust aggregation with cutoff
			final double layerShift = aggregateShifts(shifts);
			cumulativeShift += layerShift;

			// Create model: new_val = old_val - cumulativeShift (to normalize to layer 0)
			final AffineModel1D model = new AffineModel1D();
			model.set(1.0, -cumulativeShift);
			models.add(model);
		}

		return models;
	}

	private boolean isWithinThreshold(final int value) {
		return value >= multiSemOptions.lowerThreshold() && value <= multiSemOptions.upperThreshold();
	}

	private double aggregateShifts(final List<Double> shifts) {
		if (shifts.isEmpty()) {
			return 0.0;
		}

		// Use LayerStats for robust aggregation with cutoff (clips outliers)
		final LayerStats stats = LayerStats.from(shifts, multiSemOptions.cutoff());

		if (multiSemOptions.aggregation() == AggregationType.MEDIAN) {
			return stats.median;
		} else {
			return stats.mean;
		}
	}
}
