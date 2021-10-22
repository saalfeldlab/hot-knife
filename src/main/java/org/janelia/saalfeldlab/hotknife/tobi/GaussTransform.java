package org.janelia.saalfeldlab.hotknife.tobi;

import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.RealTransform;
import org.scijava.listeners.Listeners;

public class GaussTransform implements RealTransform {

	public interface ChangeListener {
		void transformParametersChanged();
	}

	private final Listeners.List<ChangeListener> listeners;
	private double maxSlope;
	private double minSigma;
	private double exph;
	private boolean active = false;
	private double sx0;
	private double sy0;
	private double sx1;
	private double sy1;
	private double stx; // sx1 - sx0
	private double sty; // sy1 - sy0
	private double sigmaSqu;

	public GaussTransform() {
		this(0.8, 100.0);
	}

	public GaussTransform(double maxSlope, double minSigma) {
		listeners = new Listeners.SynchronizedList<>();
		this.maxSlope = maxSlope;
		this.minSigma = minSigma;
		this.exph = Math.exp(-0.5) / maxSlope;
	}

	public void setMinSigma(double minSigma) {
		this.minSigma = minSigma;
		updateSigmaSqu();
		notifyParametersChanged();
	}

	public double getMinSigma() {
		return minSigma;
	}

	public void setMaxSlope(double maxSlope) {
		this.maxSlope = maxSlope;
		this.exph = Math.exp(-0.5) / maxSlope;
		updateSigmaSqu();
		notifyParametersChanged();
	}

	public double getMaxSlope() {
		return maxSlope;
	}

	public void setLine(double sx0, double sy0, double sx1, double sy1) {
		this.sx0 = sx0;
		this.sy0 = sy0;
		this.sx1 = sx1;
		this.sy1 = sy1;
		stx = sx0 - sx1;
		sty = sy0 - sy1;
		updateSigmaSqu();
		notifyParametersChanged();
	}

	public void setLineStart(double sx0, double sy0) {
		this.sx0 = sx0;
		this.sy0 = sy0;
		stx = sx0 - sx1;
		sty = sy0 - sy1;
		updateSigmaSqu();
		notifyParametersChanged();
	}

	public double[] getLineStart() {
		return new double[] {sx0, sy0};
	}

	public void setLineEnd(double sx1, double sy1) {
		this.sx1 = sx1;
		this.sy1 = sy1;
		stx = sx0 - sx1;
		sty = sy0 - sy1;
		updateSigmaSqu();
		notifyParametersChanged();
	}

	public double[] getLineEnd() {
		return new double[] {sx1, sy1};
	}

	@Deprecated
	public void setActive(boolean active) {
		this.active = active;
		notifyParametersChanged();
	}

	public double getSigma() {
		double h = Math.sqrt(stx * stx + sty * sty);
		return Math.max(minSigma, h * exph);
	}

	private void updateSigmaSqu() {
		final double sigma = getSigma();
		sigmaSqu = sigma * sigma;
	}

	public Listeners<ChangeListener> changeListeners() {
		return listeners;
	}

	private void notifyParametersChanged() {
		listeners.list.forEach(ChangeListener::transformParametersChanged);
	}



	@Override
	public int numSourceDimensions() {
		return 2;
	}

	@Override
	public int numTargetDimensions() {
		return 2;
	}

	@Override
	public void apply(final double[] source, final double[] target) {
		apply(RealPoint.wrap(source), RealPoint.wrap(target));
	}

	@Override
	public void apply(final RealLocalizable source, final RealPositionable target) {
		if (active) {
			double x = source.getDoublePosition(0);
			double y = source.getDoublePosition(1);

			double asqu = (x - sx1) * (x - sx1) + (y - sy1) * (y - sy1);
			double dt = Math.exp(asqu / (-2.0 * sigmaSqu));

			double transformedX = x + dt * stx;
			double transformedY = y + dt * sty;

			target.setPosition(transformedX, 0);
			target.setPosition(transformedY, 1);
		} else {
			target.setPosition(source);
		}
	}

	@Override
	public RealTransform copy() {
		return this;
	}

	public GaussTransform snapshot() {
		final GaussTransform t = new GaussTransform(maxSlope, minSigma);
		t.setLine(sx0,sy0,sx1,sy1);
		t.setActive(active);
		return t;
	}

	@Override
	public String toString() {
		return "GaussTransform{" +
				"maxSlope=" + maxSlope +
				", minSigma=" + minSigma +
				", active=" + active +
				", sx0=" + sx0 +
				", sy0=" + sy0 +
				", sx1=" + sx1 +
				", sy1=" + sy1 +
				'}';
	}
}
