package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.Bounds;
import bdv.viewer.ViewerPanel;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
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
import org.scijava.ui.behaviour.io.InputTriggerConfig;
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
		}));

		buttons.onButton(0, () -> SwingUtilities.invokeLater(() -> {
			editor.setModel(transform);
			minSigmaEditor.setTransform(transform);
			maxSlopeEditor.setTransform(transform);
		}));

		final CardPanel cards = bdv.getBdvHandle().getCardPanel();
		cards.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false);
		cards.addCard("Face Transforms", panel, true, new Insets(0, 0, 0, 0));
	}

	static class MaxSlopeEditor {

		private final JLabel label;
		private final BoundedValuePanel valuePanel;
		private final GaussTransform.ChangeListener updateValuePanel;
		private GaussTransform transform;

		public MaxSlopeEditor(
				final JLabel label,
				final BoundedValuePanel valuePanel,
				final GaussTransform transform) {
			this.label = label;
			this.valuePanel = valuePanel;
			this.transform = transform;
			valuePanel.changeListeners().add(this::updateTransform);
			updateValuePanel = this::updateValuePanel;
			setTransform(transform);
		}

		public synchronized void setTransform(final GaussTransform transform) {
			if (this.transform != null)
				this.transform.changeListeners().remove(updateValuePanel);
			this.transform = transform;
			if (this.transform != null)
				this.transform.changeListeners().add(updateValuePanel);
			updateValuePanel();
		}

		private boolean blockUpdates = false;

		private synchronized void updateTransform() {
			if (blockUpdates || transform == null)
				return;

			final BoundedValue value = valuePanel.getValue();
			transform.setMaxSlope(value.getValue());

			updateValuePanel();
		}

		private synchronized void updateValuePanel() {
			if (transform == null) {
				SwingUtilities.invokeLater(() -> {
					valuePanel.setEnabled(false);
					label.setEnabled(false);
				});
			} else {
				SwingUtilities.invokeLater(() -> {
					synchronized (MaxSlopeEditor.this) {
						blockUpdates = true;
						valuePanel.setEnabled(true);
						label.setEnabled(true);
						valuePanel.setValue(valuePanel.getValue().withValue(transform.getMaxSlope()));
						blockUpdates = false;
					}
				});
			}
		}
	}

	static class MinSigmaEditor {

		private final JLabel label;
		private final BoundedValuePanel valuePanel;
		private final GaussTransform.ChangeListener updateValuePanel;
		private GaussTransform transform;

		public MinSigmaEditor(
				final JLabel label,
				final BoundedValuePanel valuePanel,
				final GaussTransform transform) {
			this.label = label;
			this.valuePanel = valuePanel;
			valuePanel.changeListeners().add(this::updateTransform);
			updateValuePanel = this::updateValuePanel;
			setTransform(transform);
		}

		public synchronized void setTransform(final GaussTransform transform) {
			if (this.transform != null)
				this.transform.changeListeners().remove(updateValuePanel);
			this.transform = transform;
			if (this.transform != null)
				this.transform.changeListeners().add(updateValuePanel);
			updateValuePanel();
		}

		private boolean blockUpdates = false;

		private synchronized void updateTransform() {
			if (blockUpdates || transform == null)
				return;

			final BoundedValue value = valuePanel.getValue();
			transform.setMinSigma(value.getValue());

			final JPopupMenu menu = new JPopupMenu();
			menu.add(runnableItem("set bounds ...", valuePanel::setBoundsDialog));
			menu.add(setBoundsItem("set bounds 0..10", 0, 10));
			menu.add(setBoundsItem("set bounds 0..100", 0, 100));
			menu.add(setBoundsItem("set bounds 0..1000", 0, 1000));
			menu.add(setBoundsItem("set bounds 0..10000", 0, 10000));
			valuePanel.setPopup(() -> menu);

			updateValuePanel();
		}

		private JMenuItem setBoundsItem(final String text, final double min, final double max) {
			final JMenuItem item = new JMenuItem(text);
			item.addActionListener(e -> setBounds(new Bounds(min, max)));
			return item;
		}

		private JMenuItem runnableItem(final String text, final Runnable action) {
			final JMenuItem item = new JMenuItem(text);
			item.addActionListener(e -> action.run());
			return item;
		}

		private synchronized void setBounds(final Bounds bounds) {
			if (transform == null)
				return;

			SwingUtilities.invokeLater(() -> {
				synchronized (MinSigmaEditor.this) {
					valuePanel.setValue(valuePanel.getValue()
							.withMinBound(bounds.getMinBound())
							.withMaxBound(bounds.getMaxBound()));
				}
			});
		}

		private synchronized void updateValuePanel() {
			if (transform == null) {
				SwingUtilities.invokeLater(() -> {
					valuePanel.setEnabled(false);
					label.setEnabled(false);
				});
			} else {
				SwingUtilities.invokeLater(() -> {
					synchronized (MinSigmaEditor.this) {
						blockUpdates = true;
						valuePanel.setEnabled(true);
						label.setEnabled(true);
						valuePanel.setValue(valuePanel.getValue().withValue(transform.getMinSigma()));
						blockUpdates = false;
					}
				});
			}
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
