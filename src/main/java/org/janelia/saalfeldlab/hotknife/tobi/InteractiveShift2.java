package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.viewer.ViewerPanel;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.miginfocom.swing.MigLayout;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.scijava.ui.behaviour.DragBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Behaviours;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

public class InteractiveShift2 {

	static Img<UnsignedByteType> checkerboard(final int w, final int h, final int cw, final int ch) {
		Img<UnsignedByteType> img = ArrayImgs.unsignedBytes(w, h);
		Cursor<UnsignedByteType> c = img.localizingCursor();
		while (c.hasNext()) {
			c.fwd();
			final int x = c.getIntPosition(0);
			final int y = c.getIntPosition(1);
			final int i = ((x / cw) + (y / ch)) % 2;
			c.get().set(i == 0 ? 128 : 64);
		}
		return img;
	}

	public static void main(String[] args) {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

//		final Img<UnsignedByteType> rai = ImageJFunctions.wrapByte(IJ.openImage("/Users/pietzsch/workspace/data/DrosophilaWing.tif"));
		final Img<UnsignedByteType> rai = checkerboard(1000, 1000, 20, 20);
		final BdvSource fixedSource = BdvFunctions.show(rai, "image", Bdv.options().is2D());
		final Bdv bdv = fixedSource;

		// set up transform
		final double maxSlope = 0.8;
		final double minSigma = 100.0;
		final boolean active = true;
		final double sx0 = 611.9999999999999;
		final double sy0 = 473.66666666666663;
		final double sx1 = 780.3333333333333;
		final double sy1 = 558.6666666666665;
		final GaussTransform transform = new GaussTransform(maxSlope, minSigma);
		transform.setLine(sx0, sy0, sx1, sy1);
		transform.setActive(active);

		final UnsignedByteType background = new UnsignedByteType(100);
		final RandomAccessibleInterval<UnsignedByteType> transformed = Transform.createTransformedInterval(
				rai,
				Intervals.expand(rai, 1000),
				transform,
				background);

		final BdvSource movingSource = BdvFunctions.show(transformed, "transformed", Bdv.options().addTo(bdv));
		fixedSource.setColor(new ARGBType(0x8888ff));
		movingSource.setColor(new ARGBType(0xff8800));

		final ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();
		final TriggerBehaviourBindings triggerbindings = bdv.getBdvHandle().getTriggerbindings();
		final InputTriggerConfig keyconf = new InputTriggerConfig();
		final GaussShiftEditor editor = new GaussShiftEditor(keyconf,
				viewer, triggerbindings, transform);
		editor.install();

		JPanel panel = new JPanel(new MigLayout( "gap 0, ins 5 5 5 0, fill", "[right][grow]", "center" ));

		final BoundedValuePanel minSigmaSlider = new BoundedValuePanel(new BoundedValue(0, 1000, 100));
		minSigmaSlider.setBorder(null);
		final JLabel minSigmaLabel = new JLabel("min sigma");
		panel.add(minSigmaLabel, "aligny baseline");
		panel.add(minSigmaSlider, "growx, wrap");
		final MinSigmaEditor minSigmaEditor = new MinSigmaEditor(minSigmaLabel, minSigmaSlider, transform);

		final BoundedValuePanel maxSlopeSlider = new BoundedValuePanel(new BoundedValue(0, 1, 0.8));
		maxSlopeSlider.setBorder(null);
		final JLabel maxSlopeLabel = new JLabel("max slope");
		panel.add(maxSlopeLabel, "aligny baseline");
		panel.add(maxSlopeSlider, "growx, wrap");
		final MaxSlopeEditor maxSlopeEditor = new MaxSlopeEditor(maxSlopeLabel, maxSlopeSlider, transform);


		final ButtonPanel buttons = new ButtonPanel("Cancel", "Apply");
		panel.add(buttons, "sx2, gaptop 10px, wrap, bottom");

		buttons.onButton(1, () -> SwingUtilities.invokeLater(() -> {
			editor.setModel(null);
			minSigmaEditor.setTransform(null);
			maxSlopeEditor.setTransform(null);
			transform.setActive(false);
		}));

		buttons.onButton(0, () -> SwingUtilities.invokeLater(() -> {
			editor.setModel(transform);
			minSigmaEditor.setTransform(transform);
			maxSlopeEditor.setTransform(transform);
			transform.setActive(true);
		}));

		final CardPanel cards = bdv.getBdvHandle().getCardPanel();
		cards.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false);
		cards.addCard("Face Transforms", panel, true, new Insets(0, 0, 0, 0));


		new GaussTransformInitializer(keyconf, viewer, triggerbindings, transform, model -> {
			editor.setModel(model);
			minSigmaEditor.setTransform(model);
			maxSlopeEditor.setTransform(model);
			model.setActive(true);
		}).install();
	}








	static class GaussTransformInitializer implements DragBehaviour {


		public static final String DRAG_INIT_GAUSS_SHIFT = "drag init gauss-shift";
		public static final String[] DRAG_INIT_GAUSS_SHIFT_KEYS = new String[] {"shift button1"};

		public static final String INIT_GAUSS_SHIFT_MAP = "init-gauss-shift";

		private final ViewerPanel viewer;
		private final TriggerBehaviourBindings triggerbindings;
		private final ViewerCoords viewerCoords;
		private final Behaviours behaviours;


		// TEMP:
		private GaussTransform TEMPtransform;
		private Consumer<GaussTransform> transformSetter;

		private GaussTransform model;

		public GaussTransformInitializer(
				final InputTriggerConfig keyconf,
				final ViewerPanel viewer,
				final TriggerBehaviourBindings triggerbindings,
				final GaussTransform TEMPtransform,
				final Consumer<GaussTransform> transformSetter) {

			this.viewer = viewer;
			this.triggerbindings = triggerbindings;
			this.TEMPtransform = TEMPtransform;
			this.transformSetter = transformSetter;

			viewerCoords = new ViewerCoords();

			behaviours = new Behaviours(keyconf);
			behaviours.behaviour(this, DRAG_INIT_GAUSS_SHIFT, DRAG_INIT_GAUSS_SHIFT_KEYS);
		}

		private int x0;
		private int y0;

		public void install() {
			viewer.renderTransformListeners().add(viewerCoords);
			behaviours.install(triggerbindings, INIT_GAUSS_SHIFT_MAP);
		}

		public void uninstall() {
			viewer.removeTransformListener(viewerCoords);
			triggerbindings.removeInputTriggerMap(INIT_GAUSS_SHIFT_MAP);
			triggerbindings.removeBehaviourMap(INIT_GAUSS_SHIFT_MAP);
		}

		@Override
		public void init(final int x, final int y) {
			x0 = x;
			y0 = y;
		}

		@Override
		public void drag(final int x, final int y) {
			if (model == null) {
				model = TEMPtransform; // new GaussTransform();
				viewerCoords.applyTransformed(model::setLineStart, x0, y0);
				viewerCoords.applyTransformed(model::setLineEnd, x, y);
				model.setActive(true);
				transformSetter.accept(model);
			} else {
				viewerCoords.applyTransformed(model::setLineEnd, x, y);
			}
			viewer.requestRepaint(); // TODO: necessary?
		}

		@Override
		public void end(final int x, final int y) {
			model = null;
			viewer.requestRepaint(); // TODO: necessary?
		}
	}


	/**
	 * A panel containing buttons, and callback lists for each of them.
	 */
	public static class ButtonPanel extends JPanel {

		private final List<List<Runnable>> runOnButton;

		public ButtonPanel(final String... buttonLabels) {
			if (buttonLabels.length == 0)
				throw new IllegalArgumentException();

			final int numButtons = buttonLabels.length;
			runOnButton = new ArrayList<>(numButtons);

			setLayout(new BoxLayout(this, BoxLayout.LINE_AXIS));
			add(Box.createHorizontalGlue());

			for (int i = 0; i < numButtons; i++) {
				final List<Runnable> runnables = new ArrayList<>();
				runOnButton.add(runnables);
				final JButton button = new JButton(buttonLabels[i]);
				button.addActionListener(e -> runnables.forEach(Runnable::run));
				add(button);
			}
		}

		public synchronized void onButton(final int index, final Runnable runnable) {
			runOnButton.get(index).add(runnable);
		}
	}
}
