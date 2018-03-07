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
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
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

import bdv.labels.labelset.Label;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.morphology.distance.DistanceTransform;
import net.imglib2.algorithm.morphology.distance.DistanceTransform.DISTANCE_TYPE;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkDistanceTransform {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /groups/saalfeld/saalfeldlab/sampleE/multicut_segmentation.n5")
		private String n5Path = null;

		@Option(name = "--n5Dataset", required = true, usage = "N5 dataset, e.g. /multicut")
		private String datasetName = null;

		@Option(name = "--n5OutputPath", required = true, usage = "N5 output path, e.g. /groups/saalfeld/saalfeldlab/sampleE/multicut_segmentation.n5")
		private String n5OutputPath = null;

		@Option(name = "--n5OutputDataset", required = true, usage = "N5 output dataset, e.g. /multicut")
		private String outputDatasetName = null;

		@Option(name = "--blockSize", required = true, usage = "Size of output blocks, e.g. 256,256,26")
		private String blockSizeString = null;
		private int[] blockSize;

		@Option(name = "--resolution", required = true, usage = "Physical resolution, e.g. 4,4,40")
		private String resolutionString = null;
		private double[] resolution;

		@Option(name = "--padding", required = true, usage = "Initial padding of input, e.g. 64,64,6")
		private String paddingString = null;
		private long[] padding;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			blockSize = new int[3];
			try {
				parser.parseArgument(args);
				parseCSIntArray(blockSizeString, blockSize);
				parseCSDoubleArray(resolutionString, resolution);
				parseCSLongArray(paddingString, padding);
				parsedSuccessfully = true;
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
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
		 * @return the n5Path
		 */
		public String getN5OutputPath() {
			return n5OutputPath;
		}

		/**
		 * @return the datasetName
		 */
		public String getOutputDatasetName() {
			return outputDatasetName;
		}

		/**
		 * @return the blockSize
		 */
		public int[] getBlockSize() {
			return blockSize;
		}

		/**
		 * @return the resolution
		 */
		public double[] getResolution() {
			return resolution;
		}

		/**
		 * @return the padding
		 */
		public long[] getPadding() {
			return padding;
		}
	}

	public static <T extends Type<T>> ArrayImg<DoubleType, DoubleArray> createBoundaries(
			final RandomAccessibleInterval<T> source,
			final double maxSquareDistance) {

		final long[] dimensions = Intervals.dimensionsAsLongArray(source);
		final int n = dimensions.length;
		final long[] dimensionsMinus1 = new long[n];
		final long[] outputDimensions = new long[n];
		Arrays.setAll(dimensionsMinus1, i -> dimensions[i] - 1);
		Arrays.setAll(outputDimensions, i -> dimensions[i] * 2 - 4);

		/* padded output block, assume we can fit it in an array */
		final ArrayImg<DoubleType, DoubleArray> targetBlock = ArrayImgs.doubles(outputDimensions);
		targetBlock.forEach(t -> t.set(1.0));

		final long[] minA = new long[n];
		for (int d = 0; d < n; ++d) {

			final long[] minB = new long[n];
			minB[d] = 1;
			final IntervalView<T> a = Views.offsetInterval(source, minA, dimensionsMinus1);
			final IntervalView<T> b = Views.offsetInterval(source, minB, dimensionsMinus1);

			final long[] subsampleSteps = new long[n];
			Arrays.fill(subsampleSteps, 1);
			subsampleSteps[d] = 2;
			final SubsampleIntervalView<DoubleType> target = Views.subsample(targetBlock, subsampleSteps);
			final RandomAccess<DoubleType> rat = target.randomAccess(target);

			final Cursor<T> ca = Views.flatIterable(a).localizingCursor();
			final Cursor<T> cb = Views.flatIterable(b).localizingCursor();

			while (ca.hasNext()) {
				final T cav = ca.next();
				final T cbv = cb.next();
				if (!cav.valueEquals(cbv)) {
					rat.setPosition(ca);
					rat.get().set(0);
				}
			}
		}

		return targetBlock;
	}

	public static final void calculateDistanceTransform(
			final JavaSparkContext sc,
			final String n5Path,
			final String datasetName,
			final String n5OutputPath,
			final String outputDatasetName,
			final int[] blockSize,
			final long[] initialPadding,
			final double[] resolution) throws IOException {

		final N5Reader n5Reader = new N5FSReader(n5Path);
		final N5Writer n5Writer = new N5FSWriter(n5OutputPath);

		final DatasetAttributes attributes = n5Reader.getDatasetAttributes(datasetName);
		final long[] dimensions = attributes.getDimensions();
		final int n = dimensions.length;

		final double[] squareHalfResolution = new double[n];
		Arrays.setAll(squareHalfResolution, i -> 0.25 * resolution[i] * resolution[i]);

		n5Writer.createDataset(
				outputDatasetName,
				dimensions,
				blockSize,
				DataType.UINT16,
				new GzipCompression());

		/*
		 * grid block size for parallelization to minimize double loading of
		 * blocks
		 */
		final JavaRDD<long[][]> rdd = sc.parallelize(
				Grid.create(
						dimensions,
						blockSize,
						blockSize));

		rdd.foreach(gridBlock -> {

			final long[] padding = initialPadding.clone();
A:			for (boolean paddingIsTooSmall = true; paddingIsTooSmall; Arrays.setAll(padding, i -> padding[i] * 2)) {

				paddingIsTooSmall = false;

				final double[] scaledPadding = new double[n];
				Arrays.setAll(scaledPadding, i -> resolution[i] * padding[i]);
				final double maxScaledPadding =  Arrays.stream(scaledPadding).max().getAsDouble();
				final double squareHalfMaxScaledPadding = 0.25 * maxScaledPadding * maxScaledPadding;

				final long[] paddedBlockMin = new long[n];
				final long[] paddedBlockSize = new long[n];
				final long[] paddedBlockSizeMinus1 = new long[n];
				final long[] paddedOutputBlockSize = new long[n];
				Arrays.setAll(paddedBlockMin, i -> gridBlock[0][i] - padding[i]);
				Arrays.setAll(paddedBlockSize, i -> gridBlock[1][i] + 2 * padding[i]);
				Arrays.setAll(paddedBlockSizeMinus1, i -> paddedBlockSize[i] - 1);
				Arrays.setAll(paddedOutputBlockSize, i -> paddedBlockSize[i] * 2 - 4);

				final long maxBlockSize = Arrays.stream(paddedBlockSize).max().getAsLong();
				final double squareMaxBlockSize = maxBlockSize * maxBlockSize;

				final N5Reader n5BlockReader = new N5FSReader(n5Path);
				final RandomAccessibleInterval<UnsignedLongType> source = N5Utils.open(n5BlockReader, datasetName);
				final IntervalView<UnsignedLongType> sourceBlock =
						Views.offsetInterval(
								Views.extendValue(
										source,
										new UnsignedLongType(Label.OUTSIDE)),
								paddedBlockMin,
								paddedBlockSize);

				/* padded output block, assume we can fit it in an array */
				final ArrayImg<DoubleType, DoubleArray> block = createBoundaries(sourceBlock, squareMaxBlockSize);

				/* make distance transform */
				DistanceTransform.transform(block, DISTANCE_TYPE.EUCLIDIAN, squareHalfResolution);

				final long[] minInside = new long[n];
				final long[] dimensionsInside = new long[n];
				Arrays.setAll(minInside, i -> padding[i] * 2 - 1);
				Arrays.setAll(dimensionsInside, i -> gridBlock[1][i] * 2 - 1);

				final IntervalView<DoubleType> insideBlock = Views.offsetInterval(block, minInside, dimensionsInside);

				/* test whether distances at inside boundary are smaller than padding */
				for (int d = 0; d < n; ++d) {

					final IntervalView<DoubleType> topSlice = Views.hyperSlice(insideBlock, d, 0);
					for (final DoubleType t : topSlice)
						if (t.get() >= squareHalfMaxScaledPadding) {
							paddingIsTooSmall = true;
							continue A;
						}

					final IntervalView<DoubleType> botSlice = Views.hyperSlice(insideBlock, d, insideBlock.max(d));
					for (final DoubleType t : botSlice)
						if (t.get() >= squareHalfMaxScaledPadding) {
							paddingIsTooSmall = true;
							continue A;
						}
				}

				/* padding was sufficient, save */
				final SubsampleIntervalView<DoubleType> outputBlock = Views.subsample(insideBlock, 2);
				final RandomAccessibleInterval<UnsignedShortType> convertedOutputBlock = Converters.convert(
						outputBlock,
						(a, b) -> b.set(Math.min(65535, (int)Math.round(a.get()))),
						new UnsignedShortType());

				final N5FSWriter n5BlockWriter = new N5FSWriter(n5Path);
				N5Utils.saveNonEmptyBlock(convertedOutputBlock, n5BlockWriter, datasetName, gridBlock[2], new UnsignedShortType(0));
			}
		});
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName("SparkDistanceTransform");
		final JavaSparkContext sc = new JavaSparkContext(conf);

		/* parallelize over slices */
		calculateDistanceTransform(
				sc,
				options.getN5Path(),
				options.getDatasetName(),
				options.getN5OutputPath(),
				options.getOutputDatasetName(),
				options.getBlockSize(),
				options.getPadding(),
				options.getResolution());

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
