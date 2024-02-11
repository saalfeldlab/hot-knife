/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.TaskContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.ops.CLLCN;
import org.janelia.saalfeldlab.hotknife.ops.ImageJStackOp;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ClippedTransitionRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkExportAlignedSlabSeries {

	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput", required = true, usage = "Input N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5PathInput = null;

		@Option(name = "--n5PathOutput", required = true, usage = "Output N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5PathOutput = null;

		@Option(name = "-i", aliases = {"--n5DatasetInput"}, required = true, usage = "N5 slab dataset, e.g. /slab-22/raw")
		private ArrayList<String> datasetsInput = new ArrayList<>();

		@Option(name = "-t", aliases = {"--top"}, required = true, usage = "top slab face offset")
		private ArrayList<Long> topOffsets = new ArrayList<>();

		@Option(name = "-b", aliases = {"--bot"}, required = true, usage = "bottom slab face offset")
		private ArrayList<Long> botOffsets = new ArrayList<>();

		@Option(name = "-j", aliases = {"--n5TransformGroup"}, required = true, usage = "N5 group containing alignments, e.g. /align-13")
		private String n5GroupAlign;

		@Option(name = "-o", aliases = {"--n5DatasetOutput"}, required = true, usage = "Output dataset, e.g. /slab-23/raw/s0")
		private String datasetOutput = null;

		@Option(name = "--blockSize", usage = "blockSize, e.g. 128,128,128")
		private String blockSizeString = null;

		@Option(name = "-n", aliases = {"--normalizeContrast"}, usage = "optionally normalize contrast")
		private boolean normalizeContrast;

		@Option(name = "--zBatch",
				usage = "Separate blocks into batches by z and run only one batch.  " +
						"Format is <one-based-batch-for-current-run>:<total-batch-count>.  " +
						"For example, specify 1:10 to run first batch of ten and 10:10 to run last batch of ten."
		)
		String zBatchString = null;
		Integer zBatchForCurrentRun = null;
		Integer zBatchTotalCount = null;

		@Option(name = "--zGridPosition",
				usage = "Only processes blocks with these z grid positions.")
		List<Long> zGridPositions = new ArrayList<>();

		// TIP: To aggregate and sort explain plan info by task id:
		//   grep " returning " ${SPARK_LOGS_DIR}/work*/app*/*/stdout | sed 's/.*task//' | sort -n > task.log
		@Option(name = "--explainPlan",
				usage = "Only report input grid block counts (the plan) and skip actual output generation.")
		private boolean explainPlan;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				setDerivedZBatchFields();
				parsedSuccessfully = datasetsInput.size() == topOffsets.size() && datasetsInput.size() == botOffsets.size();
			} catch (final Exception e) {
				e.printStackTrace(System.err);
				parser.printUsage(System.err);
			}
		}

		private void setDerivedZBatchFields() throws IllegalArgumentException {
			if (zBatchString != null) {
				final Matcher m = Pattern.compile("^(\\d++):(\\d++)$").matcher(zBatchString);
				if (m.matches()) {
					zBatchForCurrentRun = Integer.parseInt(m.group(1));
					zBatchTotalCount = Integer.parseInt(m.group(2));
					if (zBatchTotalCount < 1) {
						throw new IllegalArgumentException("total z batch count must be greater than 0");
					}
					if ((zBatchForCurrentRun < 1) || (zBatchForCurrentRun > zBatchTotalCount)) {
						throw new IllegalArgumentException(
								"z batch for current run must be between 1 and " + zBatchTotalCount);
					}
				} else {
					throw new IllegalArgumentException(
							"--zBatch must have format <one-based-batch-for-current-run>:<total-batch-count>");
				}
			}
		}

		/**
		 * @return the n5Path
		 */
		public String getN5InputPath() {

			return n5PathInput;
		}

		/**
		 * @return the input dataset
		 */
		public List<String> getInputDatasets() {

			return datasetsInput;
		}

		/**
		 * @return the alignment group
		 */
		public String getGroup() {

			return n5GroupAlign;
		}

		/**
		 * @return the top offsets
		 */
		public List<Long> getTopOffsets() {

			return topOffsets;
		}

		/**
		 * @return the bottom offsets (max)
		 */
		public List<Long> getBotOffsets() {

			return botOffsets;
		}

		/**
		 * @return the output N5 path
		 */
		public String getN5OutputPath() {

			return n5PathOutput;
		}

		/**
		 * @return the output N5 path
		 */
		public String getOutputDataset() {

			return datasetOutput;
		}

		/**
		 * @return the block size
		 */
		public int[] getBlockSize() {

			return blockSizeString == null ? new int[]{128, 128, 128}: parseCSIntArray(blockSizeString);
		}

		/**
		 * @return whether to normalize contrast
		 */
		public boolean normalizeContrast() {
			return normalizeContrast;
		}
	}

	public static boolean isBlockIncluded(final List<String> datasetNames,
										  final List<Long> topOffsets,
										  final List<Long> botOffsets,
										  final long[][] gridBlock,
										  final long minZForRun,
										  final long maxZForRun,
										  final Set<Long> zGridPositions,
										  final boolean explainPlan) {

		boolean isIncluded = false;

		final List<String> gridDatasetNames = new ArrayList<>();

		final long minZForBlock = gridBlock[0][2];
		final long maxZForBlock = gridBlock[0][2] + gridBlock[1][2] - 1;
		final boolean isBlockWithinZRangeForRun =
				((minZForBlock >= minZForRun) && (maxZForBlock <= maxZForRun));

		final long[] gridPosition = gridBlock[2];
		final boolean isZPositionIncludedInRun =
				(zGridPositions == null) || (zGridPositions.size() == 0) || zGridPositions.contains(gridPosition[2]);

		if (isBlockWithinZRangeForRun && isZPositionIncludedInRun) {

			isIncluded = true;

			// Find which datasets contain the grid block for explain plan logging.
			// Later, this will be needed to look for empty transformed blocks.
			long zOffset = 0;
			for (int i = 0; i < datasetNames.size(); ++i) {
				final long topOffset = topOffsets.get(i);
				final long botOffset = botOffsets.get(i);
				final long depth = botOffset - topOffset + 1;

				if (!((gridBlock[0][2] > zOffset + depth) | (gridBlock[0][2] + gridBlock[1][2] < zOffset))) {
					final String datasetName = datasetNames.get(i) + "/s0";
					gridDatasetNames.add(datasetName);
				}

				zOffset += depth;
			}

		}

		// TODO: if block is included, filter out black transformed blocks by testing with a down-sampled version

		if ((explainPlan) || (isIncluded && (zGridPositions.size() > 0))) {
			System.out.println("SparkExportAlignedSlabSeries: isBlockIncluded returning " + isIncluded +
							   " for block " + printBlock(gridBlock) + " in dataset(s) " + gridDatasetNames);
		}

		return isIncluded;
	}

	private static void saveBlock(
			final String n5PathInput,
			final String n5PathOutput,
			final List<String> datasetNames,
			final String group,
			final String datasetNameOutput,
			final String[] transformDatasetNames,
			final List<Long> topOffsets,
			final List<Long> botOffsets,
			final long[] min,
			final long[] max,
			final long[] dimensions,
			final int[] blockSize,
			final long[][] gridBlock,
			final boolean normalizeContrast ) throws IOException {

		final N5Reader n5Input = new N5FSReader(n5PathInput);
		final N5Writer n5Output = new N5FSWriter(n5PathOutput);

		final ArrayList<RandomAccessibleInterval<UnsignedByteType>> sources = new ArrayList<>();
		long zOffset = 0;
		for (int i = 0; i < datasetNames.size(); ++i) {
			final String datasetName = datasetNames.get(i) + "/s0";
			try {
				final long topOffset = topOffsets.get(i);
				final long botOffset = botOffsets.get(i);
				final long depth = botOffset - topOffset + 1;

				/* do not include blocks that do not intersect with the gridBlock */
				if (!((gridBlock[0][2] > zOffset + depth) | (gridBlock[0][2] + gridBlock[1][2] < zOffset))) {

					final RealTransform top =
							Transform.loadScaledTransform(n5Input, group + "/" + transformDatasetNames[i * 2]);
					final RealTransform bot =
							Transform.loadScaledTransform(n5Input, group + "/" + transformDatasetNames[i * 2 + 1]);
					final RealTransform transition =
							new ClippedTransitionRealTransform(
									top,
									bot,
									topOffset,
									botOffset);

					final long[] cropMin = new long[]{min[0], min[1], topOffset};
					final long[] cropMax = new long[]{max[0], max[1], botOffset};

					final FinalInterval cropInterval = new FinalInterval(
							cropMin,
							cropMax);

					final RandomAccessibleInterval<UnsignedByteType> source;

					if (normalizeContrast) {
						final RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5Input, datasetName);

						final int blockRadius = Math.round(511);

						final ImageJStackOp<UnsignedByteType> cllcn =
								new ImageJStackOp<>(
										Views.extendZero(sourceRaw),
										(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 10, 0.5f, true, true, true),
										blockRadius,
										0,
										255,
										true );

						source = Lazy.process(
								sourceRaw,
								new int[]{128, 128, 16},
								new UnsignedByteType(),
								AccessFlags.setOf(AccessFlags.VOLATILE),
								cllcn);
					} else {
						source = N5Utils.open(n5Input, datasetName);
					}

					final RandomAccessibleInterval<UnsignedByteType> transformedSource =
							Transform.createTransformedInterval(
									source,
									cropInterval,
									transition,
									new UnsignedByteType(0));

					final IntervalView<UnsignedByteType> extendedTransformedSource =
							Views.interval(
									Views.extendValue(
											Views.translate(
													Views.zeroMin(transformedSource),
													0, 0, zOffset),
											new UnsignedByteType(0)),
									new FinalInterval(min, max));

					sources.add(extendedTransformedSource);
				}

				zOffset += depth;

			} catch (final Throwable t) {
				final String errorMessage = "failed to open " + n5PathInput + " dataset " + datasetName;
				throw new IOException(errorMessage, t);
			}
		}

		final FinalInterval gridBlockInterval = Intervals.createMinSize(
				gridBlock[0][0],
				gridBlock[0][1],
				gridBlock[0][2],

				gridBlock[1][0],
				gridBlock[1][1],
				gridBlock[1][2]);

		switch (sources.size()) {
		case 0:
			break;
		case 1:
			N5Utils.saveNonEmptyBlock(
					Views.interval(
							sources.get(0),
							gridBlockInterval),
					n5Output,
					datasetNameOutput,
					new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
					gridBlock[2],
					new UnsignedByteType());
			break;
		default:
			final RandomAccessibleInterval<UnsignedByteType> composite = Converters.<UnsignedByteType, UnsignedByteType>composeReal(
					sources,
					(c, target) -> {
						target.set(0);
						for (int i = 0; i < sources.size(); ++i)
							target.add(c.get(i));
					},
					new UnsignedByteType());
			N5Utils.saveNonEmptyBlock(
					Views.interval(
							composite,
							gridBlockInterval),
					n5Output,
					datasetNameOutput,
					new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
					gridBlock[2],
					new UnsignedByteType());
			break;
		}
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		// TODO: doesn't work right now, see saalfeld's change to ViewAlignedSlabSeries
		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName( "SparkExportAlignedSlabSeries" );
        final JavaSparkContext sc = new JavaSparkContext(conf);
        sc.setLogLevel("ERROR");

        final String n5PathInput = options.getN5InputPath();
		final String group = options.getGroup();

		final N5Reader n5Input = new N5FSReader(n5PathInput);
		final String[] transformDatasetNames = n5Input.getAttribute(group, "transforms", String[].class);

		final List<Long> topOffsets = options.getTopOffsets();
		final List<Long> botOffsets = options.getBotOffsets();
		final List<String> datasetNames = options.getInputDatasets();

		final double[] boundsMin = n5Input.getAttribute(group, "boundsMin", double[].class);
		final double[] boundsMax = n5Input.getAttribute(group, "boundsMax", double[].class);

		/* size */
		final long[] fMin = Grid.floorScaled(boundsMin, 1);
		final long[] fMax = Grid.ceilScaled(boundsMax, 1);

		long depth = 0;
		for (int i = 0; i < topOffsets.size(); ++i) {
			long botOffset = botOffsets.get(i);
			if (botOffset < 0) {
				final long[] datasetDimensions = n5Input.getAttribute(datasetNames.get(i) + "/s0", "dimensions", long[].class);
				botOffset = datasetDimensions[2] + botOffset - 1;
				botOffsets.set(i, botOffset);
			}
			depth += botOffset - topOffsets.get(i) + 1;
		}

		for (int i = 0; i < datasetNames.size(); i++) {
			System.out.println("dataset " + datasetNames.get(i) + " has botOffset " + botOffsets.get(i) +
							   " and topOffset " + topOffsets.get(i));
		}

		final long[] min = new long[] {
				fMin[0],
				fMin[1],
				0
		};

		final long[] max = new long[] {
				fMax[0],
				fMax[1],
				depth - 1
		};

		final long[] dimensions = new long[] {
				max[0] - min[0] + 1,
				max[1] - min[1] + 1,
				depth
		};

		System.out.println( "final volume: " + Util.printCoordinates( dimensions ));

		final String datasetNameOutput = options.getOutputDataset();
		final int[] blockSize = options.getBlockSize();
		final boolean normalizeContrast = options.normalizeContrast();

		// Filter the full set of input blocks down to only those that should be processed.
		// Filtering removes empty source blocks and (optionally) blocks for non-included re-run tasks.
		final int[] gridBlockSize = new int[] { blockSize[0] * 8, blockSize[1] * 8, blockSize[2] };

		final long minZForRun;
		final long maxZForRun;
		if (options.zBatchTotalCount == null) {
			minZForRun = min[2];
			maxZForRun = max[2];
		} else {
			if (options.explainPlan) {
				System.out.println("explaining all zBatch information: ");
				for (int zBatch = 1; zBatch <= options.zBatchTotalCount; zBatch++) {
					getMinAndMaxZForBatch(zBatch,
										  options.zBatchTotalCount,
										  dimensions[2],
										  gridBlockSize[2]);
				}
				System.out.println("\n\nzBatch information for current run batch is below\n");
			}
			final long[] minAndMax = getMinAndMaxZForBatch(options.zBatchForCurrentRun,
														   options.zBatchTotalCount,
														   dimensions[2],
														   gridBlockSize[2]);
			minZForRun = minAndMax[0];
			maxZForRun = minAndMax[1];
		}

		final List<long[][]> gridFull = Grid.create(dimensions, gridBlockSize, blockSize);

		final String gridBlockSizeString = " " + gridBlockSize[0] + "x" + gridBlockSize[1] + "x" + gridBlockSize[2];
		System.out.println("SparkExportAlignedSlabSeries: original grid contains " +
						   gridFull.size() + gridBlockSizeString + " blocks");

		final JavaRDD<long[][]> pGridFull = sc.parallelize(gridFull);

		final Set<Long> zGridPositions = new HashSet<>(options.zGridPositions);

		if (zGridPositions.size() > 0) {
			System.out.println("SparkExportAlignedSlabSeries: filtering zGridPositions " +
							   zGridPositions.stream().sorted().collect(Collectors.toList()));
		}

		final boolean explainPlan = options.explainPlan;
		final List<long[][]> grid = pGridFull.filter(gridBlock -> isBlockIncluded(datasetNames,
																				  topOffsets,
																				  botOffsets,
																				  gridBlock,
																				  minZForRun,
																				  maxZForRun,
																				  zGridPositions,
																				  explainPlan)).collect();

		System.out.println("SparkExportAlignedSlabSeries: filtered grid contains " +
						   grid.size() + gridBlockSizeString + " blocks");
		if (grid.size() > 1) {
			System.out.println("SparkExportAlignedSlabSeries: first grid block is " +
							   printBlock(gridFull.get(0)));
			System.out.println("SparkExportAlignedSlabSeries: middle grid block is " +
							   printBlock(gridFull.get(gridFull.size() / 2)));
			System.out.println("SparkExportAlignedSlabSeries: last grid block is " +
							   printBlock(gridFull.get(gridFull.size() - 1)));
		}

		// final List<long[][]> grid = Grid.create(dimensions, new int[]{blockSize[0] * 8, blockSize[1] * 8, blockSize[2]}, blockSize);

		// if explainPlan option is not set, go ahead and generate output ...
		if (! explainPlan) {

			/* create output dataset */
			final String n5PathOutput = options.getN5OutputPath();
			final N5Writer n5Output = new N5FSWriter(n5PathOutput);

			if (n5Output.exists(datasetNameOutput)) {
				// if dataset already exists (e.g. from prior batch run), verify consistency of attributes
				final DatasetAttributes datasetAttributes = n5Output.getDatasetAttributes(datasetNameOutput);
				verifyConsistency(datasetNameOutput + " dimensions",
								  Arrays.toString(dimensions), Arrays.toString(datasetAttributes.getDimensions()));
				verifyConsistency(datasetNameOutput + " blockSize",
								  Arrays.toString(blockSize), Arrays.toString(datasetAttributes.getBlockSize()));
				verifyConsistency(datasetNameOutput + " dataType",
								  DataType.UINT8.toString(), datasetAttributes.getDataType().toString());
				verifyConsistency(datasetNameOutput + " compression",
								  GzipCompression.class.toString(),
								  datasetAttributes.getCompression().getClass().toString());
			} else {
				n5Output.createDataset(datasetNameOutput, dimensions, blockSize, DataType.UINT8, new GzipCompression());
			}

			final JavaRDD<long[][]> pGrid = sc.parallelize(grid);

			pGrid.foreach(
					gridBlock -> saveBlock(
							n5PathInput,
							n5PathOutput,
							datasetNames,
							group,
							datasetNameOutput,
							transformDatasetNames,
							topOffsets,
							botOffsets,
							min,
							max,
							dimensions,
							blockSize,
							gridBlock,
							normalizeContrast));

			n5Output.close();
		}

		sc.close();
		n5Input.close();
	}

	public static long[] getMinAndMaxZForBatch(final int zBatchForCurrentRun,
											   final int zBatchTotalCount,
											   final long lastZForVolume,
											   final long zSizeForBlock) {

		final int batchIndex = zBatchForCurrentRun - 1; // adapt one-based command line parameter

		// ensure each batch is block aligned and evenly distribute remainder blocks among batches ...

		final long lastGridBlockForVolume = (long) Math.ceil((double) lastZForVolume / zSizeForBlock);
		final long blocksPerBatch = lastGridBlockForVolume / zBatchTotalCount;
		final long remainder = lastGridBlockForVolume % zBatchTotalCount;

		final long normalBatchZSize = (blocksPerBatch * zSizeForBlock);
		final long extraBlockBatchZSize = ((blocksPerBatch + 1) * zSizeForBlock);

		final long zSizeForRun = (batchIndex < remainder) ? extraBlockBatchZSize : normalBatchZSize;
		final long extraBlockBatchCount = (batchIndex < remainder) ? batchIndex : remainder;
		final long normalBlockBatchCount = (batchIndex < remainder) ? 0 : (batchIndex - remainder);

		final long minZForRun = (extraBlockBatchCount * extraBlockBatchZSize) +
								(normalBlockBatchCount * normalBatchZSize);
		final long maxZForRun = minZForRun + zSizeForRun - 1;

		final double blocksForRun = (double) zSizeForRun / zSizeForBlock;

		System.out.println("getMinAndMaxZForBatch: returning z " + minZForRun + " to " + maxZForRun +
						   " (" + blocksForRun + " input blocks) for zBatch " + zBatchForCurrentRun +
						   " of " + zBatchTotalCount);

		return new long[] { minZForRun, maxZForRun };
	}

	public static String printBlock(final long[][] gridBlock) {
		return "{ offset: " + Util.printCoordinates(gridBlock[0]) + ", size: " + Util.printCoordinates(gridBlock[1]) +
			   ", position: " + Util.printCoordinates(gridBlock[2]) + " }";
	}

	private static void verifyConsistency(final String context,
										  final String expected,
										  final String actual) throws IllegalStateException {
		if (! expected.equals(actual)) {
			throw new IllegalStateException(context + " is " + actual + " but should be " + expected);
		}
	}
}
