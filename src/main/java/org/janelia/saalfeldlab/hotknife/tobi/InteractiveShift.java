package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.tools.brightness.ConverterSetup;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.viewer.ConverterSetups;
import bdv.viewer.OverlayRenderer;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.ViewerPanel;
import ij.IJ;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.List;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.scijava.ui.behaviour.DragBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Behaviours;

public class InteractiveShift {

	static Img<UnsignedByteType> checkerboard(final int w, final int h, final int cw, final int ch) {
		Img<UnsignedByteType> img = ArrayImgs.unsignedBytes(w, h);
		Cursor<UnsignedByteType> c = img.localizingCursor();
		while (c.hasNext()) {
			c.fwd();
			final int x = c.getIntPosition(0);
			final int y = c.getIntPosition(1);
			final int i = ((x / cw) + (y / ch)) % 2;
			c.get().set(i == 0 ? 255 : 0);
		}
		return img;
	}

	public static void main(String[] args) {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

//		final Img<UnsignedByteType> rai = ImageJFunctions.wrapByte(IJ.openImage("/Users/pietzsch/workspace/data/DrosophilaWing.tif"));
		final Img<UnsignedByteType> rai = checkerboard(1000, 1000, 20, 20);
		final Bdv bdv = BdvFunctions.show(rai, "image", Bdv.options().is2D());

		final TransformEditor editor = new TransformEditor(bdv);

		RealTransform transformFromSource = new RealTransform() {

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
				editor.transform(source, target);
			}

			@Override
			public RealTransform copy() {
				return this;
			}
		};

		final UnsignedByteType background = new UnsignedByteType(100);
		final RandomAccessibleInterval<UnsignedByteType> transformed = Transform.createTransformedInterval(
				rai,
				Intervals.expand(rai, 1000),
				transformFromSource,
				background);

		BdvFunctions.show(transformed, "transformed", Bdv.options().addTo(bdv));

		final SynchronizedViewerState state = bdv.getBdvHandle().getViewerPanel().state();
		final ConverterSetups setups = bdv.getBdvHandle().getConverterSetups();
		final List<SourceAndConverter<?>> sources = state.getSources();
		final List<ConverterSetup> converterSetups = setups.getConverterSetups(sources);
		converterSetups.get(0).setColor(new ARGBType(0x00ff00));
		converterSetups.get(1).setColor(new ARGBType(0xff00ff));
	}


	static class TransformEditor {

		private final ViewerPanel viewer;

		public TransformEditor(Bdv bdv) {

			viewer = bdv.getBdvHandle().getViewerPanel();

			final Behaviours behaviours = new Behaviours(new InputTriggerConfig());
			behaviours.install(bdv.getBdvHandle().getTriggerbindings(), "paint");
			behaviours.behaviour(new PaintBehaviour(), "paint", "shift button1");

			final Overlay overlay = new Overlay();
			viewer.getDisplay().overlays().add(overlay);
		}

		private static final double maxSlope = 0.8;
		private static final double exph = Math.exp(-0.5) / maxSlope;
		private static final double minSigma = 2.0;

		public void transform(final RealLocalizable source, final RealPositionable target) {

			double x = source.getDoublePosition(0);
			double y = source.getDoublePosition(1);
			double transformedX = x;
			double transformedY = y;

			double h = Math.sqrt(stx * stx + sty * sty);
			double sigma = Math.max(minSigma, h * exph);
			double asqu = (x - sx1) * (x - sx1) + (y - sy1) * (y - sy1);
			double dt = Math.exp(asqu / (-2.0 * sigma * sigma));

			transformedX = x + dt * stx;
			transformedY = y + dt * sty;

			target.setPosition(transformedX, 0);
			target.setPosition(transformedY, 1);
		}

		public void transformTriangle(final RealLocalizable source, final RealPositionable target) {

			double x = source.getDoublePosition(0);
			double y = source.getDoublePosition(1);
			double transformedX = x;
			double transformedY = y;

			double h = Math.sqrt(stx * stx + sty * sty);
			double f = 1.01;
			double w = f * h;
			double a = Math.sqrt((x - sx1) * (x - sx1) + (y - sy1) * (y - sy1));
			if ( a < w )
			{
				transformedX = x + (1.0 - a / w) * stx;
				transformedY = y + (1.0 - a / w) * sty;
			}

			target.setPosition(transformedX, 0);
			target.setPosition(transformedY, 1);
		}

		private double stx;
		private double sty;
		private double sx0;
		private double sy0;
		private double sx1;
		private double sy1;

		private boolean dragging = false;
		private int x0;
		private int y0;
		private int x1;
		private int y1;

		private synchronized void initTranslate(final int x, final int y) {
			dragging = true;
			x0 = x;
			y0 = y;
			x1 = x;
			y1 = y;
		}

		private synchronized void dragTranslate(final int x, final int y) {
			x1 = x;
			y1 = y;

			SynchronizedViewerState state = viewer.state();
			AffineTransform3D tmp = state.getViewerTransform();
			AffineTransform2D sourceToViewer = new AffineTransform2D();
			sourceToViewer.set(
					tmp.get(0, 0), tmp.get(0, 1), tmp.get(0, 3),
					tmp.get(1, 0), tmp.get(1, 1), tmp.get(1, 3));
			double[] source = new double[2];
			sourceToViewer.applyInverse(source, new double[]{x0, y0});
			sx0 = source[0];
			sy0 = source[1];
			sourceToViewer.applyInverse(source, new double[]{x1, y1});
			sx1 = source[0];
			sy1 = source[1];
			stx = sx0 - sx1;
			sty = sy0 - sy1;

			viewer.requestRepaint();
		}

		private synchronized void endTranslate(final int x, final int y) {
			dragging = false;
			x1 = x;
			y1 = y;

			sx0 = sx1;
			sy0 = sy1;
			stx = 0;
			sty = 0;

			viewer.requestRepaint();
		}

		private synchronized void drawOverlay(final Graphics2D g) {
			if (dragging) {
				g.setColor(Color.BLUE);
				g.drawLine(x0, y0, x1, y1);
			}
		}

		private class PaintBehaviour implements DragBehaviour {

			@Override
			public void init(final int x, final int y) {
				initTranslate(x, y);
			}

			@Override
			public void drag(final int x, final int y) {
				dragTranslate(x, y);
			}

			@Override
			public void end(final int x, final int y) {
				endTranslate(x, y);
			}
		}

		private class Overlay implements OverlayRenderer {

			@Override
			public void drawOverlays(final Graphics g) {

				drawOverlay((Graphics2D)g);
			}

			@Override
			public void setCanvasSize(final int width, final int height) {

			}
		}
	}
}
