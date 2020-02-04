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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.RealSum;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.imglib2.view.composite.GenericComposite;
import net.preibisch.surface.Test;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkSurfaceFit implements Callable<Void>{

	@Option(names = {"--n5Path"}, required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"-i", "--n5CostInput"}, required = true, description = "N5 input group for cost, e.g. /cost-0")
	private String inGroup = null;

	@Option(names = {"-o", "--n5SurfaceOutput"}, required = true, description = "N5 output group for , e.g. /surface-1")
	private String outGroup = null;

	@Option(names = {"-s", "--scale"}, description = "scale index, e.g. 10")
	private int scaleIndex = 0;

	/**
	 * Mask (0) all voxel z-columns with all equal cost.
	 *
	 * @param <T>
	 * @param cost
	 * @return
	 */
	private <T extends Type<T>> ArrayImg<UnsignedByteType, ByteArray> costMask(final RandomAccessibleInterval<T> cost) {

		final ArrayImg<UnsignedByteType, ByteArray> mask = ArrayImgs.unsignedBytes(cost.dimension(0), cost.dimension(1));
		final T reference = Util.getTypeFromInterval(cost).createVariable();
		final ArrayCursor<UnsignedByteType> maskCursor = mask.cursor();
		final long depth = cost.dimension(2);
		final Cursor<GenericComposite<T>> costCursor = Views.flatIterable(Views.collapse(cost)).cursor();
		while (maskCursor.hasNext()) {
			final UnsignedByteType maskValue = maskCursor.next();
			final GenericComposite<T> costValue = costCursor.next();
			reference.set(costValue.get(0));
			for (long z = 1; z < depth; ++z) {
				if (!reference.valueEquals(costValue.get(z))) {
					maskValue.set(1);
					break;
				}
			}
		}
		return mask;
	}


	private <T extends Type<T>, M extends NumericType<M>> void maskSlice(
			final RandomAccessibleInterval<T> slice,
			final RandomAccessibleInterval<M> mask,
			final T value) {

		final M zero = Util.getTypeFromInterval(mask).createVariable();
		zero.setZero();

		final Cursor<M> maskCursor = Views.flatIterable(mask).cursor();
		final Cursor<T> sliceCursor = Views.flatIterable(slice).cursor();
		while (sliceCursor.hasNext()) {
			sliceCursor.fwd();
			if (maskCursor.next().valueEquals(zero))
				sliceCursor.get().set(value);
		}
	}


	private RandomAccessibleInterval<FloatType> inpaintCost(
			final RandomAccessibleInterval<FloatType> cost,
			final RandomAccessibleInterval<UnsignedByteType> mask) {

		final ArrayList<ArrayImg<FloatType, FloatArray>> slices = new ArrayList<>();
		for (int z = 0; z < cost.dimension(2); ++z) {
			final FloatProcessor fpSlice = org.janelia.saalfeldlab.hotknife.util.Util.materialize(Views.hyperSlice(cost, 2, z));
			final ArrayImg<FloatType, FloatArray> slice = ArrayImgs.floats((float[])fpSlice.getPixels(), fpSlice.getWidth(), fpSlice.getHeight());
			maskSlice(slice, mask, new FloatType(Float.NaN));
			InpaintMasked.run(fpSlice);
			slices.add(slice);
		}

		return Views.stack(slices);
	}


	private RandomAccessibleInterval<FloatType> inpaintSurface(
			final RandomAccessibleInterval<FloatType> surface,
			final RandomAccessibleInterval<UnsignedByteType> mask) {

		final FloatProcessor fpSlice = org.janelia.saalfeldlab.hotknife.util.Util.materialize(surface);
		final ArrayImg<FloatType, FloatArray> slice = ArrayImgs.floats((float[])fpSlice.getPixels(), fpSlice.getWidth(), fpSlice.getHeight());

		System.out.println(slice.dimension(0) + ", " + slice.dimension(1) + "     " + mask.dimension(0) + ", " + mask.dimension(1));
		maskSlice(slice, mask, new FloatType(Float.NaN));

		new ImagePlus("maskedSlice", org.janelia.saalfeldlab.hotknife.util.Util.materialize(slice)).show();

		InpaintMasked.run(fpSlice);
		ImageJFunctions.show(slice);

		return slice;
	}


	private <T extends RealType<T>, M extends RealType<M>> double weightedAverage(
			final IterableInterval<T> values,
			final IterableInterval<M> weights) {

		final RealSum valueSum = new RealSum();
		final RealSum weightSum = new RealSum();

		final Cursor<M> weightsCursor = weights.cursor();
		final Cursor<T> valuesCursor = values.cursor();
		while (valuesCursor.hasNext()) {
			final double weight = weightsCursor.next().getRealDouble();
			weightSum.add(weight);
			valueSum.add(valuesCursor.next().getRealDouble() * weight);

			if (Double.isNaN(valuesCursor.get().getRealDouble()))
				System.out.println(weight);
		}
		return valueSum.getSum() / weightSum.getSum();
	}


	@Override
	public Void call() throws IOException {

		final N5Reader n5 = new N5FSReader(n5Path);

		final SparkConf conf = new SparkConf().setAppName(getClass().getCanonicalName());
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		final String dataset = inGroup + "/s" + scaleIndex;
		final RandomAccessibleInterval<UnsignedByteType> cost = N5Utils.openVolatile(n5, inGroup + "/s" + scaleIndex);
		final RandomAccessibleInterval<UnsignedByteType> permutedCost = Views.permute(cost, 1, 2);

		final ArrayImg<UnsignedByteType, ByteArray> mask = costMask(permutedCost);
		ImageJFunctions.show(mask);

		final RandomAccessibleInterval<FloatType> inpaintedCost = inpaintCost(
				Converters.convert(
							permutedCost,
							(a, b) -> b.set(a.getRealFloat()),
							new FloatType()),
				mask);

		final double[] downsamplingFactors = n5.getAttribute(dataset, "downsamplingFactors", double[].class);
		final double dzScale = downsamplingFactors[0] / downsamplingFactors[1];

		System.out.println(dzScale);

		final RandomAccessibleInterval<IntType> top = Test.process2(
				Views.offsetInterval(
						inpaintedCost,
						new long[] {0, 0, 0},
						new long[] {inpaintedCost.dimension(0), inpaintedCost.dimension(1), inpaintedCost.dimension(2) / 2}),
				(int)Math.round(dzScale),
				0,
				Integer.MAX_VALUE);

		final RandomAccessibleInterval<FloatType> topInpainted = inpaintSurface(
				Converters.convert(
						top,
						(a, b) -> b.set(a.getRealFloat()),
						new FloatType()),
				mask);

		final RandomAccessibleInterval<FloatType> topInpaintedOffset = Converters.convert(
				topInpainted,
				(a, b) -> b.set(a.get() - 1),
				new FloatType());

		final double topAvg = weightedAverage(
				Views.flatIterable(topInpaintedOffset),
				Views.flatIterable(mask));

		System.out.println(topAvg);

		ImageJFunctions.show(top);
		ImageJFunctions.show(topInpainted);






		final RandomAccessibleInterval<IntType> bot = Test.process2(
				Views.offsetInterval(
						inpaintedCost,
						new long[] {0, 0, inpaintedCost.dimension(2) / 2},
						new long[] {inpaintedCost.dimension(0), inpaintedCost.dimension(1), inpaintedCost.dimension(2) / 2}),
				(int)Math.round(dzScale),
				0,
				Integer.MAX_VALUE);

		final RandomAccessibleInterval<FloatType> botInpainted = inpaintSurface(
				Converters.convert(
						bot,
						(a, b) -> b.set(a.getRealFloat()),
						new FloatType()),
				mask);

		final RandomAccessibleInterval<FloatType> botInpaintedOffset = Converters.convert(
				botInpainted,
				(a, b) -> b.set(a.get() - 1),
				new FloatType());

		final double botAvg = weightedAverage(
				Views.flatIterable(botInpaintedOffset),
				Views.flatIterable(mask));

		System.out.println(botAvg);

		ImageJFunctions.show(bot);
		ImageJFunctions.show(botInpainted);

		sc.close();

		return null;
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		new ImageJ();

		CommandLine.call(new SparkSurfaceFit(), args);
	}
}

