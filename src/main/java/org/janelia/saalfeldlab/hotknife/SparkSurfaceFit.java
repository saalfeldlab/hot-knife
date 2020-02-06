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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import ij.ImageJ;
import ij.process.FloatProcessor;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.InverseRealTransform;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.RealSum;
import net.imglib2.util.ValuePair;
import net.imglib2.view.IntervalView;
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
		final T reference = net.imglib2.util.Util.getTypeFromInterval(cost).createVariable();
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


	private static <T extends Type<T>, M extends NumericType<M>> void maskSlice(
			final RandomAccessibleInterval<T> slice,
			final RandomAccessibleInterval<M> mask,
			final T value) {

		final M zero = net.imglib2.util.Util.getTypeFromInterval(mask).createVariable();
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
			final FloatProcessor fpSlice = Util.materialize(Views.hyperSlice(cost, 2, z));
			final ArrayImg<FloatType, FloatArray> slice = ArrayImgs.floats((float[])fpSlice.getPixels(), fpSlice.getWidth(), fpSlice.getHeight());
			maskSlice(slice, mask, new FloatType(Float.NaN));
			InpaintMasked.run(fpSlice);
			slices.add(slice);
		}

		return Views.stack(slices);
	}


	private static <M extends RealType<M>> RandomAccessibleInterval<FloatType> inpaintHeightField(
			final RandomAccessibleInterval<FloatType> heightField,
			final RandomAccessibleInterval<M> mask) {

		final FloatProcessor fpSlice = Util.materialize(heightField);
		final ArrayImg<FloatType, FloatArray> slice =
				ArrayImgs.floats(
						(float[])fpSlice.getPixels(),
						fpSlice.getWidth(),
						fpSlice.getHeight());
		maskSlice(slice, mask, new FloatType(Float.NaN));
		InpaintMasked.run(fpSlice);

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


	private <T extends RealType<T>> double[] minMax(
			final IterableInterval<T> values) {

		final double[] minMax = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE};

		for (final T value : values) {
			final double v = value.getRealDouble();
			if (minMax[0] > v) minMax[0] = v;
			if (minMax[1] < v) minMax[1] = v;
		}
		return minMax;
	}


	private static <
					T extends RealType<T>,
					M extends RealType<M>> RandomAccessibleInterval<FloatType> calculateHeightField(
							final RandomAccessible<T> transformedCost,
							final RandomAccessibleInterval<M> mask,
							final RealRandomAccessible<DoubleType> heightFieldScaled,
							final double offsetZScaledAvg,
							final long padding,
							final int maxStepSize) {

		final long min = (long)Math.floor(offsetZScaledAvg - padding);

		final IntervalView<T> cropTransformCost = Views.offsetInterval(
				transformedCost,
				new long[] {mask.min(0), mask.min(1), min},
				new long[] {mask.dimension(0), mask.dimension(1), padding * 2});

		final RandomAccessibleInterval<IntType> heightFieldUpdate = Test.process2(
				cropTransformCost,
				maxStepSize,
				0,
				Integer.MAX_VALUE);

		final RandomAccessibleInterval<DoubleType> doubleFixedHeightField = Converters.convert(
				heightFieldUpdate,
				(a, b) -> b.setReal(a.get() - 1 - padding),
				new DoubleType());

		final RandomAccessibleInterval<FloatType> updatedMinField = Converters.convert(
			Views.collapseReal(
					Views.stack(
						Views.offsetInterval(
								Views.raster(heightFieldScaled),
								heightFieldUpdate),
						doubleFixedHeightField)),
			(a, b) -> b.set(a.get(0).getRealFloat() + a.get(1).getRealFloat()),
			new FloatType());

		return inpaintHeightField(
				updatedMinField,
				mask);
	}




	/**
	 * Generate an updated min height field for a cost function warped by
	 * a given min and max height field that are usually lower scale.
	 *
	 * @param <T>
	 * @param cost at target resolution
	 * @param minField in scaled and shifted z-coordinates according to scale
	 * @param maxField in scaled and shifted z-coordinates according to scale
	 * @param minAvg weighted average of minField
	 * @param maxAvg weighted average of maxField
	 * @param scale scale factors transforming minFIeld and maxField to cost
	 * @param padding range of the cost function around the prior min face
	 *
	 * @return
	 */
	public static <
					T extends RealType<T>,
					M extends RealType<M>,
					F extends RealType<F>> ValuePair<RandomAccessibleInterval<FloatType>, RandomAccessibleInterval<FloatType>> updateMinHeightFields(
			final RandomAccessibleInterval<T> cost,
			final RandomAccessibleInterval<M> mask,
			final RandomAccessibleInterval<F> minField,
			final RandomAccessibleInterval<F> maxField,
			final double minAvg,
			final double maxAvg,
			final double[] scale,
			final long padding,
			final int maxStepSize) {

		final RealRandomAccessible<DoubleType> minFieldScaled =
				Transform.scaleAndShiftHeightField(
						Transform.scaleAndShiftHeightFieldValues(
								minField,
								scale[2],
								0),
						Arrays.copyOf(scale, 2));

//		ImageJFunctions.show(Views.interval(Views.raster(minFieldScaled), cost), "min field scaled");

		final RealRandomAccessible<DoubleType> maxFieldScaled =
				Transform.scaleAndShiftHeightField(
						Transform.scaleAndShiftHeightFieldValues(
								maxField,
								scale[2],
								0),
						Arrays.copyOf(scale, 2));

//		ImageJFunctions.show(Views.interval(Views.raster(maxFieldScaled), cost), "max field scaled");

		final double offsetZScaledMinAvg = (minAvg + 0.5) * scale[2] - 0.5;
		final double offsetZScaledMaxAvg = (maxAvg + 0.5) * scale[2] - 0.5;

		final FlattenTransform<DoubleType> flatteningTransform =
				new FlattenTransform<>(
						minFieldScaled,
						maxFieldScaled,
						offsetZScaledMinAvg,
						offsetZScaledMaxAvg);

		final RealTransformRandomAccessible<T, InverseRealTransform> transformedCost = RealViews.transform(
				Views.interpolate(
						Views.extendBorder(cost),
						new NLinearInterpolatorFactory<>()),
				flatteningTransform);

//		ImageJFunctions.show(Views.interval(Views.raster(transformedCost), cost), "transformed cost");


		final RandomAccessibleInterval<FloatType> updatedMinField = calculateHeightField(
				transformedCost,
				mask,
				minFieldScaled,
				offsetZScaledMinAvg,
				padding,
				maxStepSize);

		final RandomAccessibleInterval<FloatType> updatedMaxField = calculateHeightField(
				transformedCost,
				mask,
				maxFieldScaled,
				offsetZScaledMaxAvg,
				padding,
				maxStepSize);

		return new ValuePair<>(updatedMinField, updatedMaxField);
	}


	@Override
	public Void call() throws IOException {

		final N5Reader n5 = new N5FSReader(n5Path);

		final SparkConf conf = new SparkConf().setAppName(getClass().getCanonicalName());
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");



		final String dataset = inGroup + "/s" + scaleIndex;
		final RandomAccessibleInterval<UnsignedByteType> cost = N5Utils.openVolatile(n5, dataset);
		final RandomAccessibleInterval<UnsignedByteType> permutedCost = Views.permute(cost, 1, 2);

		final ArrayImg<UnsignedByteType, ByteArray> mask = costMask(permutedCost);
		ImageJFunctions.show(mask, "mask");

		final RandomAccessibleInterval<FloatType> inpaintedCost = inpaintCost(
				Converters.convert(
							permutedCost,
							(a, b) -> b.set(a.getRealFloat()),
							new FloatType()),
				mask);



		final String dataset2 = inGroup + "/s" + (scaleIndex - 1);
		final RandomAccessibleInterval<UnsignedByteType> cost2 = N5Utils.openVolatile(n5, dataset2);
		final RandomAccessibleInterval<UnsignedByteType> permutedCost2 = Views.permute(cost2, 1, 2);

		final ArrayImg<UnsignedByteType, ByteArray> mask2 = costMask(permutedCost);
		ImageJFunctions.show(mask2, "mask2");

		final RandomAccessibleInterval<FloatType> inpaintedCost2 = inpaintCost(
				Converters.convert(
							permutedCost2,
							(a, b) -> b.set(a.getRealFloat()),
							new FloatType()),
				mask2);





		final double[] downsamplingFactors = n5.getAttribute(dataset, "downsamplingFactors", double[].class);
		final double dzScale = downsamplingFactors[0] / downsamplingFactors[1];

		System.out.println(dzScale);

		final long constantMin = permutedCost.dimension(2) / 4;
		final long constantMax = permutedCost.max(2) - constantMin;

		final RandomAccessibleInterval<FloatType> constantMinField =
				Views.offsetInterval(
						new FunctionRandomAccessible<>(
							2,
							(a, b) -> b.set(constantMin),
							FloatType::new),
						mask);

		final RandomAccessibleInterval<FloatType> constantMaxField =
				Views.offsetInterval(
						new FunctionRandomAccessible<>(
							2,
							(a, b) -> b.set(constantMax),
							FloatType::new),
						mask);



		/* test updateMinHeightField */
		final ValuePair<RandomAccessibleInterval<FloatType>, RandomAccessibleInterval<FloatType>> updatedHeightFields = updateMinHeightFields(
				inpaintedCost,
				mask,
				constantMinField,
				constantMaxField,
				constantMin,
				constantMax,
				new double[] {1, 1, 1},
				constantMin,
				(int)Math.round(dzScale));

		ImageJFunctions.show(updatedHeightFields.getA(), "updated min height field");
		ImageJFunctions.show(updatedHeightFields.getB(), "updated max height field");





		final RandomAccessibleInterval<IntType> top = Test.process2(
				Views.offsetInterval(
						inpaintedCost,
						new long[] {0, 0, 0},
						new long[] {inpaintedCost.dimension(0), inpaintedCost.dimension(1), inpaintedCost.dimension(2) / 2}),
				(int)Math.round(dzScale),
				0,
				Integer.MAX_VALUE);

		final RandomAccessibleInterval<FloatType> topInpainted = inpaintHeightField(
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

		final double[] topMinMax = minMax(Views.flatIterable(topInpaintedOffset));

		System.out.println(topAvg + " " + Arrays.toString(topMinMax));

//		ImageJFunctions.show(top, "top");
		ImageJFunctions.show(topInpaintedOffset, "topInpainted offset");


		final RandomAccessibleInterval<IntType> bot = Test.process2(
				Views.offsetInterval(
						inpaintedCost,
						new long[] {0, 0, inpaintedCost.dimension(2) / 2},
						new long[] {inpaintedCost.dimension(0), inpaintedCost.dimension(1), inpaintedCost.dimension(2) / 2}),
				(int)Math.round(dzScale),
				0,
				Integer.MAX_VALUE);

		final RandomAccessibleInterval<FloatType> botInpainted = inpaintHeightField(
				Converters.convert(
						bot,
						(a, b) -> b.set(a.getRealFloat()),
						new FloatType()),
				mask);

		final RandomAccessibleInterval<FloatType> botInpaintedOffset = Converters.convert(
				botInpainted,
				(a, b) -> b.set(a.get() - 1 + inpaintedCost.dimension(2) / 2),
				new FloatType());

		final double botAvg = weightedAverage(
				Views.flatIterable(botInpaintedOffset),
				Views.flatIterable(mask));

		final double[] botMinMax = minMax(Views.flatIterable(botInpaintedOffset));

		System.out.println(botAvg + " " + Arrays.toString(topMinMax));

//		ImageJFunctions.show(bot);
//		ImageJFunctions.show(botInpainted);



		final double topAvgUpdated = weightedAverage(
				Views.flatIterable(updatedHeightFields.getA()),
				Views.flatIterable(mask));
		final double botAvgUpdated = weightedAverage(
				Views.flatIterable(updatedHeightFields.getB()),
				Views.flatIterable(mask));

		System.out.println(topAvgUpdated);
		System.out.println(botAvgUpdated);

		/* test updateMinHeightField */
		final ValuePair<RandomAccessibleInterval<FloatType>, RandomAccessibleInterval<FloatType>> updatedHeightFields2 = updateMinHeightFields(
				inpaintedCost2,
				mask2,
				updatedHeightFields.getA(),
				updatedHeightFields.getB(),
				topAvgUpdated,
				botAvgUpdated,
				new double[] {1, 1, 4},
				9,
				(int)Math.round(dzScale) * 4);

		ImageJFunctions.show(updatedHeightFields2.getA(), "updated min height field 2");
		ImageJFunctions.show(updatedHeightFields2.getB(), "updated max height field 2");




		sc.close();

		return null;
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		new ImageJ();

		CommandLine.call(new SparkSurfaceFit(), args);
	}
}

