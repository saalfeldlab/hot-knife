package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.viewer.OverlayRenderer;
import bdv.viewer.TransformListener;
import bdv.viewer.ViewerPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseMotionListener;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import org.scijava.ui.behaviour.Behaviour;
import org.scijava.ui.behaviour.BehaviourMap;
import org.scijava.ui.behaviour.DragBehaviour;
import org.scijava.ui.behaviour.InputTrigger;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Behaviours;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

/**
 * Installs an interactive {@link GaussTransform} editing tool on BDV.
 * <p>
 * The feature consists of an overlay added to the BDV and editing behaviours
 * where the user can edit the transform directly by interacting with the overlay.
 *
 * @author Tobias Pietzsch
 */
public class GaussShiftEditor {

	public static final String DRAG_GAUSS_SHIFT_CORNER = "drag gauss-shift corner";
	public static final String[] DRAG_GAUSS_SHIFT_CORNER_KEYS = new String[] {"button1", "shift button1"};

	public static final String GAUSS_SHIFT_MAP = "gauss-shift";
	public static final String BLOCKING_MAP = "gauss-shift-blocking";


	private ViewerPanel viewer;
	private TriggerBehaviourBindings triggerbindings;
	private GaussTransform model;

	private final Behaviours behaviours;
	private final BehaviourMap blockMap;
	private final GaussShiftOverlay overlay;

	public GaussShiftEditor(
			final InputTriggerConfig keyconf,
			final ViewerPanel viewer,
			final TriggerBehaviourBindings triggerbindings,
			final GaussTransform model // TODO: it should be possible to change this later (including setting it to null).
	) {
		this.viewer = viewer;
		this.triggerbindings = triggerbindings;
		this.model = model;

		overlay = new GaussShiftOverlay(model);

		behaviours = new Behaviours(keyconf);
		behaviours.behaviour(new DragCornerBehaviour(overlay, model), DRAG_GAUSS_SHIFT_CORNER, DRAG_GAUSS_SHIFT_CORNER_KEYS);

		/*
		 * Create BehaviourMap to block behaviours interfering with
		 * DragBoxCornerBehaviour. The block map is only active while a corner
		 * is highlighted.
		 */
		blockMap = new BehaviourMap();
	}

	public void install() {
		viewer.getDisplay().overlays().add(overlay);
		viewer.getDisplay().addHandler(overlay.getCornerHighlighter());
		viewer.renderTransformListeners().add(overlay);

		model.changeListeners().add(() -> viewer.requestRepaint());

		refreshBlockMap();
		updateEditability();
	}

	public void uninstall() {
		viewer.getDisplay().overlays().remove(overlay);
		viewer.removeTransformListener(overlay);
		viewer.getDisplay().removeHandler(overlay.getCornerHighlighter());

		triggerbindings.removeInputTriggerMap(GAUSS_SHIFT_MAP);
		triggerbindings.removeBehaviourMap(GAUSS_SHIFT_MAP);

		unblock();
	}

	private void updateEditability() {
		final boolean editable = true; // TODO: simplify, if it's not needed
		if (editable) {
			overlay.setHighlightedCornerListener(this::highlightedCornerChanged);
			behaviours.install(triggerbindings, GAUSS_SHIFT_MAP);
			highlightedCornerChanged();
		} else {
			overlay.setHighlightedCornerListener(null);
			triggerbindings.removeInputTriggerMap(GAUSS_SHIFT_MAP);
			triggerbindings.removeBehaviourMap(GAUSS_SHIFT_MAP);
			unblock();
		}
	}

	private void block() {
		triggerbindings.addBehaviourMap(BLOCKING_MAP, blockMap);
	}

	private void unblock() {
		triggerbindings.removeBehaviourMap(BLOCKING_MAP);
	}

	private void highlightedCornerChanged() {
		final int index = overlay.getHighlightedCornerIndex();
		if (index < 0)
			unblock();
		else
			block();
	}

	private void refreshBlockMap() {
		triggerbindings.removeBehaviourMap(BLOCKING_MAP);

		final Set<InputTrigger> moveCornerTriggers = new HashSet<>();
		for (final String s : DRAG_GAUSS_SHIFT_CORNER_KEYS)
			moveCornerTriggers.add(InputTrigger.getFromString(s));

		final Map<InputTrigger, Set<String>> bindings = triggerbindings.getConcatenatedInputTriggerMap().getAllBindings();
		final Set<String> behavioursToBlock = new HashSet<>();
		for (final InputTrigger t : moveCornerTriggers) {
			final Set<String> behaviours = bindings.getOrDefault(t, Collections.emptySet());
			behavioursToBlock.addAll(behaviours);
		}

		blockMap.clear();
		final Behaviour block = new Behaviour() {};
		for (final String key : behavioursToBlock)
			blockMap.put(key, block);
	}


	static final class DragCornerBehaviour implements DragBehaviour {

		private final GaussShiftOverlay overlay;
		private final GaussTransform model;

		private boolean moving = false;
		private int cornerId;

		public DragCornerBehaviour(final GaussShiftOverlay overlay, final GaussTransform model) {
			this.overlay = overlay;
			this.model = model;
		}

		@Override
		public void init(final int x, final int y) {
			cornerId = overlay.getHighlightedCornerIndex();
			if (cornerId >= 0)
				moving = true;
		}

		private final AffineTransform3D tmp = new AffineTransform3D();
		private final AffineTransform2D sourceToViewer = new AffineTransform2D();
		private final double[] source = new double[2];

		@Override
		public void drag(final int x, final int y) {
			if (!moving)
				return;

			overlay.getViewerTransform(tmp);
			sourceToViewer.set(
					tmp.get(0, 0), tmp.get(0, 1), tmp.get(0, 3),
					tmp.get(1, 0), tmp.get(1, 1), tmp.get(1, 3));
			sourceToViewer.applyInverse(source, new double[] {x, y});
			final double sx1 = source[0];
			final double sy1 = source[1];
			if (cornerId == 0)
				model.setLineStart(source[0], source[1]);
			else
				model.setLineEnd(source[0], source[1]);
		}

		@Override
		public void end(final int x, final int y) {
			moving = false;
		}
	}


	static final class GaussShiftOverlay implements OverlayRenderer, TransformListener<AffineTransform3D> {

		private static final double DISTANCE_TOLERANCE = 20.;
		private static final double SELECTED_HANDLE_RADIUS = DISTANCE_TOLERANCE / 2.;
		private static final double HANDLE_RADIUS = DISTANCE_TOLERANCE / 5.;

		public interface HighlightedCornerListener {

			void highlightedCornerChanged();
		}

		private final GaussTransform transform;
		private final AffineTransform3D viewerTransform;
		private final CornerHighlighter cornerHighlighter;

		private HighlightedCornerListener highlightedCornerListener = null;
		private int cornerId = -1;

		public GaussShiftOverlay(final GaussTransform transform) {
			this.transform = transform;
			viewerTransform = new AffineTransform3D();
			cornerHighlighter = new CornerHighlighter(DISTANCE_TOLERANCE);
		}


		@Override
		public void drawOverlays(final Graphics g) {
			final Graphics2D graphics = (Graphics2D) g;
			final Color color = Color.green;
			final Stroke solid = new BasicStroke();
			final Stroke dashed = new BasicStroke( 1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[] { 5f, 5f }, 0f );

			final int id = getHighlightedCornerIndex();

			final double[][] corners;
			final double sigma;
			synchronized (viewerTransform) {
				corners = transform.getCornersInViewerCoords(viewerTransform);
				sigma = transform.getSigma() * viewerTransform.get(0,0);
			}

			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(color);
			graphics.setStroke(solid);
			Line2D line = new Line2D.Double(corners[0][0], corners[0][1], corners[1][0], corners[1][1]);
			graphics.draw(line);

			for (int i = 0; i < 2; ++i) {
				final double[] p = corners[i];
				final double radius = (i == id) ? SELECTED_HANDLE_RADIUS : HANDLE_RADIUS;
				final Ellipse2D cornerHandle = new Ellipse2D.Double(
						p[0] - radius,
						p[1] - radius,
						2 * radius, 2 * radius);

				graphics.setColor(color);
				graphics.fill(cornerHandle);
//				graphics.setColor(cornerColor.darker().darker());
//				graphics.draw(cornerHandle);

				if ( i == 1 ) {
					final Ellipse2D sigmaEllipse = new Ellipse2D.Double(
							p[0] - sigma,
							p[1] - sigma,
							2 * sigma, 2 * sigma);
					graphics.setColor(color);
					graphics.setStroke(dashed);
					graphics.draw(sigmaEllipse);
				}
			}
		}

		@Override
		public void setCanvasSize(final int i, final int i1) {

		}

		@Override
		public void transformChanged(final AffineTransform3D t) {
			synchronized (viewerTransform) {
				viewerTransform.set(t);
			}
		}

		public void getViewerTransform(final AffineTransform3D t) {
			synchronized (viewerTransform) {
				t.set(viewerTransform);
			}
		}


		/**
		 * Get the index of the highlighted corner (if any).
		 *
		 * @return corner index or {@code -1} if no corner is highlighted
		 */
		public int getHighlightedCornerIndex() {
			return cornerId;
		}

		/**
		 * Returns a {@code MouseMotionListener} that can be installed into a bdv
		 * (see {@code ViewerPanel.getDisplay().addHandler(...)}). If installed, it
		 * will notify a {@code HighlightedCornerListener} (see
		 * {@link #setHighlightedCornerListener(HighlightedCornerListener)}) when
		 * the mouse is over a corner of the box (with some tolerance)/
		 *
		 * @return a {@code MouseMotionListener} implementing mouse-over for box
		 * corners
		 */
		public MouseMotionListener getCornerHighlighter() {
			return cornerHighlighter;
		}

		public void setHighlightedCornerListener(final HighlightedCornerListener highlightedCornerListener) {
			this.highlightedCornerListener = highlightedCornerListener;
		}

		/**
		 * Set the index of the highlighted corner.
		 *
		 * @param id
		 * 		corner index, {@code -1} means that no corner is highlighted.
		 */
		private void setHighlightedCorner(final int id) {
			final int oldId = cornerId;
			cornerId = id;
			if (cornerId != oldId && highlightedCornerListener != null)
				highlightedCornerListener.highlightedCornerChanged();
		}


		private class CornerHighlighter extends MouseMotionAdapter {

			private final double squTolerance;

			CornerHighlighter(final double tolerance) {
				squTolerance = tolerance * tolerance;
			}

			@Override
			public void mouseMoved(final MouseEvent e) {
				final int x = e.getX();
				final int y = e.getY();
				final double[][] corners = transform.getCornersInViewerCoords(viewerTransform);
				for (int i = 0; i < 2; i++) {
					final double dx = x - corners[i][0];
					final double dy = y - corners[i][1];
					final double dr2 = dx * dx + dy * dy;
					if (dr2 < squTolerance) {
						setHighlightedCorner(i);
						return;
					}
				}
				setHighlightedCorner(-1);
			}
		}
	}
}