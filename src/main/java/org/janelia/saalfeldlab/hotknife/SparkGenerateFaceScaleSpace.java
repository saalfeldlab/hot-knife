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
import org.janelia.saalfeldlab.hotknife.ops.CLLCN;
import org.janelia.saalfeldlab.hotknife.ops.ImageJStackOp;
import org.janelia.saalfeldlab.hotknife.ops.SimpleGaussRA;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.converter.Converters;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkGenerateFaceScaleSpace {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "--n5DatasetInput", required = true, usage = "N5 dataset, e.g. /Sec26")
		private final String inDatasetName = null;

		@Option(name = "--n5GroupOutput", required = true, usage = "N5 group, e.g. /Sec26-top")
		private final String outGroupName = null;

		@Option(name = "--blockSize", required = false, usage = "Size of output blocks, e.g. 128,128,128")
		private final String blockSizeString = null;
		private final int[] blockSize;

		@Option(name = "--min", required = false, usage = "Min coordinate of the output volume, e.g. 0,0,0")
		private final String minString = null;
		private final long[] min;

		@Option(name = "--size", required = false, usage = "Size of the output volume, e.g. 10000,20000,30000, a number == 0 for any dimensions indicates default input_dataset_size - min")
		private final String sizeString = null;
		private final long[] size;

		@Option(name = "--maxDownsamplingLevel", usage = "MultiSem datasets can be thin, so we might not be able to downsample till s9")
		private int maxDownsamplingLevel = 9;

		@Option(name = "--invert", usage = "MultiSem datasets might be inverted")
		private boolean invert = false;

		@Option(name = "--normalizeContrast", usage = "Perform contast normalization on the input data")
		private boolean normalizeContrast = false;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			blockSize = new int[2];
			min = new long[3];
			size = new long[3];
			try {
				parser.parseArgument(args);

				if (blockSizeString == null)
					blockSize[0] = blockSize[1] = 128;
				else
					parseCSIntArray(blockSizeString, blockSize);

				if (minString == null)
					Arrays.fill(min, 0);
				else
					parseCSLongArray(minString, min);

				final N5Reader n5 = new N5FSReader(n5Path);
				final DatasetAttributes attributes = n5.getDatasetAttributes(inDatasetName);
				final long[] sourceSize = attributes.getDimensions();

				if (sizeString == null) {
					size[0] = sourceSize[0] - min[0];
					size[1] = sourceSize[1] - min[1];
					size[2] = sourceSize[2] - min[2];
				} else
					parseCSLongArray(sizeString, size);

				/* absolute coordinates for negative min */
				for (int i = 0; i < min.length; ++i) {
					if (min[i] < 0) min[i] = sourceSize[i] + min[i] - 1;
				}

				/* default size for 0 fields */
				for (int i = 0; i < size.length; ++i) {
					if (size[i] == 0) size[i] = sourceSize[i] - min[i];
				}

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
		 * @return the input datasetName
		 */
		public String getInputDatasetName() {

			return inDatasetName;
		}

		/**
		 * @return the input datasetName
		 */
		public String getOutputGroupName() {

			return outGroupName;
		}

		/**
		 * @return the blockSize
		 */
		public int[] getBlockSize() {

			return blockSize;
		}

		/**
		 * @return the min
		 */
		public long[] getMin() {

			return min;
		}

		/**
		 * @return the size
		 */
		public long[] getSize() {

			return size;
		}
	}

	static public double sigmaDiff(final double sourceSigma, final double targetSigma, final double scale) {

		final double s = targetSigma / scale;
		final double v = Math.max(0, s * s - sourceSigma * sourceSigma);
		return Math.sqrt(v);
	}

	static public long[] downsample(
			final JavaSparkContext sc,
			final String n5Path,
			final String inDatasetName,
			final long[] min,
			final long[] size,
			final int scaleIndex,
			final String outDatasetName,
			final int[] outBlockSize,
			final boolean invert,
			final boolean normalizeContrast ) throws IOException {

		final N5Writer n5 = new N5FSWriter(n5Path);

		final DatasetAttributes attributes = n5.getDatasetAttributes(inDatasetName);
		final DataType inType = attributes.getDataType();

		final int sampleStepSize = net.imglib2.util.Util.pow(2, scaleIndex);
		final double sigma = sigmaDiff(0.5, 0.5, 1.0 / sampleStepSize);
		final double[] sigmas = new double[] { sigma, sigma, sigma };

		final long[] outDimensions = Arrays.stream(size).map(x -> Math.abs(x) / sampleStepSize).toArray();

		n5.createDataset(
				outDatasetName,
				outDimensions,
				outBlockSize,
				DataType.FLOAT32,
				new GzipCompression());

		final JavaRDD<long[][]> rdd =
				sc.parallelize(
						Grid.create(
								outDimensions,
								outBlockSize));

		rdd.foreach(
				gridBlock -> {
					System.out.println(Arrays.deepToString(gridBlock));

					final N5Writer n5Writer = new N5FSWriter(n5Path);
					@SuppressWarnings("unchecked")
					final RandomAccessibleInterval<RealType<?>> source;

					// only s0 is UnsignedByteType, the rest is FloatType and already inverted and normalized (if asked for)
					if ( invert || normalizeContrast )
					{
						// we choose a non-power-of-2 blocksize on purpose since the grids do not align here typically
						// a size of 1 in z makes sense since it is a 2d filter and we do not want to do cllcn and not need it
						final int[] blockSize_cllcn = new int[] { (int)gridBlock[1][0] + 1, (int)gridBlock[1][1] + 1, 1 };

						source = (RandomAccessibleInterval<RealType<?>>)(Object) filter(
								(RandomAccessibleInterval<UnsignedByteType>)N5Utils.open(n5Writer, inDatasetName),
								invert,
								normalizeContrast,
								0, // here scaleIndex is the result of the downsampling, not the input
								blockSize_cllcn );
					}
					else
					{
						source = (RandomAccessibleInterval)N5Utils.open(n5Writer, inDatasetName);
					}

					@SuppressWarnings({ "rawtypes", "unchecked" })
					final RandomAccessibleInterval<FloatType> floatSource =
							inType == DataType.FLOAT32 ? (RandomAccessibleInterval)source : Converters.convert(
									source,
									(a, b) -> b.set(a.getRealFloat()),
									new FloatType());

					final long[] absMin = new long[min.length];
					final long[] absSize = new long[size.length];
					for (int d = 0; d < min.length; ++d) {
						if (size[d] < 0) {
							absMin[d] = min[d] + size[d];
							absSize[d] = -size[d];
						}
						else {
							absMin[d] = min[d];
							absSize[d] = size[d];
						}
					}

					//System.out.println("abs min: " + Arrays.toString(absMin) + " size " + Arrays.toString(absSize));

					IntervalView<FloatType> roi = Views.offsetInterval(floatSource, absMin, absSize);
					for (int d = 0; d < roi.numDimensions(); ++d)
						if (size[d] < 0)
							roi = Views.invertAxis(roi, d);

					final RandomAccessibleInterval<FloatType> zeroMin = Views.zeroMin(roi);

					final SimpleGaussRA<FloatType> gauss = new SimpleGaussRA<>(sigmas);
					final RandomAccessibleInterval<FloatType> filtered = Lazy.process(
							Views.extendMirrorSingle(zeroMin),
							zeroMin,
							new int[] { 33, 33, 7 }, // small blocksize to make sure we do not compute things for nothing, specifically if normalize constrast is on
							new FloatType(),
							AccessFlags.setOf(),
							gauss);
					final SubsampleIntervalView<FloatType> subsampled = Views.subsample(filtered, sampleStepSize);

					final RandomAccessibleInterval<FloatType> sourceGridBlock = Views.offsetInterval(subsampled, gridBlock[0], gridBlock[1]);

					N5Utils.saveBlock(sourceGridBlock, n5Writer, outDatasetName, gridBlock[2]);
				});

		return outDimensions;
	}

	protected static RandomAccessibleInterval<UnsignedByteType> filter(
			RandomAccessibleInterval<UnsignedByteType> sourceRaw,
			final boolean invert,
			final boolean normalizeContrast,
			final int scaleIndex )
	{
		return filter(sourceRaw, invert, normalizeContrast, scaleIndex, new int[] {1024, 1024, 1} );
	}

	protected static RandomAccessibleInterval<UnsignedByteType> filter(
			RandomAccessibleInterval<UnsignedByteType> sourceRaw,
			final boolean invert,
			final boolean normalizeContrast,
			final int scaleIndex,
			int[] blocksize )
	{
		final int n = sourceRaw.numDimensions();

		if ( invert )
			sourceRaw = Converters.convertRAI(sourceRaw, (in,out) -> out.set( 255 - in.get() ), new UnsignedByteType() );

		if ( normalizeContrast )
		{
			final int scale = 1 << scaleIndex;
			final double inverseScale = 1.0 / scale;

			final int blockRadius = (int)Math.round(511 * inverseScale); //1023

			if ( n == 2 )
			{
				sourceRaw = Views.addDimension( sourceRaw, 0, 0 );
				blocksize = new int[] { blocksize[0], blocksize[1],1};
			}

			final ImageJStackOp<UnsignedByteType> cllcn =
					new ImageJStackOp<>(
							Views.extendZero(sourceRaw),
							(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 10, 0.5f, true, true, true),
							blockRadius,
							0,
							255,
							true ); // do nothing if all black

			final CachedCellImg<UnsignedByteType, ?> out = Lazy.process(
					sourceRaw,
					blocksize,
					new UnsignedByteType(),
					AccessFlags.setOf(AccessFlags.VOLATILE),
					cllcn);

			if ( n == 2 )
				return Views.hyperSlice( out, 2, 0 );
			else
				return out;
		}
		else
		{
			return sourceRaw;
		}
	}

	public static final void extractFace(
			final JavaSparkContext sc,
			final String n5Path,
			final String inDatasetName,
			final long[] min,
			final long[] size,
			final String outDatasetName,
			final int[] outBlockSize,
			final boolean invert,
			final boolean normalizeContrast,
			final int scaleIndex ) throws IOException {

		final N5Writer n5 = new N5FSWriter(n5Path);

		final DatasetAttributes attributes = n5.getDatasetAttributes(inDatasetName);
		final DataType inType = attributes.getDataType();

		final long[] absMin = new long[min.length];
		final long[] absSize = new long[size.length];
		for (int d = 0; d < min.length; ++d) {
			if (size[d] < 0) {
				absMin[d] = min[d] + size[d];
				absSize[d] = -size[d];
			}
			else {
				absMin[d] = min[d];
				absSize[d] = size[d];
			}
		}

		final long[] outDimensions = new long[]{absSize[0], absSize[1]};

		n5.createDataset(
				outDatasetName,
				outDimensions,
				outBlockSize,
				DataType.FLOAT32,
				new GzipCompression());

		final JavaRDD<long[][]> rdd =
				sc.parallelize(
						Grid.create(
								outDimensions,
								outBlockSize));

		rdd.foreach(
				gridBlock -> {
					System.out.println(Arrays.deepToString(gridBlock));
					final N5Writer n5Writer = new N5FSWriter(n5Path);
					
					final RandomAccessibleInterval<RealType<?>> source;

					// only s0 is UnsignedByteType, the rest is FloatType and already inverted and normalized (if asked for)
					if ( invert || normalizeContrast )
					{
						source = (RandomAccessibleInterval<RealType<?>>)(Object) filter(
								(RandomAccessibleInterval<UnsignedByteType>)N5Utils.open(n5Writer, inDatasetName),
								invert,
								normalizeContrast,
								scaleIndex );
					}
					else
					{
						source = (RandomAccessibleInterval)N5Utils.open(n5Writer, inDatasetName);
					}

					@SuppressWarnings({ "rawtypes", "unchecked" })
					final RandomAccessibleInterval<FloatType> floatSource =
							inType == DataType.FLOAT32 ? (RandomAccessibleInterval)source : Converters.convert(
									source,
									(a, b) -> b.set(a.getRealFloat()),
									new FloatType());

					IntervalView<FloatType> roi = Views.offsetInterval(Views.extendZero( floatSource ), absMin, absSize);
					for (int d = 0; d < roi.numDimensions(); ++d)
						if (size[d] < 0)
							roi = Views.invertAxis(roi, d);

					final IntervalView<FloatType> zeroMin = Views.zeroMin(roi);
					final IntervalView<FloatType> face = Views.hyperSlice(zeroMin, 2, 0);
					final RandomAccessibleInterval<FloatType> sourceGridBlock = Views.offsetInterval(face, gridBlock[0], gridBlock[1]);
					N5Utils.saveBlock(sourceGridBlock, n5Writer, outDatasetName, gridBlock[2]);
				});
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName("SparkGenerateFaceScaleSpace");
		final JavaSparkContext sc = new JavaSparkContext(conf);

		generateFace(sc, options);

		sc.close();
	}

	public static void generateFace(final JavaSparkContext sc,
									final Options options)
            throws IOException {

		final N5Writer n5 = new N5FSWriter(options.getN5Path());
		n5.createGroup(options.getOutputGroupName());

		final DatasetAttributes attributes = n5.getDatasetAttributes(options.getInputDatasetName());

		/* downsample */
		final long[] min = options.getMin().clone();
		final long[] size = options.getSize().clone();
		String sourceDatasetName = options.getInputDatasetName();

		// for multisem we only [downsample to s4 or s5] extract the face, and then downsample the face itself (because the "cuts"/slabs are too thin)
		// for FIB-SEM we go in deeper, downsample until s9 and then extract the face from each downsampling step independently
		int maxScaleIndex = options.maxDownsamplingLevel; //options.multiSem ? 0 : 9;

		for (int scaleIndex = 1; scaleIndex <= maxScaleIndex; ++scaleIndex) {
			System.out.println("Scale level " + scaleIndex);
			System.out.println("min " + Util.printCoordinates( min ));
			System.out.println("size " + Util.printCoordinates( size ));

			final String scaleSpaceDataSetName = options.getOutputGroupName() + "/s" + scaleIndex;

			final long[] outDimensions = downsample(
					sc,
					options.getN5Path(),
					sourceDatasetName,
					min,
					size,
					1,
					scaleSpaceDataSetName,
					attributes.getBlockSize(),
					scaleIndex == 1 ? options.invert : false, // only when downsampling to s1 we need filtering
					scaleIndex == 1 ? options.normalizeContrast : false ); // only when downsampling to s1 we need filtering

			sourceDatasetName = scaleSpaceDataSetName;
			Arrays.fill(min, 0);
			System.arraycopy(
					n5.getDatasetAttributes(scaleSpaceDataSetName).getDimensions(), 0, size, 0, size.length);

			// if we downsampled to a z-size of 1, we need to stop
			if ( outDimensions[ 2 ] == 1 )
				maxScaleIndex = scaleIndex;
		}

		/* save faces */
		final String faceGroupName = options.getOutputGroupName() + "/face";
		n5.createGroup(faceGroupName);

		/* face 0 */
		extractFace(
				sc,
				options.getN5Path(),
				options.getInputDatasetName(),
				options.getMin(),
				options.getSize(),
				faceGroupName + "/s0",
				options.getBlockSize(),
				options.invert, // only face 0 needs filtering
				options.normalizeContrast, // only face 0 needs filtering
				0 );

		for (int scaleIndex = 1; scaleIndex <= maxScaleIndex; ++scaleIndex) {
			System.out.println("Scale level " + scaleIndex);
			final String scaleSpaceDataSetName = options.getOutputGroupName() + "/s" + scaleIndex;
			final DatasetAttributes scaleSpaceAttributes = n5.getDatasetAttributes(scaleSpaceDataSetName);
			extractFace(
					sc,
					options.getN5Path(),
					scaleSpaceDataSetName,
					new long[]{0, 0, 0},
					scaleSpaceAttributes.getDimensions(),
					faceGroupName + "/s" + scaleIndex,
					options.getBlockSize(),
					false,
					false,
					scaleIndex );
		}

		// downsample the s1 face
		if ( maxScaleIndex < 9 )
		{
			final N5WriterSupplier n5Supplier = () -> new N5FSWriter( options.getN5Path() );
			final int[] downsamplingFactorDelta = new int[] { 2, 2 };
			final int[] ds = new int[] { 1, 1 };

			// manually set the downsampling factors, the rest will "fall in place"
			n5.setAttribute(faceGroupName + "/s0", "downsamplingFactors", ds );

			for (int scaleIndex = 1; scaleIndex <= maxScaleIndex; ++scaleIndex)
			{
				ds[ 0 ] *= downsamplingFactorDelta[ 0 ];
				ds[ 1 ] *= downsamplingFactorDelta[ 1 ];

				// manually set the downsampling factors, the rest will "fall in place"
				n5.setAttribute(faceGroupName + "/s" + scaleIndex, "downsamplingFactors", ds);
			}

			for (int scaleIndex = maxScaleIndex+1; scaleIndex <= 9; ++scaleIndex) {
				N5DownsamplerSpark.downsample(
						sc,
						n5Supplier,
						faceGroupName + "/s" + (scaleIndex - 1),
						faceGroupName + "/s" + scaleIndex,
						downsamplingFactorDelta,
						options.getBlockSize()
					);
			}
		}

	}
}
