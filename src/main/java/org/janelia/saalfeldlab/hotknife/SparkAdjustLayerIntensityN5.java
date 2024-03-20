package org.janelia.saalfeldlab.hotknife;

import bdv.util.BdvFunctions;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TranslationModel1D;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.apache.commons.lang.math.IntRange;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class SparkAdjustLayerIntensityN5 {

	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput",
				required = true,
				usage = "Input N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5")
		private String n5PathInput = null;

		@Option(name = "--n5DatasetInput",
				required = true,
				usage = "Input N5 dataset, e.g. /render/slab_070_to_079/s075_m119_align_big_block_ic___20240308_072106")
		private String n5DatasetInput = null;

		@Option(
				name = "--factors",
				usage = "If specified, generates a scale pyramid with given factors, e.g. 2,2,1")
		public String factors;

		@Option(name = "--invert", usage = "Invert before saving to N5, e.g. for MultiSEM")
		private boolean invert = false;

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

	private static void saveFullScaleBlock(final String n5PathInput,
										   final String n5PathOutput,
										   final String datasetName, // should be s0
										   final String datasetNameOutput,
										   final long[] dimensions,
										   final int[] blockSize,
										   final long[][] gridBlock,
										   final boolean invert) {

		final N5Reader n5Input = new N5FSReader(n5PathInput);
		final N5Writer n5Output = new N5FSWriter(n5PathOutput);

		final RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5Input, datasetName);
//		final RandomAccessibleInterval<UnsignedByteType> filteredSource =
//				SparkGenerateFaceScaleSpace.filter(sourceRaw,
//												   invert,
//												   true,
//												   0);
//
//		final FinalInterval gridBlockInterval =
//				Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
//										gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);

//		N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
//								  n5Output,
//								  datasetNameOutput,
//								  new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
//								  gridBlock[2],
//								  new UnsignedByteType());
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final String[] xargs = new String[]{
				"--n5PathInput", "/nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5",
				"--n5DatasetInput", "render/slab_070_to_079/s075_m119_align_big_block_ic2d___20240314_175709",
				"--factors", "2,2,1",
				"--invert"
		};

		final SparkAdjustLayerIntensityN5.Options options = new SparkAdjustLayerIntensityN5.Options(xargs);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

//		final SparkConf conf = new SparkConf().setAppName("SparkNormalizeN5");
//		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
//		sparkContext.setLogLevel("ERROR");

		final N5Reader n5Input = new N5FSReader(options.n5PathInput);

		final String fullScaleInputDataset = options.n5DatasetInput + "/s6";
		final int[] blockSize = n5Input.getAttribute(fullScaleInputDataset, "blockSize", int[].class);
		final long[] dimensions = n5Input.getAttribute(fullScaleInputDataset, "dimensions", long[].class);

		final int[] gridBlockSize = new int[] { blockSize[0] * 8, blockSize[1] * 8, blockSize[2] };
		final List<long[][]> grid = Grid.create(dimensions, gridBlockSize, blockSize);

		final String downScaledDataset = options.n5DatasetInput + "/s10";
		final Img<UnsignedByteType> downScaledImg = N5Utils.open(n5Input, downScaledDataset);
		final List<IntervalView<UnsignedByteType>> downScaledStack = asZStack(downScaledImg);

		final List<TranslationModel1D> models = fitModels(downScaledStack);

		final Img<UnsignedByteType> sourceRaw = N5Utils.open(n5Input, fullScaleInputDataset);
		final Img<UnsignedByteType> copy = ArrayImgs.unsignedBytes(sourceRaw.dimensionsAsLongArray());
		final List<IntervalView<UnsignedByteType>> sourceStack = asZStack(sourceRaw);
		final List<IntervalView<UnsignedByteType>> targetStack = asZStack(copy);

		applyModels(models, sourceStack, targetStack);

		BdvFunctions.show(copy, "converted");

//		final N5Writer n5Output = new N5FSWriter(options.n5PathInput);
//		final String invertedName = options.invert ? "_inverted" : "";
//		final String outputDataset = options.n5DatasetInput + "_normalized" + invertedName;
//		final String fullScaleOutputDataset = outputDataset + "/s0";
//
//		if (n5Output.exists(fullScaleOutputDataset)) {
//			final String fullPath = options.n5PathInput + fullScaleOutputDataset;
//			throw new IllegalArgumentException("Normalized data set exists: " + fullPath);
//		}
//
//		n5Output.createDataset(fullScaleOutputDataset, dimensions, blockSize, DataType.UINT8, new GzipCompression());
//
//		final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid);
//		pGrid.foreach(
//				gridBlock -> saveFullScaleBlock(options.n5PathInput,
//												options.n5PathInput,
//												fullScaleInputDataset,
//												fullScaleOutputDataset,
//												dimensions,
//												blockSize,
//												gridBlock,
//												options.invert));
//		n5Output.close();
		n5Input.close();

//		final int[] downsampleFactors = parseCSIntArray(options.factors);
//		if (downsampleFactors != null) {
//			downsampleScalePyramid(sparkContext,
//								   new N5PathSupplier(options.n5PathInput),
//								   fullScaleOutputDataset,
//								   outputDataset,
//								   downsampleFactors);
//		}

//		sparkContext.close();
	}

	private static List<IntervalView<UnsignedByteType>> asZStack(final RandomAccessibleInterval<UnsignedByteType> rai) {
		final List<IntervalView<UnsignedByteType>> stack = new ArrayList<>();
		for (int z = 0; z < rai.dimension(2); ++z) {
			stack.add(Views.hyperSlice(rai, 2, z));
		}
		return stack;
	}

	private static List<TranslationModel1D> fitModels(List<IntervalView<UnsignedByteType>> downScaledStack) {

		// initialize
		final List<Tile<TranslationModel1D>> tiles = new ArrayList<>();
		for (int z = 0; z < downScaledStack.size(); ++z) {
			final TranslationModel1D model = new TranslationModel1D();
			tiles.add(new Tile<>(model));
		}


		// match (only pixels that have "content" and not just resin)
		final IntRange contentRange = new IntRange(1, 100);

		for (int z = 0; z < downScaledStack.size() - 1; ++z) {
			final IntervalView<UnsignedByteType> layerA = downScaledStack.get(z);
			final IntervalView<UnsignedByteType> layerB = downScaledStack.get(z + 1);

			final List<PointMatch> matches = new ArrayList<>();
			LoopBuilder.setImages(layerA, layerB)
					.forEachPixel((a, b) -> {
						if (contentRange.containsInteger(a.get()) && contentRange.containsInteger(b.get())) {
							final Point pointA = new Point(new double[]{a.get()});
							final Point pointB = new Point(new double[]{b.get()});
							final PointMatch match = new PointMatch(pointA, pointB, 1);
							matches.add(match);
						}
					});

			final TranslationModel1D model = new TranslationModel1D();
			final List<PointMatch> inliers = new ArrayList<>();
			try {
				model.filterRansac(matches, inliers, 1000, 0.01f, 0.01f);
			} catch (NotEnoughDataPointsException e) {
				throw new RuntimeException(e);
			}

			System.out.printf("inlier ratio for z=%d: %d / %d\n", z, inliers.size(), matches.size());
			for (final PointMatch inlier : inliers) {
				System.out.printf("inlier: %f -> %f\n", inlier.getP1().getL()[0], inlier.getP2().getL()[0]);
			}

			final Tile<TranslationModel1D> tileA = tiles.get(z);
			final Tile<TranslationModel1D> tileB = tiles.get(z + 1);
			tileA.connect(tileB, inliers);
		}

		// optimize
		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles(tiles);
		tc.fixTile(tiles.get(0));

		final int nIt = 1000;
		try {
			tc.optimize(new ErrorStatistic(nIt + 1), 0.01f, nIt, nIt, 0.75f);
		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			throw new RuntimeException(e);
		}

		//TODO remove
		for (int z = 0; z < downScaledStack.size() - 1; ++z) {
			final double[] matrix = new double[2];
			tiles.get(z).getModel().toArray(matrix);
			System.out.printf("z=%d: %s\n", z, Arrays.toString(matrix));
		}
		return tiles.stream().map(Tile::getModel).collect(Collectors.toList());
	}

	private static void applyModels(
			final List<TranslationModel1D> models,
			final List<IntervalView<UnsignedByteType>> sourceStack,
			final List<IntervalView<UnsignedByteType>> targetStack) {

		final double[] container = new double[1];
		final IntRange contentRange = new IntRange(1, 100);

		for (int z = 0; z < sourceStack.size(); ++z) {
			final TranslationModel1D model = models.get(z);
			LoopBuilder.setImages(sourceStack.get(z), targetStack.get(z))
					.forEachPixel((s, t) -> {
						// only apply in the foreground
						if (s.get() > 0) {
//						if (contentRange.containsInteger(s.get())) {
							container[0] = s.get();
							model.applyInPlace(container);
							t.set((int) Math.round(container[0]));
						}
//						else {
//							t.set(0);
//						}
					});
		}
	}
}
