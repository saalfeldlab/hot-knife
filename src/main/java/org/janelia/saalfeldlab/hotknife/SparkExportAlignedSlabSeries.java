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
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
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
import org.kohsuke.args4j.CmdLineException;
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
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkExportAlignedSlabSeries {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput", required = true, usage = "Input N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5PathInput = null;

		@Option(name = "--n5PathOutput", required = true, usage = "Output N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5PathOutput = null;

		@Option(name = "-i", aliases = {"--n5DatasetInput"}, required = true, usage = "N5 slab dataset, e.g. /slab-22/raw/s0")
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

		@Option(name = "-n", aliases = {"--normalizeContrast"}, required = false, usage = "optionally normalize contrast")
		private boolean normalizeContrast;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				parsedSuccessfully = datasetsInput.size() == topOffsets.size() && datasetsInput.size() == botOffsets.size();
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
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

			final long topOffset = topOffsets.get(i);
			final long botOffset = botOffsets.get(i);
			final long depth = botOffset - topOffset + 1;

			/* do not include blocks that do not intersect with the gridBlock */
			if (!((gridBlock[0][2] > zOffset + depth) | (gridBlock[0][2] + gridBlock[1][2] < zOffset))) {

				final RealTransform top = Transform.loadScaledTransform(n5Input, group + "/" + transformDatasetNames[i * 2]);
				final RealTransform bot = Transform.loadScaledTransform(n5Input, group + "/" + transformDatasetNames[i * 2 + 1]);
				final RealTransform transition =
						new ClippedTransitionRealTransform(
								top,
								bot,
								topOffset,
								botOffset);
				
				final RealTransformSequence transformSequence = new RealTransformSequence();
				
				AffineTransform3D rigid = new AffineTransform3D();
				//make rigid
				transformSequence.add(rigid.inverse());
				transformSequence.add(transition);
				

				final long[] cropMin = new long[] {min[0], min[1], topOffset};
				final long[] cropMax = new long[] {max[0], max[1], botOffset};

				final FinalInterval cropInterval = new FinalInterval(
						cropMin,
						cropMax);

				final String datasetName = datasetNames.get(i);

				final RandomAccessibleInterval<UnsignedByteType> source;

				if ( normalizeContrast )
				{
					final RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5Input, datasetName);
	
					final int blockRadius = (int)Math.round(511);
	
					final ImageJStackOp<UnsignedByteType> cllcn =
							new ImageJStackOp<>(
									Views.extendZero(sourceRaw),
									(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 10, 0.5f, true, true, true),
									blockRadius,
									0,
									255);
	
					source = Lazy.process(
							sourceRaw,
							new int[] {128, 128, 16},
							new UnsignedByteType(),
							AccessFlags.setOf(AccessFlags.VOLATILE),
							cllcn);
				}
				else
				{
					source =  N5Utils.open(n5Input, datasetName);
				}

				final RandomAccessibleInterval<UnsignedByteType> transformedSource = Transform.createTransformedInterval(
					source,
					cropInterval,
					transformSequence,
					new UnsignedByteType(0));

				final IntervalView<UnsignedByteType> extendedTransformedSource =
						Views.interval(
							Views.extendValue(
									Views.translate(
											Views.zeroMin(transformedSource),
											0, 0, zOffset),
									new UnsignedByteType(0)),
							new FinalInterval(min, max));

				// flipping X-Z axes
				// TODO: remove
				sources.add( Views.permute( extendedTransformedSource, 0, 2 ) );
			}

			zOffset += depth;
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
					UnsignedByteType::new);
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

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

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

		// flipping x-z axes
		// TODO: Remove
		long tmp = min[ 2 ];
		min[ 2 ] = min[ 0 ];
		min[ 0 ] = tmp;
		tmp = max[ 2 ];
		max[ 2 ] = max[ 0 ];
		max[ 0 ] = tmp;
		tmp = dimensions[ 2 ];
		dimensions[ 2 ] = dimensions[ 0 ];
		dimensions[ 0 ] = tmp;


		final String datasetNameOutput = options.getOutputDataset();
		final int[] blockSize = options.getBlockSize();
		final boolean normalizeContrast = options.normalizeContrast();

		final String n5PathOutput = options.getN5OutputPath();

		/* create output dataset */
		final N5Writer n5Output = new N5FSWriter(n5PathOutput);
		n5Output.createDataset(datasetNameOutput, dimensions, blockSize, DataType.UINT8, new GzipCompression());

		final List<long[][]> grid = Grid.create(dimensions, new int[]{blockSize[0] * 8, blockSize[1] * 8, blockSize[2]}, blockSize);

		final JavaRDD<long[][]> pGrid = sc.parallelize(grid);

		pGrid.foreach(
				gridBlock -> {
					saveBlock(
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
							normalizeContrast);
				});

		sc.close();

		n5Input.close();
		n5Output.close();
	}
}
