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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import ij.IJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkConvertCATMAIDStackToN5 {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--urlFormat", required = true, usage = "Input URL format for CATMAID stack, e.g. /nrs/saalfeld/FAFB00/v14_align_tps_20170818_dmg/%6$dx%7$d/%1$d/%5$d/%5$d.%1$d.%8$d.%9$d.png")
		private String urlFormat = null;

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "--n5Dataset", required = true, usage = "N5 dataset, e.g. /Sec26")
		private String datasetName = null;

		@Option(name = "--tileSize", required = true, usage = "Size of input tiles, e.g. 8192,8192")
		private String tileSizeString = null;
		private int[] tileSize;

		@Option(name = "--min", required = false, usage = "Min coordinate of the output volume, e.g. 0,0,0")
		private String minString = null;
		private long[] min;

		@Option(name = "--size", required = true, usage = "Size of the output volume, e.g. 10000,20000,30000")
		private String sizeString = null;
		private long[] size;

		@Option(name = "--blockSize", required = true, usage = "Size of output blocks, e.g. 256,256,26")
		private String blockSizeString = null;
		private int[] blockSize;

		@Option(name = "--firstSlice", required = false, usage = "first slice index (if not 0)")
		private long firstSliceIndex = 0;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			min = new long[3];
			size = new long[3];
			tileSize = new int[2];
			blockSize = new int[3];
			try {
				parser.parseArgument(args);

				if (minString == null)
					Arrays.fill(min, 0);
				else
					parseCSLongArray(minString, min);

				parseCSLongArray(sizeString, size);
				parseCSIntArray(blockSizeString, blockSize);
				parseCSIntArray(tileSizeString, tileSize);

				parsedSuccessfully = true;
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		/**
		 * @return the min
		 */
		public long[] getMin() {
			return min;
		}

		/**
		 * @return the urlFormat
		 */
		public String getUrlFormat() {
			return urlFormat;
		}

		/**
		 * @return the n5Path
		 */
		public String getN5Path() {
			return n5Path;
		}

		/**
		 * @return the datasetName
		 */
		public String getDatasetName() {
			return datasetName;
		}

		/**
		 * @return the size
		 */
		public long[] getSize() {
			return size;
		}

		/**
		 * @return the blockSize
		 */
		public int[] getBlockSize() {
			return blockSize;
		}

		/**
		 * @return the tileSize
		 */
		public int[] getTileSize() {
			return tileSize;
		}

		/**
		 * @return the firstSlice
		 */
		public long getFirstSliceIndex() {
			return firstSliceIndex;
		}
	}

	public static final void saveCATMAIDStack(
			final JavaSparkContext sc,
			final String urlFormat,
			final int[] tileSize,
			final String n5Path,
			final String datasetName,
			final long[] min,
			final long[] size,
			final int[] blockSize,
			final long firstSliceIndex) throws IOException {

		final N5Writer n5 = new N5FSWriter(n5Path);

		n5.createDataset(
				datasetName,
				size,
				blockSize,
				DataType.UINT8,
				new GzipCompression());

		/*
		 * grid block size for parallelization to minimize double loading of
		 * blocks
		 */
		final int[] gridBlockSize = new int[] {
				Math.max(blockSize[0], tileSize[0]),
				Math.max(blockSize[1], tileSize[1]),
				blockSize[2] };

		final JavaRDD<long[][]> rdd = sc.parallelize(
				Grid.create(
						new long[] {
								size[0],
								size[1],
								size[2] },
						gridBlockSize,
						blockSize));

		rdd.foreach(gridBlock -> {

			/* tile coordinates */
			final long col = (gridBlock[0][0] + min[0]) / tileSize[0];
			final long row = (gridBlock[0][1] + min[1]) / tileSize[1];

			/* assume we can fit it in an array */
			final ArrayImg<UnsignedByteType, ByteArray> block = ArrayImgs.unsignedBytes(gridBlock[1]);
			boolean hasData = false;
			for (int z = 0; z < block.dimension(2); ++z) {

				final String urlString = String.format(
						urlFormat,
						0,
						1,
						gridBlock[0][0] + min[0],
						gridBlock[0][1] + min[1],
						gridBlock[0][2] + min[2] + firstSliceIndex + z,
						tileSize[0],
						tileSize[1],
						row,
						col);

				if (Files.exists(Paths.get(urlString))) {

					final ImagePlus imp = IJ.openImage(urlString);
					if (imp == null)
						continue;
					hasData = true;

					final IntervalView<UnsignedByteType> outSlice = Views.hyperSlice(block, 2, z);
					@SuppressWarnings({ "unchecked" })
					final IterableInterval<UnsignedByteType> inSlice = Views
							.flatIterable(Views.interval((RandomAccessible<UnsignedByteType>) (Object) ImagePlusImgs.from(imp), outSlice));

					final Cursor<UnsignedByteType> in = inSlice.cursor();
					final Cursor<UnsignedByteType> out = outSlice.cursor();
					while (out.hasNext())
						out.next().set(in.next());
				}
			}

			if (hasData) {
				final N5FSWriter n5Writer = new N5FSWriter(n5Path);
				N5Utils.saveNonEmptyBlock(block, n5Writer, datasetName, gridBlock[2], new UnsignedByteType(0));
			}
		});
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName("SparkConvertCATMAIDStackToN5");
		final JavaSparkContext sc = new JavaSparkContext(conf);

		/* parallelize over slices */
		saveCATMAIDStack(
				sc,
				options.getUrlFormat(),
				options.getTileSize(),
				options.getN5Path(),
				options.getDatasetName(),
				options.getMin(),
				options.getSize(),
				options.getBlockSize(),
				options.getFirstSliceIndex());

		sc.close();

		// final N5Writer n5 = N5.openFSWriter(options.getN5Path());

		/* remove should be parallelized */
		// n5.remove(slicesDatasetName);

		// final int numProc = Runtime.getRuntime().availableProcessors();
		// final SharedQueue queue = new SharedQueue(Math.max(1, numProc / 2));
		//
		// BdvFunctions.show(
		// VolatileViews.wrapAsVolatile(
		// (RandomAccessibleInterval<UnsignedByteType>)N5Utils.openVolatile(n5,
		// options.datasetName),
		// queue),
		// "export");
	}
}
