package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.viewer.TransformListener;
import java.util.function.DoubleSupplier;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;

public class ViewerCoords implements TransformListener<AffineTransform3D> {

	private final AffineTransform2D sourceToViewer = new AffineTransform2D();
	private double scale = 1;

	@Override
	public void transformChanged(final AffineTransform3D t) {
		setViewerTransform(t);
	}

	public synchronized void setViewerTransform(final AffineTransform3D viewerTransform) {
		final double m00 = viewerTransform.get(0, 0);
		final double m01 = viewerTransform.get(0, 1);
		final double m03 = viewerTransform.get(0, 3);
		final double m10 = viewerTransform.get(1, 0);
		final double m11 = viewerTransform.get(1, 1);
		final double m13 = viewerTransform.get(1, 3);
		sourceToViewer.set(
				m00, m01, m03,
				m10, m11, m13);
		scale = Math.sqrt(m00 * m00 + m01 * m11);
	}

	public interface CoordinateConsumer {

		void accept(double x, double y);
	}

	public interface CoordinateSupplier {

		double[] get();
	}

	public synchronized void applyTransformed(CoordinateConsumer f, double x, double y) {
		final double[] p = new double[2];
		sourceToViewer.applyInverse(p, new double[] {x, y});
		f.accept(p[0], p[1]);
	}

	public synchronized double[] of(CoordinateSupplier f) {
		final double[] p = new double[2];
		sourceToViewer.apply(f.get(), p);
		return p;
	}

	public synchronized double of(DoubleSupplier f) {
		return f.getAsDouble() * scale;
	}
}
