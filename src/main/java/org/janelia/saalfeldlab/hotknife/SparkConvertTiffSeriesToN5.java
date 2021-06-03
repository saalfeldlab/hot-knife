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

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import ij.IJ;
import ij.ImagePlus;
import ij.io.Opener;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkConvertTiffSeriesToN5 {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--urlFormat", required = true, usage = "Input URL format for tiff series, e.g. /nrs/flyem/data/Z0115-22_Sec26/flatten/flattened/zcorr.%05d-flattened.tif")
		private final String urlFormat = null;

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "--n5Dataset", required = true, usage = "N5 dataset, e.g. /Sec26")
		final String datasetName = null;

		@Option(name = "--min", required = false, usage = "Min coordinate of the output volume, e.g. 0,0,0")
		private final String minString = null;
		private final long[] min;

		@Option(name = "--size", required = false, usage = "Size of the output volume, e.g. 10000,20000,30000, a number <= 0 for any dimensions indicates default sourceSize - min")
		private final String sizeString = null;
		private final long[] size;

		@Option(name = "--blockSize", required = false, usage = "Size of output blocks, e.g. 128,128,128")
		private final String blockSizeString = null;
		private final int[] blockSize;

		@Option(name = "--firstSlice", required = false, usage = "first slice index (if not 0)")
		private long firstSliceIndex = 0;

		private final long[] sourceSize;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			sourceSize = new long[3];
			min = new long[3];
			size = new long[3];
			blockSize = new int[3];
			try {
				parser.parseArgument(args);

				if (minString == null)
					Arrays.fill(min, 0);
				else
					parseCSLongArray(minString, min);

				System.out.println(String.format(urlFormat, firstSliceIndex + min[2]));

				/* width and height */
				final ImagePlus firstSlice = new Opener().openImage(String.format(urlFormat, firstSliceIndex + min[2]));
				sourceSize[0] = firstSlice.getWidth();
				sourceSize[1] = firstSlice.getHeight();

				/* depth */
				final File[] tiffs = new File(urlFormat).getParentFile().listFiles(
						(dir, file) -> file.endsWith(urlFormat.substring(urlFormat.lastIndexOf('.'))));
				sourceSize[2] = tiffs.length;

				if (sizeString == null) {
					size[0] = sourceSize[0] - min[0];
					size[1] = sourceSize[1] - min[1];
//					size[2] = sourceSize[2] - min[2];
					size[2] = sourceSize[2];
				} else
					parseCSLongArray(sizeString, size);

				/* default min and size for -1 fields */
				for (int i = 0; i < size.length; ++i) {
					if (min[i] <= 0) min[i] = 0;
					if (size[i] <= 0) size[i] = sourceSize[i] - min[i];
				}

				if (blockSizeString == null)
					blockSize[0] = blockSize[1] = blockSize[2] = 128;
				else
					parseCSIntArray(blockSizeString, blockSize);

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
		 * @return the sourceSize
		 */
		public long[] getSourceSize() {
			return sourceSize;
		}

		/**
		 * @return the firstSlice
		 */
		public long getFirstSliceIndex() {
			return firstSliceIndex;
		}
	}


	public static final void saveTIFFSeries(
			final JavaSparkContext sc,
			final String urlFormat,
			final String n5Path,
			final String datasetName,
			final long[] min,
			final long[] size,
			final int[] blockSize,
			final long firstSliceIndex) throws IOException {

		final N5Writer n5 = new N5FSWriter(n5Path);

		ImagePlus firstImp = IJ.openImage(String.format(urlFormat, firstSliceIndex));
		final DataType type;
		switch (firstImp.getType()) {
		case ImagePlus.GRAY16:
			type = DataType.UINT16;
			break;
		case ImagePlus.GRAY32:
			type = DataType.FLOAT32;
			break;
		case ImagePlus.COLOR_RGB:
			type = DataType.UINT32;
			break;
		default:
			type = DataType.UINT8;
			break;
		}
		firstImp = null;

        final int[] slicesDatasetBlockSize = new int[]{
        		blockSize[0] * 8,
        		blockSize[1] * 8,
        		1};
        n5.createDataset(
        		datasetName,
        		size,
        		slicesDatasetBlockSize,
        		type,
        		new GzipCompression());
		final ArrayList<Long> slices = new ArrayList<>();
		for (long z = min[2]; z < min[2] + size[2]; ++z)
			slices.add(z);

		final JavaRDD<Long> rddSlices = sc.parallelize(slices);

		rddSlices.foreach(sliceIndex -> {

			final ImagePlus imp = IJ.openImage(String.format(urlFormat, sliceIndex + firstSliceIndex));
			if (imp == null)
				return;

			@SuppressWarnings({ "unchecked", "rawtypes" })
			RandomAccessibleInterval img = ImagePlusImgs.from(imp);
			if (imp.getType() == ImagePlus.COLOR_RGB)
				img = Converters.convert(
						(RandomAccessibleInterval<ARGBType>)img,
						(a, b) -> {
							b.set(a.get());
						},
						new UnsignedIntType());

			@SuppressWarnings({ "unchecked", "rawtypes" })
			final RandomAccessibleInterval slice =
					Views.offsetInterval(
							img,
							new long[]{
									min[0],
									min[1]},
							new long[]{
									size[0],
									size[1]});
			final N5Writer n5Local = new N5FSWriter(n5Path);
			N5Utils.saveBlock(
					Views.addDimension(slice, 0, 0),
					n5Local,
					datasetName,
					new long[]{0, 0, sliceIndex - min[2]});
		});
	}


	/**
	 * Copy an existing N5 dataset into another with a different blockSize.
	 *
	 * Parallelizes over blocks of [max(input, output)] to reduce redundant
	 * loading.  If blockSizes are integer multiples of each other, no
	 * redundant loading will happen.
	 *
	 * @param sc
	 * @param n5Path
	 * @param datasetName
	 * @param outDatasetName
	 * @param outBlockSize
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	public static final void reSave(
			final JavaSparkContext sc,
			final String n5Path,
			final String datasetName,
			final String outDatasetName,
			final int[] outBlockSize) throws IOException {

		final N5Writer n5 = new N5FSWriter(n5Path);

		final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
		final int n = attributes.getNumDimensions();
		final int[] blockSize = attributes.getBlockSize();

		n5.createDataset(
				outDatasetName,
				attributes.getDimensions(),
				outBlockSize,
				attributes.getDataType(),
				attributes.getCompression());

		/* grid block size for parallelization to minimize double loading of blocks */
		final int[] gridBlockSize = new int[outBlockSize.length];
		Arrays.setAll(gridBlockSize, i -> Math.max(blockSize[i], outBlockSize[i]));

		final JavaRDD<long[][]> rdd =
				sc.parallelize(
						Grid.create(
								attributes.getDimensions(),
								gridBlockSize,
								outBlockSize));

		rdd.foreach(
				gridBlock -> {
					final N5Writer n5Writer = new N5FSWriter(n5Path);
					final RandomAccessibleInterval<?> source = N5Utils.open(n5Writer, datasetName);
					@SuppressWarnings("rawtypes")
					final RandomAccessibleInterval sourceGridBlock = Views.offsetInterval(source, gridBlock[0], gridBlock[1]);
					N5Utils.saveBlock(sourceGridBlock, n5Writer, outDatasetName, gridBlock[2]);
				});
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName( "SparkConvertTiffSeriesToN5" );
        final JavaSparkContext sc = new JavaSparkContext(conf);

        final String slicesDatasetName = options.getDatasetName() + "-slices";

		/* parallelize over slices */
        saveTIFFSeries(
        		sc,
        		options.getUrlFormat(),
        		options.getN5Path(),
        		slicesDatasetName,
        		options.getMin(),
        		options.getSize(),
        		options.getBlockSize(),
        		options.getFirstSliceIndex());

		/* re-block */
		reSave(
				sc,
				options.getN5Path(),
				slicesDatasetName,
				options.getDatasetName(),
				options.getBlockSize());

		sc.close();

//		final N5Writer n5 = N5.openFSWriter(options.getN5Path());

		/* remove should be parallelized */
//		n5.remove(slicesDatasetName);

//		final int numProc = Runtime.getRuntime().availableProcessors();
//		final SharedQueue queue = new SharedQueue(Math.max(1, numProc / 2));
//
//		BdvFunctions.show(
//				VolatileViews.wrapAsVolatile(
//						(RandomAccessibleInterval<UnsignedByteType>)N5Utils.openVolatile(n5, options.datasetName),
//						queue),
//				"export");
	}
}
