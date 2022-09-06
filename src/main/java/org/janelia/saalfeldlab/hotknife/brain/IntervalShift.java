package org.janelia.saalfeldlab.hotknife.brain;

import net.imglib2.RealInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.RealTransform;

public class IntervalShift implements RealTransform {

	private final int n;
	private final double[] min;
	private final double[] max;
	private final double[] t;
	private final double[] w;

	/**
	 * @param interval interval (in target coordinates) that will be shifted
	 * @param translation vector by which the interval will be shifted (target = source - translation)
	 * @param weights can be roughly interpreted as how big the fade-out region around the interval is in each direction
	 */
	public IntervalShift(final RealInterval interval, final double[] translation, final double[] weights) {
		assert interval.numDimensions() == translation.length;
		assert interval.numDimensions() == weights.length;

		n = interval.numDimensions();
		min = interval.minAsDoubleArray();
		max = interval.maxAsDoubleArray();
		t = new double[n];
		w = new double[n];

		for (int d = 0; d < n; d++) {
			t[d] = -translation[d];
			w[d] = 1.0 / weights[d];
		}
	}

	@Override
	public int numSourceDimensions() {
		return n;
	}

	@Override
	public int numTargetDimensions() {
		return n;
	}

	@Override
	public void apply(final double[] source, final double[] target) {
		apply(RealPoint.wrap(source), RealPoint.wrap(target));
	}

	@Override
	public void apply(final RealLocalizable source, final RealPositionable target) {
		double squdist = 0;
		for (int d = 0; d < n; d++) {
			final double x = source.getDoublePosition(d);
			final double dd = distToInterval(x, min[d], max[d]) * w[d];
			squdist += dd * dd;
		}
		final double s = Math.exp(squdist / (-2.0));
		for (int d = 0; d < n; d++) {
			target.setPosition(source.getDoublePosition(d) + t[d] * s, d);
		}
	}

	@Override
	public RealTransform copy() {
		return this;
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
