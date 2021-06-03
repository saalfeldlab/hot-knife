package org.janelia.saalfeldlab.hotknife.cost;

import ij.process.FloatProcessor;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale2D;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import net.imglib2.view.composite.GenericComposite;
import org.janelia.saalfeldlab.hotknife.FlattenTransform;
import org.janelia.saalfeldlab.hotknife.InpaintMasked;
import org.janelia.saalfeldlab.hotknife.util.Transform;

import java.util.ArrayList;

public class CostUtils {
    /**
     * Based on code from hot-knife
     * @param cost
     * @return
     */
    public static RandomAccessibleInterval<FloatType> initializeCost(RandomAccessibleInterval<UnsignedByteType> cost) {

        final RandomAccessibleInterval<UnsignedByteType> permutedCost = Views.permute(cost, 1, 2);
        final RandomAccessibleInterval<UnsignedByteType> mask = costMask(permutedCost);
        final RandomAccessibleInterval<FloatType> inpaintedCost = inpaintCost(
                Converters.convert(
                            permutedCost,
                            (a, b) -> b.set(a.getRealFloat()),
                            new FloatType()),
                mask);

        return Views.permute(inpaintedCost, 1, 2);
    }


	/**
	 * Mask (0) all voxel z-columns with all equal cost.
	 *
     * Stolen from saalfeldlab/hot-knife:SparkSurfaceFit
     *
	 * @param <T>
	 * @param cost
	 * @return
	 */
	private static <T extends Type<T>> RandomAccessibleInterval<UnsignedByteType> costMask(final RandomAccessibleInterval<T> cost) {

		System.out.println(Util.printInterval(cost));
		final ArrayImg<UnsignedByteType, ByteArray> mask = ArrayImgs.unsignedBytes(cost.dimension(0), cost.dimension(1));
		final T reference = Util.getTypeFromInterval(cost).createVariable();
		final ArrayCursor<UnsignedByteType> maskCursor = mask.cursor();
		final long depth = cost.dimension(2);
		//final Cursor<? extends GenericComposite<T>> costCursor = Views.flatIterable(Views.collapse(cost)).cursor();
		final Cursor<? extends GenericComposite<T>> costCursor = (Cursor<? extends GenericComposite<T>>) Views.flatIterable(Views.collapse(cost)).cursor();
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
		return Views.translate(mask, cost.min(0), cost.min(1));
	}

    /**
     * Stolen from saalfeldlab/hot-knife:Util
     * @param source
     * @param target
     * @param <T>
     */
	public static final <T extends Type<T>> void copy(
			final RandomAccessible<? extends T> source,
			final RandomAccessibleInterval<T> target) {

		Views.flatIterable(Views.interval(Views.pair(source, target), target)).forEach(
				pair -> pair.getB().set(pair.getA()));
	}

    /**
     * Stolen from saalfeldlab/hot-knife:Util
     * @param source
     * @return
     */
	public static final FloatProcessor materialize(final RandomAccessibleInterval<FloatType> source) {
		final FloatProcessor target = new FloatProcessor((int) source.dimension(0), (int) source.dimension(1));
		copy(
				Views.zeroMin(source),
				ArrayImgs.floats(
						(float[]) target.getPixels(),
						target.getWidth(),
						target.getHeight()));
		return target;
	}

	/**
	 * Set all values in slice that are 0 in mask to value.
	 * Ignores offsets and assumes that slice and mask have the same size.
	 *
     * Stolen from saalfeldlab/hot-knife:SparkSurfaceFit
     *
	 * @param <T>
	 * @param <M>
	 * @param slice
	 * @param mask
	 * @param value
	 */
	private static <T extends Type<T>, M extends NumericType<M>> void maskSlice(
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

    /**
     * Stolen from saalfeldlab/hot-knife:SparkSurfaceFit
     * @param cost
     * @param mask
     * @param <M>
     * @return
     */
	private static <M extends RealType<M>> RandomAccessibleInterval<FloatType> inpaintCost(
			final RandomAccessibleInterval<FloatType> cost,
			final RandomAccessibleInterval<M> mask) {

		final ArrayList<ArrayImg<FloatType, FloatArray>> slices = new ArrayList<>();
		for (long z = cost.min(2); z <= cost.max(2); ++z) {
			final FloatProcessor fpSlice = materialize(Views.hyperSlice(cost, 2, z));
			final ArrayImg<FloatType, FloatArray> slice = ArrayImgs.floats((float[])fpSlice.getPixels(), fpSlice.getWidth(), fpSlice.getHeight());
			maskSlice(slice, mask, new FloatType(Float.NaN));
			InpaintMasked.run(fpSlice);
			slices.add(slice);
		}

		final long[] min = new long[cost.numDimensions()];
		cost.min(min);
		return Views.translate(Views.stack(slices), min);
	}

	public static RandomAccessibleInterval<UnsignedByteType> floatAsUnsignedByte(RandomAccessibleInterval<FloatType> floatCost) {
	    return Converters.convert(floatCost, (a, b) -> b.set((int) Math.round(a.get())), new UnsignedByteType());
    }

	public static RandomAccessibleInterval<UnsignedByteType> doubleAsUnsignedByte(RandomAccessibleInterval<DoubleType> floatCost) {
	    return Converters.convert(floatCost, (a, b) -> b.set((int) Math.round(a.get())), new UnsignedByteType());
    }

	// From hotknife
	public static final FunctionRandomAccessible<DoubleType> zRange(
			final double min,
			final double max,
			final double scale,
			final double stretch) {

		return new FunctionRandomAccessible<>(
				3,
				(location, value) -> {
					final double z = location.getDoublePosition(2);
					value.set(
							scale / (stretch * Math.abs(z - min) + 1) +
							scale / (stretch * Math.abs(z - max) + 1));
				},
				DoubleType::new);
	}

    // From NailFlat in BigWarp
	public static RandomAccessibleInterval<DoubleType> heightfieldOverlay(Interval img, RandomAccessibleInterval<FloatType> min, RandomAccessibleInterval<FloatType> max, double minMean, double maxMean ) {
		int costStep = 6;
		int heightmapScale = 1;
		int padding = 2000;

		double transformScaleX = costStep;
		double transformScaleY = costStep;
		final Scale2D transformScale = new Scale2D(transformScaleX, transformScaleY);

		final FlattenTransform ft = new FlattenTransform(
								RealViews.affine(
										Views.interpolate(
												Views.extendBorder(min),
												new NLinearInterpolatorFactory<>()),
										transformScale),
								RealViews.affine(
										Views.interpolate(
												Views.extendBorder(max),
												new NLinearInterpolatorFactory<>()),
										transformScale),
				( minMean + 0.5 ) * heightmapScale - 0.5,
				( maxMean + 0.5 ) * heightmapScale - 0.5);

		final IntervalView<DoubleType> zRange = Views.interval(
				zRange(( minMean + 0.5 ) - 0.5,
						( maxMean + 0.5 ) - 0.5,
						255,
						1),
				new long[]{0, 0, Math.round(( minMean + 0.5 ) - 0.5) - padding},
				new long[]{img.dimension(0), img.dimension(2), Math.round(( maxMean + 0.5 ) - 0.5) + padding});

		final RandomAccessibleInterval<DoubleType> heightmapRai =
				Transform.createTransformedInterval(
						zRange,
						img,
						ft,
						new DoubleType(0));

		return heightmapRai;
	}
}
