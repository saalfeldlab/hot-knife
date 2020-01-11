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

import ij.IJ;
import ij.ImagePlus;
import ij.io.Opener;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.Views;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.*;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import static org.janelia.saalfeldlab.n5.spark.downsample.scalepyramid.N5ScalePyramidSpark.downsampleScalePyramid;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkConvertTiffToN5 {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--url", required = true, usage = "Input URL for tiff, e.g. /nrs/flyem/data/Z0115-22_Sec26/flatten/flattened/zcorr-stack-flattened.tif")
		private final String url = null;

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

		@Option(name = "--factors", usage = "Specifies generates a scale pyramid with given factors with relative scaling between factors, e.g. 2,2,2")
		private String downsamplingFactorsString = null;
		private int[] downsamplingFactors;

		private final long[] sourceSize;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			sourceSize = new long[3];
			min = new long[3];
			size = new long[3];
			blockSize = new int[3];
			downsamplingFactors = null;
			try {
				parser.parseArgument(args);

				if (minString == null)
					Arrays.fill(min, 0);
				else
					parseCSLongArray(minString, min);

				System.out.println(url);

				/* width and height and depth */
				final ImagePlus imp = new Opener().openImage(url);
				sourceSize[0] = imp.getWidth();
				sourceSize[1] = imp.getHeight();
				sourceSize[2] = imp.getStackSize();

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

				if (downsamplingFactorsString != null) {
					int numFactors = 1;
					for (int idx = 0; (idx = downsamplingFactorsString.indexOf(",", idx)) >= 0; idx++) { numFactors++; }
					downsamplingFactors = new int[numFactors];
					parseCSIntArray(downsamplingFactorsString, downsamplingFactors);
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
		 * @return the urlFormat
		 */
		public String getUrl() {
			return url;
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
		 *
		 * @return the downsamplingFactors
		 */
		public int[] getDownsamplingFactors() { return downsamplingFactors; }
	}


	public static final void saveTIFF(
			final JavaSparkContext sc,
			final String url,
			final String n5Path,
			final String datasetName,
			final long[] min,
			final long[] size,
			final int[] blockSize) throws IOException {

		final N5Writer n5 = new N5FSWriter(n5Path);

        final int[] slicesDatasetBlockSize = new int[]{
        		blockSize[0] * 8,
        		blockSize[1] * 8,
        		1};
        n5.createDataset(
        		datasetName,
        		size,
        		slicesDatasetBlockSize,
        		DataType.UINT8,
        		new GzipCompression());
		final ArrayList<Long> slices = new ArrayList<>();
		for (long z = min[2]; z < min[2] + size[2]; ++z)
			slices.add(z);

		final JavaRDD<Long> rddSlices = sc.parallelize(slices);

		rddSlices.foreach(sliceIndex -> {

			ImagePlus imp = IJ.openImage(url);
			imp.setSlice(Math.toIntExact(sliceIndex));
			imp = new ImagePlus(imp.getTitle() + "_" + sliceIndex, imp.getProcessor());
			if (imp == null)
				return;

			@SuppressWarnings({ "unchecked", "rawtypes" })
			final RandomAccessibleInterval<UnsignedByteType> slice =
					Views.offsetInterval(
							(RandomAccessibleInterval)ImagePlusImgs.from(imp),
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

		String datasetName = options.getDatasetName();
        final String slicesDatasetName = datasetName + "-slices";
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

		/* parallelize over slices */
        saveTIFF(
        		sc,
        		options.getUrl(),
        		options.getN5Path(),
        		slicesDatasetName,
        		options.getMin(),
        		options.getSize(),
        		options.getBlockSize());

		/* re-block */
		reSave(
				sc,
				options.getN5Path(),
				slicesDatasetName,
				fullScaleName,
				options.getBlockSize());

		if (downsampleStack) {

			// Now that the full resolution image is saved into n5, generate the scale pyramid
			final N5WriterSupplier n5Supplier = () -> new N5FSWriter(options.getN5Path() );

			// NOTE: no need to write full scale down-sampling factors (default is 1,1,1)

			downsampleScalePyramid(
					sc,
					n5Supplier,
					fullScaleName,
					datasetName,
					options.getDownsamplingFactors()
			);
		}

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
