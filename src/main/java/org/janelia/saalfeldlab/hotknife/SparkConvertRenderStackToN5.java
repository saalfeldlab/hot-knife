/*
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

import ij.process.ColorProcessor;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import static org.janelia.saalfeldlab.n5.spark.downsample.scalepyramid.N5ScalePyramidSpark.downsampleScalePyramid;

/**
 * Export a render stack to N5.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkConvertRenderStackToN5 {

	final static public String ownerFormat = "%s/owner/%s";
	final static public String stackFormat = ownerFormat + "/project/%s/stack/%s";
	final static public String boundingBoxFormat = stackFormat + "/z/%d/box/%d,%d,%d,%d,%f";
	final static public String renderParametersFormat = boundingBoxFormat + "/render-parameters";

	@SuppressWarnings({"serial", "FieldCanBeLocal"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--baseUrl", required = true, usage = "Render stack base URL")
		private String baseUrl = "http://tem-services.int.janelia.org:8080/render-ws/v1";

		@Option(name = "--owner", required = true, usage = "Render stack owner")
		private String owner = "flyTEM";

		@Option(name = "--project", required = true, usage = "Render stack project")
		private String project = "FAFB00";

		@Option(name = "--stack", required = true, usage = "Render stack stack")
		private String stack = "v12_align_tps";

		@Option(name = "--filter", usage = "Render stack filter")
		private boolean filter = false;

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "--n5Dataset", required = true, usage = "N5 dataset, e.g. /Sec26")
		private String datasetName = null;

		@Option(name = "--tileSize", required = true, usage = "Size of input tiles, e.g. 8192,8192")
		private String tileSizeString = null;
		private int[] tileSize;

		@Option(name = "--tempTileSize", usage = "Size of temporary output tiles, must be an integer multiple of blockSize, e.g. 4096,4096")
		private String tempTileSizeString = null;
		private int[] tempTileSize;

		@Option(name = "--min", usage = "Min coordinate of the output volume, e.g. 0,0,0")
		private String minString = null;
		private long[] min;

		@Option(name = "--size", required = true, usage = "Size of the output volume, e.g. 10000,20000,30000")
		private String sizeString = null;
		private long[] size;

		@Option(name = "--blockSize", required = true, usage = "Size of output blocks, e.g. 128,128,128")
		private String blockSizeString = null;
		private int[] blockSize;

		@Option(name = "--factors", usage = "Specifies generates a scale pyramid with given factors with relative scaling between factors, e.g. 2,2,2")
		private String downsamplingFactorsString = null;
		private int[] downsamplingFactors;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			min = new long[3];
			size = new long[3];
			tileSize = new int[2];
			tempTileSize = null;
			blockSize = new int[3];
			downsamplingFactors = null;
			try {
				parser.parseArgument(args);

				if (minString == null)
					Arrays.fill(min, 0);
				else
					parseCSLongArray(minString, min);

				parseCSLongArray(sizeString, size);
				parseCSIntArray(blockSizeString, blockSize);
				parseCSIntArray(tileSizeString, tileSize);
				if (downsamplingFactorsString != null) {
					int numFactors = 1;
					for (int idx = 0; (idx = downsamplingFactorsString.indexOf(",", idx)) >= 0; idx++) { numFactors++; }
					downsamplingFactors = new int[numFactors];
					parseCSIntArray(downsamplingFactorsString, downsamplingFactors);
				}
				if (tempTileSizeString != null) {
					tempTileSize = new int[2];
					parseCSIntArray(tempTileSizeString, tempTileSize);
				}

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
		 * @return the baseUrl
		 */
		public String getBaseUrl() {
			return baseUrl;
		}

		/**
		 * @return the owner
		 */
		public String getOwner() {
			return owner;
		}

		/**
		 * @return the project
		 */
		public String getProject() {
			return project;
		}

		/**
		 * @return the stack
		 */
		public String getStack() {
			return stack;
		}

		/**
		 * @return the filter flag
		 */
		public boolean getFilter() {
			return filter;
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
		 * @return the tempTileSize
		 */
		public int[] getTempTileSize() {
			return tempTileSize;
		}

		/**
		 *
		 * @return the downsamplingFactors
		 */
		public int[] getDownsamplingFactors() { return downsamplingFactors; }
	}

	static private BufferedImage renderImage(
			final ImageProcessorCache ipCache,
			final String baseUrl,
			final String owner,
			final String project,
			final String stack,
			final long x,
			final long y,
			final long z,
			final long w,
			final long h,
			final double scale,
			final boolean filter) {

		final String renderParametersUrlString = String.format(
				renderParametersFormat,
				baseUrl,
				owner,
				project,
				stack,
				z,
				x,
				y,
				w,
				h,
				scale);

		final RenderParameters renderParameters = RenderParameters.loadFromUrl(renderParametersUrlString);
		renderParameters.setDoFilter(filter);
        final BufferedImage image = renderParameters.openTargetImage();
        ArgbRenderer.render(renderParameters, image, ipCache);

        return image;
	}

	public static void saveRenderStack(
			final JavaSparkContext sc,
			final String baseUrl,
			final String owner,
			final String project,
			final String stack,
			final boolean filter,
			final int[] tileSize,
			final String n5Path,
			final String datasetName,
			final long[] min,
			final long[] size,
			final int[] blockSize) throws IOException {

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

//			final ImageProcessorCache ipCache = new ImageProcessorCache();
			final ImageProcessorCache ipCache = ImageProcessorCache.DISABLED_CACHE;

			/* tile coordinates */
//			final long col = (gridBlock[0][0] + min[0]) / tileSize[0];
//			final long row = (gridBlock[0][1] + min[1]) / tileSize[1];

			/* assume we can fit it in an array */
			final ArrayImg<UnsignedByteType, ByteArray> block = ArrayImgs.unsignedBytes(gridBlock[1]);
//			final boolean hasData = false;
			for (int z = 0; z < block.dimension(2); ++z) {

				final BufferedImage image = renderImage(
						ipCache,
						baseUrl,
						owner,
						project,
						stack,
						gridBlock[0][0] + min[0],
						gridBlock[0][1] + min[1],
						gridBlock[0][2] + min[2] + z,
						tileSize[0],
						tileSize[1],
						1,
						filter);

				final IntervalView<UnsignedByteType> outSlice = Views.hyperSlice(block, 2, z);
				final IterableInterval<UnsignedByteType> inSlice = Views
						.flatIterable(
								Views.interval(
										ArrayImgs.unsignedBytes(
												(byte[])new ColorProcessor(image).convertToByteProcessor().getPixels(),
												image.getWidth(),
												image.getHeight()),
										outSlice));

				final Cursor<UnsignedByteType> in = inSlice.cursor();
				final Cursor<UnsignedByteType> out = outSlice.cursor();
				while (out.hasNext())
					out.next().set(in.next());
			}

			final N5FSWriter n5Writer = new N5FSWriter(n5Path);
			N5Utils.saveNonEmptyBlock(block, n5Writer, datasetName, gridBlock[2], new UnsignedByteType(0));
		});
	}

	public static void main(final String... args) throws IOException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName("SparkConvertRenderStackToN5");
		final JavaSparkContext sc = new JavaSparkContext(conf);

		final String datasetName = options.getTempTileSize() == null ? options.getDatasetName() : options.getDatasetName() + "-slices";
		final int[] blockSize = options.getTempTileSize() == null ? options.getBlockSize() : new int[] {options.getTempTileSize()[0], options.getTempTileSize()[1], 1};

		final boolean downsampleStack = options.getDownsamplingFactors() != null;
		final String fullScaleName = downsampleStack ? Paths.get(datasetName, "s" + 0).toString() : datasetName;

		// TODO: Add option indicating that prior existing data should be removed and then use Spark to
		//       remove in parallel (running FileUtils.deleteDirectory on the root dataset directory will take
		//       too long for all but the smallest data sets).  The parallel removal feature should support
		//       removal of all scale levels (including full scale).

		final File datasetDir = new File(Paths.get(options.getN5Path(), fullScaleName).toString());
		if (datasetDir.exists()) {
			throw new IllegalArgumentException("Dataset " + datasetDir.getAbsolutePath() + " already exists.  " +
											   "Please move (or remove) the existing dataset before regenerating it.");
		}

		// save full scale first ...
		saveRenderStack(
				sc,
				options.getBaseUrl(),
				options.getOwner(),
				options.getProject(),
				options.getStack(),
				options.getFilter(),
				options.getTileSize(),
				options.getN5Path(),
				fullScaleName,
				options.getMin(),
				options.getSize(),
				blockSize);

		if (downsampleStack) {

			// Now that the full resolution image is saved into n5, generate the scale pyramid
			final N5WriterSupplier n5Supplier = () -> new N5FSWriter( options.getN5Path() );

			// NOTE: no need to write full scale down-sampling factors (default is 1,1,1)

			downsampleScalePyramid(
					sc,
					n5Supplier,
					fullScaleName,
					datasetName,
					options.getDownsamplingFactors()
				);
		}

		// TODO: find out whether this should behave differently when down-sampling is requested

		if (options.getTempTileSize() != null)
			SparkConvertTiffSeriesToN5.reSave(
					sc,
					options.getN5Path(),
					datasetName,
					options.getDatasetName(),
					options.getBlockSize());

		sc.close();
	}
}
