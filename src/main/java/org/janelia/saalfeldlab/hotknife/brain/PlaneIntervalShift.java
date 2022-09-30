package org.janelia.saalfeldlab.hotknife.brain;

import net.imglib2.RealInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.RealTransform;

public class PlaneIntervalShift implements RealTransform {

	private final int orthoDim;
	private final int n;
	private final double[] min;
	private final double[] max;
	private final double[] t;
	private final double[] w;

	/**
	 * @param orthoDim
	 * 		the "special dimension".
	 * 		Translation is orthogonal to this dimension, that is, {@code translation[d]==0}.
	 * 		        {@code weights[d]} is interpreted slightly different from other dims.
     * @param interval
     * 		interval (in target coordinates) that will be shifted
     * @param translation
     * 		vector by which the interval will be shifted (target = source - translation)
     * @param weights
     * 		can be roughly interpreted as how big the fade-out region around the interval is in each direction
     */
	public PlaneIntervalShift(final int orthoDim, final RealInterval interval, final double[] translation, final double[] weights) {
		assert interval.numDimensions() == translation.length;
		assert interval.numDimensions() == weights.length;

		n = interval.numDimensions();
		this.orthoDim = orthoDim;
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

	private static double squ(final double x) {
		return x * x;
	}

	@Override
	public void apply(final RealLocalizable source, final RealPositionable target) {
		final double ss = squ( Math.min(1.0, Math.max(0.0, 1.0 - distToInterval(source.getDoublePosition(orthoDim), min[orthoDim], max[orthoDim]) * w[orthoDim])));
		double squdist = 0;
		for (int d = 0; d < n; d++) {
			if (d == orthoDim)
				continue;
			squdist += squ(distToInterval(source.getDoublePosition(d), min[d], max[d]) * w[d]);
		}
		final double s = ss * Math.exp(squdist / (-2.0));
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
