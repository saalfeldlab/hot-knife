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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import net.imagej.ImageJ;
import net.imagej.ops.OpService;
import net.imagej.ops.Ops.Filter.Gauss;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class GenerateFaceScaleSpace {

	static public double sigmaDiff(final double sourceSigma, final double targetSigma, final double scale) {

		final double s = targetSigma / scale;
		final double v = Math.max(0, s * s - sourceSigma * sourceSigma);
		return Math.sqrt(v);
	}

	/**
	 * Crops the dimensions of a {@link DataBlock} at a given offset to fit into
	 * and {@link Interval} of given dimensions. Fills long and int version of
	 * cropped block size. Also calculates the grid raster position plus a grid
	 * offset assuming that the offset divisible by block size without
	 * remainder.
	 *
	 * @param max
	 * @param offset
	 * @param blockSize
	 * @param croppedBlockDimensions
	 * @param gridPosition
	 */
	private static void cropBlockDimensions(
			final long[] max,
			final long[] offset,
			final int[] blockSize,
			final long[] croppedBlockDimensions,
			final long[] gridPosition) {
		for (int d = 0; d < max.length; ++d) {
			croppedBlockDimensions[d] = Math.min(blockSize[d], max[d] - offset[d] + 1);
			gridPosition[d] = offset[d] / blockSize[d];
		}
	}

	static private void downsample(
			final RandomAccessibleInterval<FloatType> source,
			final N5Writer n5,
			final String dataset,
			final int[] blockSize,
			final OpService opService,
			final ExecutorService exec) throws InterruptedException, ExecutionException, IOException {

		final IntervalView<FloatType> zeroMinSource = Views.zeroMin(source);
		final int scaleIndex = 1;
		final int sampleStepSize = net.imglib2.util.Util.pow(2, scaleIndex);
		final double sigma = sigmaDiff(0.5, 0.5, 1.0 / sampleStepSize);
		final Class<Gauss> opClass = Gauss.class;
		final double[] sigmas = new double[] { sigma, sigma, sigma };

		final RandomAccessibleInterval<FloatType> filtered = Lazy.process(
				Views.extendMirrorSingle(zeroMinSource),
				zeroMinSource,
				blockSize,
				new FloatType(),
				AccessFlags.setOf(),
				opService,
				opClass,
				sigmas);
		final SubsampleIntervalView<FloatType> subsampled = Views.subsample(filtered, sampleStepSize);

		System.out.println("Size: " + Arrays.toString(Intervals.dimensionsAsLongArray(subsampled)));

		final int n = source.numDimensions();
		final long[] max = Intervals.maxAsLongArray(subsampled);
		final long[] offset = new long[n];

		n5.createDataset(dataset, Intervals.dimensionsAsLongArray(subsampled), blockSize, DataType.FLOAT32, new GzipCompression());
		final DatasetAttributes attributes = n5.getDatasetAttributes(dataset);

		final ArrayList<Future<?>> futures = new ArrayList<>();
		for (int d = 0; d < n;) {

			final long[] fOffset = offset.clone();

			futures.add(
					exec.submit(
							() -> {

								final long[] gridPosition = new long[n];
								final long[] croppedBlockSize = new long[n];

								cropBlockDimensions(max, fOffset, blockSize, croppedBlockSize, gridPosition);

								System.out.println(Arrays.toString(gridPosition));

								final RandomAccessibleInterval<FloatType> sourceBlock = Views.offsetInterval(subsampled, fOffset, croppedBlockSize);
								try {
									N5Utils.saveBlock(sourceBlock, n5, dataset, attributes, gridPosition);
								} catch (final IOException e) {
									e.printStackTrace();
								}
							}));

			for (d = 0; d < n; ++d) {
				offset[d] += blockSize[d];
				if (offset[d] <= max[d])
					break;
				else
					offset[d] = 0;
			}
		}
		for (final Future<?> f : futures)
			f.get();
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final String n5Path = "/nrs/flyem/data/tmp/Z0115-22.n5";
		final String datasetName = "/slab-24/raw";
		final String topScaleSpaceDatasetName = "/slab-24/top";

		final int numProc = Runtime.getRuntime().availableProcessors();
//		final ExecutorService exec = Executors.newFixedThreadPool(Math.max(1, numProc / 2));
		final ExecutorService exec = Executors.newFixedThreadPool(Math.max(1, numProc - 2));
//		final ExecutorService exec = Executors.newFixedThreadPool(1);

		final ImageJ ij = new ImageJ();

		final N5Writer n5 = new N5FSWriter(n5Path);
		n5.createGroup(topScaleSpaceDatasetName);
		final DatasetAttributes attributes = n5.getDatasetAttributes(datasetName);
		final long[] topMin = new long[] {0, 8, 0};
		final long[] topDimensions = new long[] {
				attributes.getDimensions()[0],
				512,
				attributes.getDimensions()[2]};
//		final long[] topMin = new long[]{10000, 9, 10000};
//		final long[] topMax = new long[]{15000, topMin[ 1 ] + 512 - 1, 15000};


		final RandomAccessibleInterval<UnsignedByteType> source = (RandomAccessibleInterval<UnsignedByteType>)N5Utils.openVolatile(n5, datasetName);
		final RandomAccessibleInterval<FloatType> floatSource = Converters.convert(source, (a, b) -> b.set(a.getRealFloat()), new FloatType());

		final IntervalView<FloatType> roi = Views.offsetInterval(floatSource, topMin, topDimensions);
		RandomAccessibleInterval<FloatType> transposed = Views.permute(roi, 1, 2);

		final SharedQueue queue = new SharedQueue(Math.max(1, numProc / 2));

//		final BdvStackSource<Volatile<UnsignedByteType>> stackSource = BdvFunctions.show(
//				VolatileViews.wrapAsVolatile(
//						source,
//						queue),
//				topScaleSpaceDatasetName + "/4-face",
//				Bdv.options());
//
//		stackSource.setDisplayRange(0, 255);

		/* extract face 0 */
		N5Utils.save(
				Views.hyperSlice(transposed, 2, 0),
				n5,
				topScaleSpaceDatasetName + "/s0",
				new int[] {128, 128},
				new GzipCompression(),
				exec);

		/* downsample */
		for (int scaleIndex = 1; scaleIndex < 10; ++scaleIndex) {
			System.out.println("Scale level " + scaleIndex);
			final String scaleSpaceDataSetName = topScaleSpaceDatasetName + "/s" + scaleIndex;
			downsample(transposed, n5, scaleSpaceDataSetName, attributes.getBlockSize(), ij.op(), exec);
			transposed = N5Utils.openVolatile(n5, scaleSpaceDataSetName);
		}

		/* extract face 1--n */
		for (int scaleIndex = 1; scaleIndex < 10; ++scaleIndex) {
			System.out.println("Scale level " + scaleIndex);
			final String scaleSpaceDataSetName = topScaleSpaceDatasetName + "/s" + scaleIndex;
			final RandomAccessibleInterval<FloatType> scaledSource = N5Utils.openVolatile(n5, scaleSpaceDataSetName);
			final String topScaleSpaceFaceDatasetName = scaleSpaceDataSetName + "-face";
			N5Utils.save(
					Views.hyperSlice(scaledSource, 2, 0),
					n5,
					topScaleSpaceFaceDatasetName,
					new int[] {128, 128},
					new GzipCompression(),
					exec);
			n5.remove(scaleSpaceDataSetName);
		}

		exec.shutdown();

//		final SharedQueue queue = new SharedQueue(Math.max(1, numProc / 2));
//
		final BdvStackSource<VolatileFloatType> stackSource = BdvFunctions.<VolatileFloatType>show(
				VolatileViews.wrapAsVolatile(
						(RandomAccessibleInterval<FloatType>)N5Utils.openVolatile(n5, topScaleSpaceDatasetName + "/s4-face"),
						queue),
				topScaleSpaceDatasetName + "/s4-face",
				Bdv.options().is2D());
//				Bdv.options());

		stackSource.setDisplayRange(0, 255);





//		final int scaleIndex = 3;
//		final int sampleStepSize = net.imglib2.util.Util.pow(2, scaleIndex);
//		final double sigma = sigmaDiff(0.5, 0.5, 1.0 / sampleStepSize);
//		final Class<Gauss> opClass = Gauss.class;
//		final double[] sigmas = new double[]{sigma, sigma};
//
//		final IntervalView<UnsignedByteType> slice = Views.hyperSlice(source, 1, 9);
//
//		final RandomAccessibleInterval<UnsignedByteType> filtered = lazyProcessUnsignedByte(slice, new int[] {64, 64}, ij.op(), opClass, sigmas);
////		final RandomAccessibleInterval<UnsignedByteType> filtered = lazyProcess(source, attributes.getBlockSize(), ij.op(), opClass, sigmas);
//
//
//
//
//
//		final BdvStackSource<Volatile<UnsignedByteType>> stackSource = BdvFunctions.show(
//				Views.subsample(
//						VolatileViews.wrapAsVolatile(
//							filtered,
////							source,
//							//(RandomAccessibleInterval<UnsignedByteType>)N5Utils.openVolatile(n5, datasetName),
//							queue),
//						sampleStepSize),
//				"crop",
//				Bdv.options().is2D());
////				Bdv.options());
//
//		/* pre-fetch */


	}

}
