package org.janelia.saalfeldlab.hotknife.brain;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.AbstractOptions;
import org.janelia.saalfeldlab.hotknife.util.Grid;
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
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

/**
 * created from PlaygroundStitch3
 * 
 * @author preibischs
 *
 */
public class SparkExportBrainVNC {

	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions
			implements Serializable {

		@Option(name = "--outputDatasetRoot", required = true, usage = "Output dataset root, e.g. /full_cns")
		String outputDatasetRoot;

		@Option(name = "--n5Level", required = true, usage = "e.g. 5 or 0")
		Integer n5Level;

		@Option(name = "--zBatch",
				usage = "Separate blocks into batches by z and run only one batch.  " +
						"Format is <one-based-batch-for-current-run>:<total-batch-count>.  " +
						"For example, specify 1:10 to run first batch of ten and 10:10 to run last batch of ten."
		)
		String zBatchString = null;
		Integer zBatchForCurrentRun = null;
		Integer zBatchTotalCount = null;

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
	}

	public static void main( String[] args ) throws IOException
	{
		final Options options = new Options(args);

		final String datasetNameOutput = options.outputDatasetRoot + "/s" + options.n5Level;

		final String n5Path = "/nrs/flyem/render/n5/Z0720_07m_BR/40-06-final/s" + options.n5Level;
		final String imgGroup = ".";
		final String brainVNCsurface = "/nrs/flyem/render/n5/Z0720_07m_VNC/heightfields_fix/brain-VNC/pass1_preibischs/min";
		final String brainVNCsurfaceGroup = ".";
		final String brainVNCdeformationField = "/nrs/flyem/render/n5/Z0720_07m_VNC/surface-align-VNC/06-37/run_20220908_121000/pass12_edit/";
		final String brainVNCdeformationFieldGroup = "/flat.Sec37.bot.face";
		final String VNCn5Path = "/nrs/flyem/render/n5/Z0720_07m_VNC/";
		final String VNCimgGroup = "final-align-VNC/20220922_120102/s" + options.n5Level;

		final String n5PathOutput = "/nrs/flyem/render/n5/Z0720_07m_CNS.n5";

		final SparkConf conf = new SparkConf().setAppName( "SparkExportBrainVNC" );
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);

		saveSpark(sparkContext,
				  options,
				  n5Path,
				  imgGroup,
				  options.n5Level,
				  brainVNCsurface,
				  brainVNCsurfaceGroup,
				  brainVNCdeformationField,
				  brainVNCdeformationFieldGroup,
				  VNCn5Path,
				  VNCimgGroup,
				  n5PathOutput,
				  datasetNameOutput);
	}

	public static boolean isBlockIncluded(final long[][] gridBlock,
										  final long minZForRun,
										  final long maxZForRun,
										  final boolean explainPlan) {

		final long minZForBlock = gridBlock[0][2];
		final long maxZForBlock = gridBlock[0][2] + gridBlock[1][2] - 1;
		final boolean isBlockWithinZRangeForRun =
				((minZForBlock >= minZForRun) && (maxZForBlock <= maxZForRun));

		if (explainPlan || isBlockWithinZRangeForRun) {
			System.out.println("isBlockIncluded returning " + isBlockWithinZRangeForRun +
							   " for block " + printBlock(gridBlock));
		}

		return isBlockWithinZRangeForRun;
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

	public static List<long[][]> buildGrid(final Options options,
										   final long[] dimensions,
										   final int[] blockSize,
										   final JavaSparkContext sparkContext) {

		// Filter the full set of input blocks down to only those that should be processed.
		// Filtering removes empty source blocks and (optionally) blocks for non-included re-run tasks.
		final int[] gridBlockSize = new int[]{blockSize[0] * 8, blockSize[1] * 8, blockSize[2]};

		final long minZForRun;
		final long maxZForRun;
		if (options.zBatchTotalCount == null) {
			minZForRun = 0;
			maxZForRun = dimensions[2];
		} else {
			if (options.explainPlan) {
				System.out.println("buildGrid: explaining all zBatch information: ");
				for (int zBatch = 1; zBatch <= options.zBatchTotalCount; zBatch++) {
					getMinAndMaxZForBatch(zBatch,
										  options.zBatchTotalCount,
										  dimensions[2],
										  gridBlockSize[2]);
				}
				System.out.println("\n\nbuildGrid: zBatch information for current run batch is below\n");
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
		System.out.println("buildGrid: original grid contains " +
						   gridFull.size() + gridBlockSizeString + " blocks");

		final JavaRDD<long[][]> pGridFull = sparkContext.parallelize(gridFull);

		final boolean explainPlan = options.explainPlan;
		final List<long[][]> grid = pGridFull.filter(gridBlock -> isBlockIncluded(gridBlock,
																				  minZForRun,
																				  maxZForRun,
																				  explainPlan)).collect();

		System.out.println("buildGrid: filtered grid contains " + grid.size() + gridBlockSizeString + " blocks");
		if (grid.size() > 1) {
			System.out.println("buildGrid: first grid block is " + printBlock(gridFull.get(0)));
			System.out.println("buildGrid: middle grid block is " + printBlock(gridFull.get(gridFull.size() / 2)));
			System.out.println("buildGrid: last grid block is " + printBlock(gridFull.get(gridFull.size() - 1)));
		}

		return grid;
	}

	public static void saveSpark(
			final JavaSparkContext sparkContext,
			final Options options,
			final String n5Path,
			final String imgGroup,
			final int n5Level,
			final String brainVNCsurface,
			final String brainVNCsurfaceGroup,
			final String brainVNCdeformationField,
			final String brainVNCdeformationFieldGroup,
			final String VNCn5Path,
			final String VNCimgGroup,
			final String n5PathOutput,
			final String datasetNameOutput) throws IOException
	{
		final N5Reader n5Input = new N5FSReader(n5Path);

		// ---------------------------
		// determine new bounding box
		// ---------------------------
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(new N5FSReader(n5Path), imgGroup);
		final RandomAccessibleInterval<UnsignedByteType> imgVNC = N5Utils.openVolatile(new N5FSReader(VNCn5Path), VNCimgGroup);

		final FlattenAndUnwarp fau = SparkTransformBrainS5.buildFlattenAndUnwarp(
				n5Level,
				brainVNCsurface,
				brainVNCsurfaceGroup,
				brainVNCdeformationField,
				brainVNCdeformationFieldGroup,
				imgBrain);

		final Interval bbox = Intervals.union(imgBrain, PlaygroundStitch3.orientVNC(fau, imgVNC));

		System.out.println( "Brain interval at s" + n5Level + ": " + Util.printInterval(imgBrain));
		System.out.println( "Brain+VNC interval at s" + n5Level + ": " + Util.printInterval(bbox));

		final long[] dimensions = bbox.dimensionsAsLongArray();
		final int[] blockSize = n5Input.getAttribute(imgGroup, "blockSize", int[].class );
		System.out.println( "dimensions: " + Util.printCoordinates( dimensions ) +
							", blocksize: " + Util.printCoordinates(blockSize) );

		final List<long[][]> grid = buildGrid(options,
											  dimensions,
											  blockSize,
											  sparkContext);

		System.out.println( grid.size() + " jobs." );

		N5Writer n5Output = null;

		if (! options.explainPlan) {

			/* create output dataset */
			n5Output = new N5FSWriter(n5PathOutput);
			n5Output.createDataset(datasetNameOutput, dimensions, blockSize, DataType.UINT8, new GzipCompression());

			final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid);

			pGrid.foreach(
					gridBlock -> saveBlock(n5Path,
										   imgGroup,
										   n5Level,
										   brainVNCsurface,
										   brainVNCsurfaceGroup,
										   brainVNCdeformationField,
										   brainVNCdeformationFieldGroup,
										   VNCn5Path,
										   VNCimgGroup,
										   dimensions,
										   blockSize,
										   gridBlock,
										   n5PathOutput,
										   datasetNameOutput));
		}

		sparkContext.close();

		n5Input.close();

		if (n5Output != null) {
			n5Output.close();
		}
	}

	public static void saveBlock(
			final String n5Path,
			final String imgGroup,
			final int n5Level,
			final String brainVNCsurface,
			final String brainVNCsurfaceGroup,
			final String brainVNCdeformationField,
			final String brainVNCdeformationFieldGroup,
			final String VNCn5Path,
			final String VNCimgGroup,
			final long[] dimensions,
			final int[] blockSize,
			final long[][] gridBlock,
			final String n5PathOutput,
			final String datasetNameOutput) throws IOException
	{

		final N5Writer n5Output = new N5FSWriter(n5PathOutput);

		final N5Reader n5 = new N5FSReader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(n5, imgGroup);

		final FlattenAndUnwarp fau = SparkTransformBrainS5.buildFlattenAndUnwarp(
				n5Level,
				brainVNCsurface,
				brainVNCsurfaceGroup,
				brainVNCdeformationField,
				brainVNCdeformationFieldGroup,
				imgBrain);

		final N5Reader VNCn5 = new N5FSReader(VNCn5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgVNC = N5Utils.openVolatile(VNCn5, VNCimgGroup);
		final RandomAccessibleInterval<UnsignedByteType> viewVNC = PlaygroundStitch3.orientVNC(fau, imgVNC);
		final Interval bbox = Intervals.union(imgBrain, viewVNC);

		final RandomAccessibleInterval<UnsignedByteType> merged =
				PlaygroundStitch3.merge(fau.getCompositeUnwarpedCrop(), viewVNC, bbox);

		// save it given the grid[][] location to the new n5
		final FinalInterval gridBlockInterval =
				Intervals.createMinSize(
						gridBlock[0][0],
						gridBlock[0][1],
						gridBlock[0][2],
						gridBlock[1][0],
						gridBlock[1][1],
						gridBlock[1][2]);

		N5Utils.saveNonEmptyBlock(
				Views.interval(merged, gridBlockInterval),
				n5Output,
				datasetNameOutput,
				new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
				gridBlock[2],
				new UnsignedByteType());

		n5Output.close();
		n5.close();
	}
}
