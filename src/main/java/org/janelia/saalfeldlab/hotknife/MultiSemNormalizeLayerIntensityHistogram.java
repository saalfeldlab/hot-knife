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
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
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
				usage = "Z index of the reference section (default: 5)")
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
		final float[] hf;
		final long[] hfShape;
		final double[] factors;

		try (final N5Reader n5 = N5Util.createN5Reader(options.n5Path())) {

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

			final RandomAccessibleInterval<FloatType> hfRai = N5Utils.open(n5, options.heightfieldDataset());
			if (hfRai.numDimensions() != 2) {
				throw new IllegalArgumentException("heightfield must be 2D, got " + hfRai.numDimensions() + "D");
			}
			hfShape = new long[]{hfRai.dimension(0), hfRai.dimension(1)};
			hf = new float[(int)(hfShape[0] * hfShape[1])];
			final Cursor<FloatType> c = Views.flatIterable(Views.zeroMin(hfRai)).cursor();
			int i = 0;
			while (c.hasNext()) {
				hf[i++] = c.next().get();
			}

			factors = readHeightfieldFactors(n5, options.heightfieldDataset());
			System.out.println("Heightfield downsamplingFactors = [" +
					factors[0] + ", " + factors[1] + ", " + factors[2] + "]");
		}

		try (final N5Writer w = N5Util.createN5Writer(options.n5Path())) {
			w.createDataset(options.n5DatasetOutput(), dims, blockSize, DataType.UINT8, new GzipCompression());
		}

		final int numZ = (int) dims[2];
		final SparkConf conf = new SparkConf().setAppName("MultiSemNormalizeLayerIntensityHistogram");
		try (final JavaSparkContext sc = new JavaSparkContext(conf)) {

			final Broadcast<float[]> hfBc = sc.broadcast(hf);
			final Broadcast<double[]> factorsBc = sc.broadcast(factors);
			final String n5Path = options.n5Path();
			final String inDataset = options.n5DatasetInput();
			final String outDataset = options.n5DatasetOutput();

			// Pass 1: split XY into blocks, each task sweeps all z within its column
			// and returns a per-section LayerHistogram of masked pixels in the block.
			// Reduce element-wise via absorb to get per-section totals over the full XY plane.
			final List<long[][]> xyGrid = Grid.create(
					new long[]{dims[0], dims[1]},
					new int[]{blockSize[0], blockSize[1]});

			final List<LayerHistogram> sectionHists = sc.parallelize(xyGrid)
					.map(block -> columnHistograms(n5Path, inDataset, block, numZ, hfBc.value(), hfShape, factorsBc.value()))
					.reduce(MultiSemNormalizeLayerIntensityHistogram::mergeSectionHists);

			final double[] refCdf = cdf256(sectionHists.get(options.refIndex()));
			final int[][] luts = new int[numZ][];
			for (int z = 0; z < numZ; z++) {
				luts[z] = buildLut(sectionHists.get(z), refCdf);
			}
			System.out.println("Computed per-section LUTs against reference z=" + options.refIndex());

			// Pass 2: 3D block grid, apply LUT[z] inside mask.
			final Broadcast<int[][]> lutsBc = sc.broadcast(luts);
			final List<long[][]> grid = Grid.create(dims, blockSize);
			sc.parallelize(grid).foreach(block ->
					processBlock(n5Path, inDataset, outDataset, block,
								 lutsBc.value(), hfBc.value(), hfShape, factorsBc.value()));
		}

		System.out.println("wrote " + options.n5Path() + "/" + options.n5DatasetOutput());
	}

	/**
	 * For a single XY column, return one {@link LayerHistogram} per section containing
	 * the intensity counts of in-tissue pixels within that column. Always constructed
	 * with offset=0 and length 256 so {@link LayerHistogram#absorb} stays 8-bit-aligned.
	 */
	private static List<LayerHistogram> columnHistograms(
			final String n5Path,
			final String inDataset,
			final long[][] xyBlock,
			final int numZ,
			final float[] hf,
			final long[] hfShape,
			final double[] factors) {

		final N5Reader n5 = N5Util.createN5Reader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> volume = N5Utils.open(n5, inDataset);

		final long x0 = xyBlock[0][0];
		final long y0 = xyBlock[0][1];
		final long x1 = x0 + xyBlock[1][0] - 1;
		final long y1 = y0 + xyBlock[1][1] - 1;

		final List<LayerHistogram> result = new ArrayList<>(numZ);
		for (int z = 0; z < numZ; z++) {
			final long[] counts = new long[256];
			final RandomAccessibleInterval<UnsignedByteType> slice = Views.interval(
					volume,
					new long[]{x0, y0, z},
					new long[]{x1, y1, z});
			final Cursor<UnsignedByteType> c = Views.flatIterable(slice).localizingCursor();
			while (c.hasNext()) {
				final int v = c.next().get();
				final long x = c.getLongPosition(0);
				final long y = c.getLongPosition(1);
				if (inTissue(z, x, y, hf, hfShape, factors)) {
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

	private static boolean inTissue(
			final long z, final long x, final long y,
			final float[] hf, final long[] hfShape, final double[] factors) {
		final long hx = Math.min((long)(x / factors[0]), hfShape[0] - 1);
		final long hy = Math.min((long)(y / factors[1]), hfShape[1] - 1);
		final double val = hf[(int)(hy * hfShape[0] + hx)] * factors[2];
		return z < val;
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
	private static double[] cdf256(final LayerHistogram hist) {
		final double[] cdf = new double[256];
		final long total = hist.totalCount();
		if (total == 0) {
			for (int i = 0; i < 256; i++) cdf[i] = (i + 1) / 256.0;
			return cdf;
		}
		final long[] counts = hist.counts();
		final int offset = hist.offset();
		long acc = 0;
		for (int v = 0; v < 256; v++) {
			final int idx = v + offset;
			if (idx >= 0 && idx < counts.length) {
				acc += counts[idx];
			}
			cdf[v] = (double) acc / total;
		}
		return cdf;
	}

	private static int[] buildLut(final LayerHistogram hist, final double[] refCdf) {
		final int[] lut = new int[256];
		if (hist.totalCount() == 0) {
			for (int i = 0; i < 256; i++) lut[i] = i;
			return lut;
		}
		final double[] srcCdf = cdf256(hist);
		int j = 0;
		for (int i = 0; i < 256; i++) {
			while (j < 255 && refCdf[j] < srcCdf[i]) j++;
			lut[i] = j;
		}
		return lut;
	}

	private static void processBlock(
			final String n5Path,
			final String inDataset,
			final String outDataset,
			final long[][] gridBlock,
			final int[][] luts,
			final float[] hf,
			final long[] hfShape,
			final double[] factors) {

		final N5Writer n5 = N5Util.createN5Writer(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> volume = N5Utils.open(n5, inDataset);

		final FinalInterval blockInterval = Intervals.createMinSize(
				gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
				gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);

		final long[] blockDims = new long[]{gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]};
		final Img<UnsignedByteType> out = ArrayImgs.unsignedBytes(blockDims);

		final Cursor<UnsignedByteType> srcCur = Views.flatIterable(Views.interval(volume, blockInterval)).cursor();
		final Cursor<UnsignedByteType> dstCur = Views.flatIterable(out).localizingCursor();

		while (dstCur.hasNext()) {
			final UnsignedByteType dst = dstCur.next();
			final int v = srcCur.next().get();

			final long x = dstCur.getLongPosition(0) + gridBlock[0][0];
			final long y = dstCur.getLongPosition(1) + gridBlock[0][1];
			final long z = dstCur.getLongPosition(2) + gridBlock[0][2];

			dst.set(inTissue(z, x, y, hf, hfShape, factors) ? luts[(int) z][v] : v);
		}

		N5Utils.saveNonEmptyBlock(out, n5, outDataset, gridBlock[2], new UnsignedByteType());
	}
}
