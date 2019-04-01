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
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.NativeType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

/**
 * Export a render stack to N5.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkRandomSubsampleN5 {

	final static public String ownerFormat = "%s/owner/%s";
	final static public String stackListFormat = ownerFormat + "/stacks";
	final static public String stackFormat = ownerFormat + "/project/%s/stack/%s";
	final static public String stackBoundsFormat = stackFormat  + "/bounds";
	final static public String boundingBoxFormat = stackFormat + "/z/%d/box/%d,%d,%d,%d,%f";
	final static public String renderParametersFormat = boundingBoxFormat + "/render-parameters";

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--inputN5Path", required = true, usage = "input N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String inputN5Path = null;

		@Option(name = "--outputN5Path", required = false, usage = "output N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String outputN5Path = null;

		@Option(name = "--inputN5Group", required = true, usage = "N5 dataset, e.g. /Sec26")
		private String inputDatasetName = null;

		@Option(name = "--outputN5Group", required = true, usage = "N5 dataset, e.g. /Sec26")
		private String outputDatasetName = null;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);

				if (outputN5Path == null) outputN5Path = inputN5Path;

				parsedSuccessfully = true;
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}


		public String getInputN5Path() {
			return inputN5Path;
		}

		public String getInputDatasetName() {
			return inputDatasetName;
		}

		public String getOutputN5Path() {
			return outputN5Path;
		}

		public String getOutputDatasetName() {
			return outputDatasetName;
		}
	}

	public static final <T extends NativeType<T>> ArrayImg<T, ?>[] subsample(
			final int m,
			final T type,
			final long[] blockSize,
			final Cursor<T>[] sources) {

		@SuppressWarnings("unchecked")
		final ArrayImg<T, ?>[] targets = (ArrayImg<T, ?>[])new ArrayImg[m];
		@SuppressWarnings("unchecked")
		final Cursor<T>[] targetCursors = new Cursor[m];
		for (int i = 0; i < m; ++i) {
			targets[i] = new ArrayImgFactory<T>(type).create(blockSize);
			targetCursors[i] = targets[i].cursor();
		}
		final int[] sourceIndex = new int[m];
		Arrays.setAll(sourceIndex, i -> i);
		final Random rnd = new Random();

		while (targetCursors[0].hasNext()) {
			shuffle(sourceIndex, rnd);
			for (int i = 0; i < m; ++i) {
				final int j = sourceIndex[i];
				targetCursors[i].next().set(sources[j].next());
			}
		}
		return targets;
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
	public static final <T extends NativeType<T>> void subsample(
			final JavaSparkContext sc,
			final String inputN5Path,
			final String outputN5Path,
			final String inputDatasetName,
			final String outputDatasetName) throws IOException {

		final N5Writer n5Reader = new N5FSWriter(inputN5Path);
		final N5Writer n5Writer = new N5FSWriter(outputN5Path);

		final DatasetAttributes attributes = n5Reader.getDatasetAttributes(inputDatasetName);
		final int n = attributes.getNumDimensions();
		final int m = Util.pow(2, n);
		final int[] blockSize = attributes.getBlockSize();
		final long[] outputDimensions = attributes.getDimensions();
		Arrays.setAll(outputDimensions, i -> outputDimensions[i] / 2);

		n5Writer.createGroup(outputDatasetName);
		for (int i = 0; i < m; ++i)
			n5Writer.createDataset(
					outputDatasetName + "/" + i,
					outputDimensions,
					blockSize,
					attributes.getDataType(),
					attributes.getCompression());

		final JavaRDD<long[][]> rdd =
				sc.parallelize(
						Grid.create(
								outputDimensions,
								blockSize));

		rdd.foreach(
				gridBlock -> {
					final N5Reader n5ReaderLocal = new N5FSReader(inputN5Path);
					final N5Writer n5WriterLocal = new N5FSWriter(outputN5Path);
					final RandomAccessibleInterval<T> source = N5Utils.open(n5ReaderLocal, inputDatasetName);
					final Cursor<T>[] sources = (Cursor<T>[])createSources(source, gridBlock[0], gridBlock[1]);

					final ArrayImg<T, ?>[] targets = subsample(m, source.randomAccess().get(), gridBlock[1], sources);

					for (int i = 0; i < m; ++i)
						N5Utils.saveBlock(targets[i], n5WriterLocal, outputDatasetName + "/" + i, gridBlock[2]);
				});
	}

	/**
	 * Durstenfeld in place shuffling like in commons' ArrayUtils but without all the needless tests in swap...
	 *
	 * @param array
	 */
	private static void shuffle(final int[] array, final Random rnd) {

		for (int i = array.length - 1; i > 0; --i) {
			final int j = rnd.nextInt(i + 1);
			final int k = array[i];
			array[i] = array[j];
			array[j] = k;
		}
	}

	public static <T> Cursor<T>[] createSources(final RandomAccessible<T> source, final long[] min, final long[] size) {

		final int n = source.numDimensions();
		final int m = Util.pow(2, n);
		final Cursor<T>[] subsampledSources = (Cursor<T>[])new Cursor[m];
		final long[] offset = new long[n];
		for (int i = 0, d = 0; d < n;) {
			subsampledSources[i++] =
					Views.flatIterable(
						Views.offsetInterval(
								Views.subsample(
										Views.offset(source, offset), 2),
						min,
						size)).cursor();
			for (d = 0; d < n; ++d) {
				++offset[d];
				if (offset[d] < 2)
					break;
				else
					offset[d] = 0;
			}
		}
		return subsampledSources;
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName("SparkRandomSubsampleN5");
		final JavaSparkContext sc = new JavaSparkContext(conf);

		subsample(sc, options.getInputN5Path(), options.getOutputN5Path(), options.getInputDatasetName(), options.getOutputDatasetName());

		sc.close();



				// BdvFunctions.show(
		// VolatileViews.wrapAsVolatile(
		// (RandomAccessibleInterval<UnsignedByteType>)N5Utils.openVolatile(n5,
		// options.datasetName),
		// queue),
		// "export");
	}
}
