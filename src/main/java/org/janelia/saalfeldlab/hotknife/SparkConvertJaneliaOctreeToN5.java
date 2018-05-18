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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.ShortArrayDataBlock;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import ij.IJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ShortArray;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * Export a render stack to N5.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkConvertJaneliaOctreeToN5 {

	final static public String transformFile = "transforms.txt";
	final static public String tifNameFormat = "default.%d.tif";

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--path", required = true, usage = "source path")
		private final String path = null;

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "--n5Group", required = true, usage = "N5 group, e.g. /Sec26")
		private final String groupName = null;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				parsedSuccessfully = true;
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		/**
		 * @return the baseUrl
		 */
		public String getPath() {
			return path;
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
		public String getGroupName() {
			return groupName;
		}
	}

	private static class Transforms {

		final public long ox;
		final public long oy;
		final public long oz;
		final public double sx;
		final public double sy;
		final public double sz;
		final public int nl;

		public Transforms(
				final long ox,
				final long oy,
				final long oz,
				final double sx,
				final double sy,
				final double sz,
				final int nl) {

			this.ox = ox;
			this.oy = oy;
			this.oz = oz;
			this.sx = sx;
			this.sy = sy;
			this.sz = sz;
			this.nl = nl;
		}
	}

	private static int[] getBlockSize(final String path) {

		final ImagePlus imp = IJ.openImage(path + "/" + String.format(tifNameFormat, 0));
		return new int[] {imp.getWidth(), imp.getHeight(), imp.getStackSize()};
	}

	private static int getNumChannels(final String path) throws IOException {

		final Path p = Paths.get(path);
		final AtomicInteger c = new AtomicInteger(0);
		while (Files.list(p).anyMatch(entry -> entry.endsWith(String.format(tifNameFormat, c.get()))))
			c.incrementAndGet();
		return c.get();
	}

	private static void listOctreeBlocks(
			final String path,
			int depth,
			final long[] offset,
			final List<String> paths,
			final List<long[]> gridCoordinates) {

		if (!Files.exists(Paths.get(path)))
			return;
		--depth;
		if (depth > 0) {
			final int step = 1 << depth;
			listOctreeBlocks(
					path + "/1",
					depth,
					offset,
					paths,
					gridCoordinates);
			listOctreeBlocks(
					path + "/2",
					depth,
					new long[]{offset[0] + step, offset[1], offset[2]},
					paths,
					gridCoordinates);
			listOctreeBlocks(
					path + "/3",
					depth,
					new long[]{offset[0], offset[1] + step, offset[2]},
					paths,
					gridCoordinates);
			listOctreeBlocks(
					path + "/4",
					depth,
					new long[]{offset[0] + step, offset[1] + step, offset[2]},
					paths,
					gridCoordinates);
			listOctreeBlocks(
					path + "/5",
					depth,
					new long[]{offset[0], offset[1], offset[2] + step},
					paths,
					gridCoordinates);
			listOctreeBlocks(
					path + "/6",
					depth,
					new long[]{offset[0] + step, offset[1], offset[2] + step},
					paths,
					gridCoordinates);
			listOctreeBlocks(
					path + "/7",
					depth,
					new long[]{offset[0], offset[1] + step, offset[2] + step},
					paths,
					gridCoordinates);
			listOctreeBlocks(
					path + "/8",
					depth,
					new long[]{offset[0] + step, offset[1] + step, offset[2] + step},
					paths,
					gridCoordinates);
		}
		else {
			paths.add(path);
			gridCoordinates.add(offset);
		}

	}

	private static Transforms getTransforms(final String path) throws IOException {

		try (Stream<String> stream = Files.lines(Paths.get(path + "/" + transformFile))) {

			Iterator<String> it = stream.iterator();
			return new Transforms(
					Long.parseLong(it.next().substring(4)),
					Long.parseLong(it.next().substring(4)),
					Long.parseLong(it.next().substring(4)),
					Double.parseDouble(it.next().substring(4)),
					Double.parseDouble(it.next().substring(4)),
					Double.parseDouble(it.next().substring(4)),
					Integer.parseInt(it.next().substring(4)));
		}
	}

	public static final void convertJaneliaOctree(
			final JavaSparkContext sc,
			final String path,
			final String n5Path,
			final String groupName) throws IOException {

		/* find out number of channels block-sizes */
		final int nChannels = getNumChannels(path);

		/* find out blocksize */
		final int[] blockSize = getBlockSize(path);
		final int nPixels = blockSize[0] * blockSize[1] * blockSize[2];

		/* get transforms data */
		Transforms transforms = getTransforms(path);
		long scale = 1 << (transforms.nl - 1);
		final long[] dimensions = new long[] {
				blockSize[0] * scale,
				blockSize[1] * scale,
				blockSize[2] * scale};

		/* create datasets */
		final N5Writer n5 = new N5FSWriter(n5Path);
		for (int c = 0; c < nChannels; ++c) {
			n5.createDataset(
					groupName + "/c" + c,
					dimensions,
					blockSize,
					DataType.UINT16,
					new GzipCompression());
		}

		/* list blocks */
		final ArrayList<String> paths = new ArrayList<>();
		final ArrayList<long[]> gridCoordinates = new ArrayList<>();
		listOctreeBlocks(
				path,
				transforms.nl - 1,
				new long[] {0, 0, 0},
				paths,
				gridCoordinates);

		/* do it */
		final JavaRDD<String> rddPaths = sc.parallelize(paths);
		final JavaRDD<long[]> rddGridCoordinates = sc.parallelize(gridCoordinates);
		JavaPairRDD<String, long[]> zipped = rddPaths.zip(rddGridCoordinates);

		zipped.foreach(block -> {

			final N5Writer n5Writer = new N5FSWriter(n5Path);
			final short[] data = new short[nPixels];
			final ArrayImg<UnsignedShortType, ShortArray> img = ArrayImgs.unsignedShorts(data, blockSize[0], blockSize[1], blockSize[2]);
			final DatasetAttributes datasetAttributes = new DatasetAttributes(dimensions, blockSize, DataType.UINT16, new GzipCompression());

			for (int c = 0; c < nChannels; ++c) {
				final ImagePlus imp = IJ.openImage(block._1() + "/" + String.format(tifNameFormat, c));
				Cursor<UnsignedShortType> cSource = (Cursor<UnsignedShortType>)ImagePlusImgs.from(imp).cursor();
				ArrayCursor<UnsignedShortType> cTarget = img.cursor();
				boolean isEmpty = true;
				while (cTarget.hasNext()) {
					final short value = cSource.next().getShort();
					isEmpty &= value == 0;
					cTarget.next().set(value);
				}
				if (!isEmpty)
					n5Writer.writeBlock(
							path + "/c" + c,
							datasetAttributes,
							new ShortArrayDataBlock(blockSize, block._2(), data));
			}
		});
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName("SparkConvertCATMAIDStackToN5");
		final JavaSparkContext sc = new JavaSparkContext(conf);

		convertJaneliaOctree(sc, options.getPath(), options.getN5Path(), options.getGroupName());

		sc.close();
	}
}
