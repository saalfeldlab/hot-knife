package org.janelia.saalfeldlab.hotknife.tobi;

import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.util.LinAlgHelpers;
import org.scijava.listeners.Listeners;

public class GaussTransform implements RealTransform {

	public interface ChangeListener {
		void transformParametersChanged();
	}

	private final Listeners.List<ChangeListener> listeners;
	private double maxSlope;
	private double minSigma;
	private final double[] ao;
	private double af;

	private double exph;
	private double m00;
	private double m01;
	private double m11;
	private double sx0;
	private double sy0;
	private double sx1;
	private double sy1;
	private double stx; // sx1 - sx0
	private double sty; // sy1 - sy0

	public GaussTransform() {
		this(0.8, 100.0);
	}

	public GaussTransform(double maxSlope, double minSigma) {
		listeners = new Listeners.SynchronizedList<>();
		this.maxSlope = maxSlope;
		this.minSigma = minSigma;
		this.ao = new double[] {1, 0};
		this.af = 1;

		this.exph = Math.exp(-0.5) / maxSlope;
	}

	public void setMinSigma(double minSigma) {
		this.minSigma = minSigma;
		updateCovariance();
		notifyParametersChanged();
	}

	public double getMinSigma() {
		return minSigma;
	}

	public void setMaxSlope(double maxSlope) {
		this.maxSlope = maxSlope;
		this.exph = Math.exp(-0.5) / maxSlope;
		updateCovariance();
		notifyParametersChanged();
	}

	public double getMaxSlope() {
		return maxSlope;
	}

	public void setAnisotropyOrientation(final double aox, final double aoy) {
		final double l = Math.sqrt(aox * aox + aoy * aoy);
		if (l < 1.0E-06) {
			ao[0] = 1;
			ao[1] = 0;
		} else {
			ao[0] = aox / l;
			ao[1] = aoy / l;
		}
		updateCovariance();
		notifyParametersChanged();
	}

	public double[] getAnisotropyOrientation() {
		return new double[] {ao[0], ao[1]};
	}


	public void setAnisotropyPow(final double af) {
		this.af = af;
		updateCovariance();
		notifyParametersChanged();
	}

	public double getAnisotropyPow() {
		return af;
	}

	public void setLine(double sx0, double sy0, double sx1, double sy1) {
		this.sx0 = sx0;
		this.sy0 = sy0;
		this.sx1 = sx1;
		this.sy1 = sy1;
		stx = sx0 - sx1;
		sty = sy0 - sy1;
		updateCovariance();
		notifyParametersChanged();
	}

	public void setLineStart(double sx0, double sy0) {
		this.sx0 = sx0;
		this.sy0 = sy0;
		stx = sx0 - sx1;
		sty = sy0 - sy1;
		updateCovariance();
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
		updateCovariance();
		notifyParametersChanged();
	}

	public double[] getLineEnd() {
		return new double[] {sx1, sy1};
	}

	public double getSigma() {
		double h = Math.sqrt(stx * stx + sty * sty);
		return Math.max(minSigma, h * exph);
	}

	private void updateCovariance() {
		final double sigma = getSigma();

		final double[][] R = {{ao[0], -ao[1]}, {ao[1], ao[0]}};
		double sigmaX = sigma;
		double sigmaY = sigma;
		if ( af >= 0 ) {
			sigmaX *= Math.pow(2, af);
		} else {
			sigmaY *= Math.pow(2, -af);
		}
		double[][] S = {{sigmaX * sigmaX, 0}, {0, sigmaY * sigmaY}};
		double[][] I = new double[2][2];
		LinAlgHelpers.multATB(R, S, I);
		LinAlgHelpers.mult(I, R, S);
		LinAlgHelpers.invertSymmetric2x2(S, I);
		m00 = -0.5 * I[0][0];
		m01 = -1.0 * I[0][1];
		m11 = -0.5 * I[1][1];
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
		double x = source.getDoublePosition(0);
		double y = source.getDoublePosition(1);

		double a = (x - sx1);
		double b = (y - sy1);
		double dt = Math.exp(m00 * a * a + m01 * a * b + m11 * b * b);

		double transformedX = x + dt * stx;
		double transformedY = y + dt * sty;

		target.setPosition(transformedX, 0);
		target.setPosition(transformedY, 1);
	}

	@Override
	public RealTransform copy() {
		return this;
	}

	public GaussTransform snapshot() {
		final GaussTransform t = new GaussTransform(maxSlope, minSigma);
		t.setLine(sx0,sy0,sx1,sy1);
		return t;
	}

	@Override
	public String toString() {
		return "GaussTransform{" +
				"maxSlope=" + maxSlope +
				", minSigma=" + minSigma +
				", sx0=" + sx0 +
				", sy0=" + sy0 +
				", sx1=" + sx1 +
				", sy1=" + sy1 +
				'}';
	}
}
