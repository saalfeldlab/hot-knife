package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

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
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;


/**
 * Layer-wise intensity normalization for multi-SEM data with non-flat substrate boundaries.
 * Computes intensity shifts between adjacent layers, using only pixels where both layers
 * have valid tissue (within threshold). This naturally handles non-flat substrate boundaries
 * by excluding pixel pairs where one is substrate.
 * <p>
 * Histogram computation is parallelized via Spark: the downsampled volume is split into
 * 3D chunks, each chunk produces per-layer-pair histograms, which are merged across
 * partitions and then on the driver.
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
	protected List<AffineModel1D> computeTransformations(
			final JavaSparkContext sparkContext,
			final DatasetAttributes downscaledAttributes) {

		final int nLayers = (int) downscaledAttributes.getDimensions()[2];
		final int nLayerPairs = nLayers - 1;

		logMessage("computeTransformations: entry, nLayerPairs="  + nLayerPairs);

		final LayerHistogram[] globalHistograms = computeHistograms(
				sparkContext, downscaledAttributes, nLayerPairs);

		return histogramsToTransformations(globalHistograms);
	}

	private LayerHistogram[] computeHistograms(
			final JavaSparkContext sparkContext,
			final DatasetAttributes downscaledAttributes,
			final int nLayerPairs) {

		logMessage("computeHistograms: entry, nLayerPairs="  + nLayerPairs);

		// Use full z-extent as block size so each grid element is an XY column spanning all layers
		final long[] dims = downscaledAttributes.getDimensions();
		final int[] blockSize = downscaledAttributes.getBlockSize();
		final int[] columnBlockSize = new int[]{blockSize[0], blockSize[1], (int) dims[2]};
		final List<long[][]> grid = Grid.create(dims, columnBlockSize);

		final String n5Path = options.n5Path;
		final String dsInputDataset = downScaledInputDataset;
		final int lowerThreshold = multiSemOptions.lowerThreshold();
		final int upperThreshold = multiSemOptions.upperThreshold();
		final double cutoff = multiSemOptions.cutoff();

		final List<LayerHistogram[]> partitionResults = sparkContext.parallelize(grid)
				.mapPartitions(blocks -> {
					final N5Reader n5 = N5Util.createN5Reader(n5Path);
					final RandomAccessibleInterval<UnsignedByteType> img = N5Utils.open(n5, dsInputDataset);

					final LayerHistogram[] merged = new LayerHistogram[nLayerPairs];

					while (blocks.hasNext()) {
						processShiftColumn(img, blocks.next(), merged, lowerThreshold, upperThreshold, cutoff);
					}

					return Collections.singletonList(merged).iterator();
				})
				.collect();

		// Merge partition results on driver
		final LayerHistogram[] globalHistograms = new LayerHistogram[nLayerPairs];
		for (final LayerHistogram[] partResult : partitionResults) {
			for (int i = 0; i < nLayerPairs; i++) {
				if (partResult[i] != null) {
					if (globalHistograms[i] == null) {
						globalHistograms[i] = partResult[i];
					} else {
						globalHistograms[i].absorb(partResult[i]);
					}
				}
			}
		}

		logMessage("computeHistograms: exit, returning "  + globalHistograms.length + " histograms");

		return globalHistograms;
	}

	private static void processShiftColumn(
			final RandomAccessibleInterval<UnsignedByteType> img,
			final long[][] gridBlock,
			final LayerHistogram[] merged,
			final int lowerThreshold,
			final int upperThreshold,
			final double cutoff) {

		final FinalInterval interval = Intervals.createMinSize(
				gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
				gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);
		final RandomAccessibleInterval<UnsignedByteType> column = Views.interval(img, interval);
		final long zMin = column.min(2);
		final int nLayers = (int) column.dimension(2);

		for (int z = 0; z < nLayers - 1; z++) {
			final Cursor<UnsignedByteType> currentLayer = Views.flatIterable(Views.hyperSlice(column, 2, zMin + z)).cursor();
			final Cursor<UnsignedByteType> nextLayer = Views.flatIterable(Views.hyperSlice(column, 2, zMin + z + 1)).cursor();

			final List<Double> shifts = new ArrayList<>();

			while (currentLayer.hasNext()) {
				final int valCurrent = currentLayer.next().getInteger();
				final int valNext = nextLayer.next().getInteger();

				if (valCurrent >= lowerThreshold && valCurrent <= upperThreshold
						&& valNext >= lowerThreshold && valNext <= upperThreshold) {
					shifts.add((double) (valNext - valCurrent));
				}
			}

			if (!shifts.isEmpty()) {
				final LayerHistogram blockHistogram = LayerHistogram.from(shifts, cutoff);
				if (merged[z] == null) {
					merged[z] = blockHistogram;
				} else {
					merged[z].absorb(blockHistogram);
				}
			}
		}
	}

	private List<AffineModel1D> histogramsToTransformations(final LayerHistogram[] histograms) {

		logMessage("histogramsToTransformations: entry");

		final List<AffineModel1D> models = new ArrayList<>(histograms.length + 1);

		// First layer has identity transform (reference)
		final AffineModel1D identity = new AffineModel1D();
		identity.set(1.0, 0.0);
		models.add(identity);

		double cumulativeShift = 0.0;

		// Print header for diagnostic output
		System.out.println("Computing layer shift statistics...");
		System.out.println("layer\tnValid\tmedian\tmean\tstd\tmin\tmax\tlayerShift\tcumulativeShift");
		System.out.printf("%d\t-\t-\t-\t-\t-\t-\t%.2f\t%.2f%n", 0, 0.0, 0.0);

		for (int z = 0; z < histograms.length; z++) {
			double layerShift = 0.0;
			final LayerHistogram histogram = histograms[z];

			if (histogram != null) {
				layerShift = (multiSemOptions.aggregation() == AggregationType.MEDIAN)
						? histogram.median() : histogram.mean();
				cumulativeShift += layerShift;

				System.out.printf("%d\t%d\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f%n",
								  z + 1, histogram.totalCount(), histogram.median(), histogram.mean(),
								  histogram.std(), histogram.min(), histogram.max(),
								  layerShift, cumulativeShift);
			} else {
				System.out.printf("%d\t0\t-\t-\t-\t-\t-\t%.2f\t%.2f%n",
								  z + 1, layerShift, cumulativeShift);
			}

			final AffineModel1D model = new AffineModel1D();
			model.set(1.0, -cumulativeShift);
			models.add(model);
		}

		logMessage("histogramsToTransformations: exit, returning " + models.size() + " models");

		return models;
	}

	private static void logMessage(final String message) {
		org.janelia.saalfeldlab.hotknife.util.Util.logMessage(MultiSemNormalizeLayerIntensity.class.getName(), message);
	}
}
