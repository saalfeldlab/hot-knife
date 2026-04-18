package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.janelia.saalfeldlab.hotknife.SparkNormalizeLayerIntensityN5.LayerHistogram;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 * Per-section histogram matching within a tissue mask derived from a 2D height field.
 * Mirrors the reference Python implementation: voxels with {@code z < heightfield(y, x)}
 * are in-tissue; each section's in-tissue pixels are histogram-matched to those of a
 * designated reference section. Out-of-tissue pixels pass through unchanged.
 * <p>
 * Only supports 8-bit data. Pass 1 (histogram collection) is parallelized over XY grid
 * blocks and reduced via {@link LayerHistogram#absorb}. Pass 2 applies the per-section
 * LUTs in a standard 3D block-grid Spark stage.
 */
public class MultiSemNormalizeLayerIntensityHistogram {

	public static final int N_BINS = 256;

	@SuppressWarnings({"FieldMayBeFinal", "unused", "FieldCanBeLocal"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true,
				usage = "N5 path, e.g. /nrs/.../debug-costs.n5")
		private String n5Path = null;

		@Option(name = "--n5DatasetInput", required = true,
				usage = "Input N5 dataset (3D UINT8), e.g. w61_s082/raw_tissue/s0")
		private String n5DatasetInput = null;

		@Option(name = "--n5DatasetOutput", required = true,
				usage = "Output N5 dataset, e.g. w61_s082/michal-processed-hf/s0")
		private String n5DatasetOutput = null;

		@Option(name = "--heightfieldDataset", required = true,
				usage = "2D height field dataset (FloatType), e.g. w61_s082/heightfield_1/s1/max. " +
						"Resolution relative to the raw is read from the 'downsamplingFactors' " +
						"attribute on the parent group (as written by SparkSurfaceFit).")
		private String heightfieldDataset = null;

		@Option(name = "--refIndex",
				usage = "Z index of the reference section used to equalize histograms to (default: 5)")
		private int refIndex = 5;

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

		public String n5Path() { return n5Path; }
		public String n5DatasetInput() { return n5DatasetInput; }
		public String n5DatasetOutput() { return n5DatasetOutput; }
		public String heightfieldDataset() { return heightfieldDataset; }
		public int refIndex() { return refIndex; }
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		final Options options = new Options(args);
		if (!options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}
		run(options);
	}

	private static void run(final Options options) {

		final long[] dims;
		final int[] blockSize;
		final double[] factors;

		try (final N5Reader n5 = N5Util.createN5Reader(options.n5Path())) {
			// Read heightfield factors and make sure the full data setup is valid
			if (n5.exists(options.n5DatasetOutput())) {
				throw new IllegalArgumentException("output dataset already exists: " + options.n5DatasetOutput());
			}

			final DatasetAttributes attrs = n5.getDatasetAttributes(options.n5DatasetInput());
			if (attrs == null) {
				throw new IllegalArgumentException("no attributes on " + options.n5DatasetInput());
			}
			if (attrs.getDataType() != DataType.UINT8) {
				throw new IllegalArgumentException("only 8-bit supported, found: " + attrs.getDataType());
			}
			dims = attrs.getDimensions();
			blockSize = attrs.getBlockSize();

			final DatasetAttributes hfAttrs = n5.getDatasetAttributes(options.heightfieldDataset());
			if (hfAttrs == null) {
				throw new IllegalArgumentException("no attributes on " + options.heightfieldDataset());
			}
			if (hfAttrs.getDimensions().length != 2) {
				throw new IllegalArgumentException("heightfield must be 2D, got " + hfAttrs.getDimensions().length + "D");
			}

			factors = readHeightfieldFactors(n5, options.heightfieldDataset());
			System.out.println("Heightfield downsamplingFactors = [" +
					factors[0] + ", " + factors[1] + ", " + factors[2] + "]");
		}

		try (final N5Writer writer = N5Util.createN5Writer(options.n5Path())) {
			writer.createDataset(options.n5DatasetOutput(), dims, blockSize, DataType.UINT8, new GzipCompression());
		}

		final int numZ = (int) dims[2];
		final SparkConf conf = new SparkConf().setAppName("MultiSemNormalizeLayerIntensityHistogram");
		try (final JavaSparkContext sc = new JavaSparkContext(conf)) {

			final Broadcast<double[]> factorsBc = sc.broadcast(factors);
			final String n5Path = options.n5Path();
			final String inDataset = options.n5DatasetInput();
			final String outDataset = options.n5DatasetOutput();
			final String hfDataset = options.heightfieldDataset();

			// Pass 1: split XY into blocks, each task sweeps all z within its column
			// and returns a per-section LayerHistogram of masked pixels in the block.
			// Reduce element-wise via absorb to get per-section totals over the full XY plane.
			final List<long[][]> xyGrid = Grid.create(
					new long[]{dims[0], dims[1]},
					new int[]{blockSize[0], blockSize[1]});

			final List<LayerHistogram> sectionHists = sc.parallelize(xyGrid)
					.map(block -> columnHistograms(n5Path, inDataset, hfDataset, block, numZ, factorsBc.value()))
					.reduce(MultiSemNormalizeLayerIntensityHistogram::mergeSectionHists);

			final double[] refCdf = extractCdf(sectionHists.get(options.refIndex()));
			final int[][] luts = new int[numZ][];
			for (int z = 0; z < numZ; z++) {
				luts[z] = buildLut(sectionHists.get(z), refCdf);
			}
			System.out.println("Computed per-section LUTs against reference z=" + options.refIndex());

			// Pass 2: 3D block grid, apply LUT[z] inside the tissue mask; out-of-tissue
			// pixels pass through unchanged. Layers with no in-tissue pixels still carry
			// an identity LUT from buildLut, so their behaviour is always a no-op.
			final Broadcast<int[][]> lutsBc = sc.broadcast(luts);
			final List<long[][]> grid = Grid.create(dims, blockSize);
			sc.parallelize(grid).foreach(block ->
					processBlock(n5Path, inDataset, outDataset, hfDataset, block,
							lutsBc.value(), factorsBc.value()));
		}

		System.out.println("wrote " + options.n5Path() + "/" + options.n5DatasetOutput());
	}

	/**
	 * For a single XY column, return one {@link LayerHistogram} per section containing
	 * the intensity counts of in-tissue pixels within that column.
	 * <p>
	 * The heightfield is opened lazily and rescaled to raw XY coordinates.
	 */
	private static List<LayerHistogram> columnHistograms(
			final String n5Path,
			final String inDataset,
			final String hfDataset,
			final long[][] xyBlock,
			final int numZ,
			final double[] factors) {

		final N5Reader n5 = N5Util.createN5Reader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> volume = N5Utils.open(n5, inDataset);
		final RandomAccessibleInterval<FloatType> hfRai = N5Utils.open(n5, hfDataset);

		final long x0 = xyBlock[0][0];
		final long y0 = xyBlock[0][1];
		final long x1 = x0 + xyBlock[1][0] - 1;
		final long y1 = y0 + xyBlock[1][1] - 1;

		// Heightfield rescaled to raw XY coordinates (positions) and raw Z (values).
		// Accessing this at integer raw (x, y) gives back the tissue boundary z directly.
		final RandomAccessibleInterval<DoubleType> hfAtRawScale = Views.interval(
				Views.raster(Transform.scaleAndShiftHeightFieldAndValues(hfRai, factors)),
				new long[]{x0, y0},
				new long[]{x1, y1});

		final List<LayerHistogram> result = new ArrayList<>(numZ);
		for (int z = 0; z < numZ; z++) {
			final long[] counts = new long[N_BINS];
			final RandomAccessibleInterval<UnsignedByteType> slice = Views.interval(
					Views.hyperSlice(volume, 2, z),
					new long[]{x0, y0},
					new long[]{x1, y1});
			final Cursor<UnsignedByteType> srcCursor = Views.flatIterable(slice).cursor();
			final Cursor<DoubleType> hfCursor = Views.flatIterable(hfAtRawScale).cursor();
			while (srcCursor.hasNext()) {
				final int v = srcCursor.next().get();
				if (z < hfCursor.next().get()) {
					counts[v]++;
				}
			}
			result.add(LayerHistogram.fromCounts(counts, 0, 0.0));
		}
		return result;
	}

	private static List<LayerHistogram> mergeSectionHists(
			final List<LayerHistogram> a, final List<LayerHistogram> b) {
		final List<LayerHistogram> out = new ArrayList<>(a.size());
		for (int i = 0; i < a.size(); i++) {
			out.add(a.get(i).absorb(b.get(i)));
		}
		return out;
	}

	/**
	 * Read the heightfield's downsampling factors relative to the raw volume. Looks on the
	 * parent group first (where {@link SparkSurfaceFit} writes the attribute), falling back
	 * to the dataset itself. Returns a 3-element [xF, yF, zF] array.
	 */
	private static double[] readHeightfieldFactors(final N5Reader n5, final String heightfieldDataset) {
		final int slash = heightfieldDataset.lastIndexOf('/');
		final String[] candidates = (slash > 0)
				? new String[]{heightfieldDataset.substring(0, slash), heightfieldDataset}
				: new String[]{heightfieldDataset};
		for (final String path : candidates) {
			final double[] f = n5.getAttribute(path, "downsamplingFactors", double[].class);
			if (f != null) {
				if (f.length < 3) {
					throw new IllegalArgumentException(
							"downsamplingFactors on " + path + " must have 3 elements, got " + f.length);
				}
				return f;
			}
		}
		throw new IllegalArgumentException(
				"no 'downsamplingFactors' attribute found on " + heightfieldDataset + " or its parent group");
	}

	/**
	 * Extract a 256-entry CDF from a LayerHistogram. Respects the internal offset so
	 * the result is always aligned to integer intensity values 0..255. Returns a
	 * uniform CDF if the histogram is empty.
	 */
	private static double[] extractCdf(final LayerHistogram hist) {
		final double[] cdf = new double[N_BINS];
		final long total = hist.totalCount();
		if (total == 0) {
			for (int i = 0; i < N_BINS; i++) cdf[i] = (i + 1) / (double) N_BINS;
			return cdf;
		}
		final long[] counts = hist.counts();
		final int offset = hist.offset();
		long acc = 0;
		for (int v = 0; v < N_BINS; v++) {
			final int idx = v + offset;
			if (idx >= 0 && idx < counts.length) {
				acc += counts[idx];
			}
			cdf[v] = (double) acc / total;
		}
		return cdf;
	}

	/**
	 * Build a LUT mapping source intensity values to reference intensity values based on
	 * the source histogram and reference CDF. Uses a standard histogram matching approach.
	 */
	private static int[] buildLut(final LayerHistogram hist, final double[] refCdf) {
		final int[] lut = new int[N_BINS];
		if (hist.totalCount() == 0) {
			for (int i = 0; i < N_BINS; i++) lut[i] = i;
			return lut;
		}
		final double[] srcCdf = extractCdf(hist);
		int j = 0;
		for (int i = 0; i < N_BINS; i++) {
			while (j < (N_BINS - 1) && refCdf[j] < srcCdf[i]) j++;
			lut[i] = j;
		}
		return lut;
	}

	/**
	 * Apply the per-section LUTs to a single 3D block and write the result to the output dataset.
	 */
	private static void processBlock(
			final String n5Path,
			final String inDataset,
			final String outDataset,
			final String hfDataset,
			final long[][] gridBlock,
			final int[][] luts,
			final double[] factors) {

		final N5Writer n5 = N5Util.createN5Writer(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> volume = N5Utils.open(n5, inDataset);
		final RandomAccessibleInterval<FloatType> hfRai = N5Utils.open(n5, hfDataset);

		final long x0 = gridBlock[0][0];
		final long y0 = gridBlock[0][1];
		final long z0 = gridBlock[0][2];
		final long x1 = x0 + gridBlock[1][0] - 1;
		final long y1 = y0 + gridBlock[1][1] - 1;

		final RandomAccessibleInterval<DoubleType> hfAtRawScale = Views.interval(
				Views.raster(Transform.scaleAndShiftHeightFieldAndValues(hfRai, factors)),
				new long[]{x0, y0},
				new long[]{x1, y1});

		final long[] blockDims = new long[]{gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]};
		final Img<UnsignedByteType> out = ArrayImgs.unsignedBytes(blockDims);

		for (long zOff = 0; zOff < blockDims[2]; zOff++) {
			final long z = zOff + z0;
			final int[] lutZ = luts[(int) z];

			final RandomAccessibleInterval<UnsignedByteType> srcSlice = Views.interval(
					Views.hyperSlice(volume, 2, z),
					new long[]{x0, y0},
					new long[]{x1, y1});
			final RandomAccessibleInterval<UnsignedByteType> dstSlice = Views.hyperSlice(out, 2, zOff);

			final Cursor<UnsignedByteType> srcCur = Views.flatIterable(srcSlice).cursor();
			final Cursor<UnsignedByteType> dstCur = Views.flatIterable(dstSlice).cursor();
			final Cursor<DoubleType> hfCur = Views.flatIterable(hfAtRawScale).cursor();

			while (dstCur.hasNext()) {
				final int v = srcCur.next().get();
				final double hfVal = hfCur.next().get();
				dstCur.next().set(z > hfVal ? v : lutZ[v]);   // Note: height-check is less conservative than in histogram computation
			}
		}

		N5Utils.saveNonEmptyBlock(out, n5, outDataset, gridBlock[2], new UnsignedByteType());
	}
}
