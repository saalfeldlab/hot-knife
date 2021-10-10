package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.viewer.OverlayRenderer;
import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.ViewerPanel;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.io.IOException;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.scijava.ui.behaviour.DragBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Behaviours;

public class ViewAlignmentPlayground4 {

	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader(n5Path);

		final String transformGroup = passGroup + "/" + "flat.Sec33.bot.face";
		final String faceGroup = "/flat/Sec33/bot/face";

		final SurfacePyramid<?, ?> pyramid = new SurfacePyramid<>(n5, faceGroup);
//		BdvFunctions.show(pyramid.getSourceAndConverter(), Bdv.options().is2D());

		final PositionField positionField = new PositionField(n5, transformGroup);

		final GaussTransform movingTransform = new GaussTransform();
		final TransformedSurfacePyramid<?, ?> tpyramid = new TransformedSurfacePyramid<>(pyramid, positionField, movingTransform);
		final BdvStackSource<?> source = BdvFunctions.show(tpyramid.getSourceAndConverter(), Bdv.options().is2D());
		source.setDisplayRange(0, 255);
		source.setDisplayRangeBounds(0, 255);

		final GaussTransformEditor editor = new GaussTransformEditor(movingTransform, source);
	}

	static class GaussTransformEditor {

		private final GaussTransform transform;
		private final ViewerPanel viewer;

		public GaussTransformEditor(final GaussTransform movingTransform, final Bdv bdv) {
			transform = movingTransform;
			viewer = bdv.getBdvHandle().getViewerPanel();

			final Behaviours behaviours = new Behaviours(new InputTriggerConfig());
			behaviours.install(bdv.getBdvHandle().getTriggerbindings(), "paint");
			behaviours.behaviour(new PaintBehaviour(), "paint", "shift button1");

			final Overlay overlay = new Overlay();
			viewer.getDisplay().overlays().add(overlay);
		}

		private boolean dragging = false;
		private int x0;
		private int y0;
		private int x1;
		private int y1;

		private synchronized void initTranslate(final int x, final int y) {
			x0 = x;
			y0 = y;
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
			final double sx0 = source[0];
			final double sy0 = source[1];
			sourceToViewer.applyInverse(source, new double[]{x1, y1});
			final double sx1 = source[0];
			final double sy1 = source[1];

			transform.setLine(sx0, sy0, sx1, sy1);
			transform.setActive(true);

			dragging = true;
			viewer.requestRepaint();
		}

		private synchronized void endTranslate(final int x, final int y) {
			System.out.println("transform = " + transform);
			transform.setActive(false);

			dragging = false;
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
