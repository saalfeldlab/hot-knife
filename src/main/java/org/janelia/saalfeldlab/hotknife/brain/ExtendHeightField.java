package org.janelia.saalfeldlab.hotknife.brain;

import java.util.function.ToDoubleFunction;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

public class ExtendHeightField {

	/**
	 * Extend {@code heightfield} to infinity:
	 * First, the difference of the heightfield to the average plane is computed.
	 * This is then border-extended and faded to 0 (at {@code fadeToPlaneDist}).
	 * Then the average plane is added again, resulting in the original {@code heightfield} smoothly faded to the average plane out-ouf-bounds.
	 * Finally, this is faded to the {@code Z=avg} plane.
	 *
	 * @param heightfield
	 * @param avg
	 * @param plane
	 * @param fadeToPlaneDist
	 * @param fadeToAvgDist
	 *
	 * @return {@code heightfield} extended to infinity
	 */
	public static RandomAccessible<FloatType> extendHeightfield(
			final RandomAccessibleInterval<FloatType> heightfield,
			final double avg,
			final double[] plane,
			final double fadeToPlaneDist,
			final double fadeToAvgDist) {
		// make a FunctionRandomAccessible that subtracts the plane from the heightfield
		final ThreadLocal<RandomAccess<FloatType>> hfAccess = ThreadLocal.withInitial(() -> heightfield.randomAccess());
		final RandomAccessible<FloatType> planeDiff = new FunctionRandomAccessible<>(2,
				(xy, t) -> {
					final double x = xy.getDoublePosition(0);
					final double y = xy.getDoublePosition(1);
					final RandomAccess<FloatType> a = hfAccess.get();
					a.setPosition(xy);
					final double z = a.get().getRealDouble();
					final double pz = plane[0] * x + plane[1] * y + plane[2];
					t.set((float) (z - pz));
				},
				FloatType::new);
		// ... and border-extend it
		final RandomAccessible<FloatType> planeDiffExt = Views.extendBorder(Views.interval(planeDiff, heightfield));

		// then first fade it to the plane and then further fade it to the avg
		final ToDoubleFunction<Localizable> fadeToPlaneWeights = intervalDistWeights(heightfield, fadeToPlaneDist);
		final ToDoubleFunction<Localizable> fadeToAvgWeights = intervalDistWeights(heightfield, fadeToAvgDist);
		final ThreadLocal<RandomAccess<FloatType>> pdAccess = ThreadLocal.withInitial(() -> planeDiffExt.randomAccess());
		final RandomAccessible<FloatType> planeFadeToAvg = new FunctionRandomAccessible<>(2,
				(xy, t) -> {
					final double x = xy.getDoublePosition(0);
					final double y = xy.getDoublePosition(1);
					final RandomAccess<FloatType> a = pdAccess.get();
					a.setPosition(xy);
					final double z = a.get().getRealDouble() * fadeToPlaneWeights.applyAsDouble(xy);
					final double pz = plane[0] * x + plane[1] * y + plane[2];

					final double w = fadeToAvgWeights.applyAsDouble(xy);
					t.set((float) (avg + (z + pz - avg) * w));
				},
				FloatType::new);

		return planeFadeToAvg;
	}

	/**
	 * Create a {@code ToDoubleFunction} that computes a weight for a
	 * coordinate. The weight is 1, if the coordinate is inside the specified
	 * {@code interval}. Outside the interval, the weight fades to 0
	 * exponentially with the distance to the interval.
	 *
	 * @param fadeDist
	 * 		approximate distance to interval where weight fades to zero
	 */
	private static ToDoubleFunction<Localizable> intervalDistWeights(final RealInterval interval, final double fadeDist) {
		final double[] min = interval.minAsDoubleArray();
		final double[] max = interval.maxAsDoubleArray();
		final int n = interval.numDimensions();
		final double wFadeOut = 4.0 / fadeDist;
		return (pos) -> {
			double squdist = 0;
			for (int d = 0; d < n; d++) {
				final double x = pos.getDoublePosition(d);
				final double dd = distToInterval(x, min[d], max[d]) * wFadeOut;
				squdist += dd * dd;
			}
			return Math.exp(squdist / (-2.0));
		};
	}

	private static double distToInterval(final double x, final double min, final double max) {
		if (x < min)
			return min - x;
		else if (x > max)
			return x - max;
		else
			return 0;
	}
}
