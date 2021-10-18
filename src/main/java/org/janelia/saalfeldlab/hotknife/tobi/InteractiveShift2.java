package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.viewer.ViewerPanel;
import java.awt.Insets;
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



		JPanel panel = new JPanel(new MigLayout( "ins 0, fillx, filly", "[][grow]", "center" ));
		final BoundedValuePanel sigmaSlider = new BoundedValuePanel(new BoundedValue(10, 200, 100));
		panel.add(new JLabel("min sigma"), "aligny baseline");
		panel.add(sigmaSlider, "growx, wrap");
		panel.add(new JButton("bla"), "growx, wrap");

		new MinSigmaEditor(sigmaSlider, transform);

		final CardPanel cards = bdv.getBdvHandle().getCardPanel();
		cards.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false);
		cards.addCard("Face Transforms", panel, true, new Insets(0, 0, 0, 0));
	}


	static class MinSigmaEditor {

		private final BoundedValuePanel valuePanel;
		private GaussTransform transform;

		public MinSigmaEditor(
				final BoundedValuePanel valuePanel,
				final GaussTransform transform) {
			this.valuePanel = valuePanel;
			this.transform = transform;
			valuePanel.changeListeners().add(this::updateTransform);
			transform.changeListeners().add(this::updateValuePanel);
			updateValuePanel();
		}

		private boolean blockUpdates = false;

		private synchronized void updateTransform() {
			if (blockUpdates || transform == null)
				return;

			final BoundedValue value = valuePanel.getValue();
			transform.setMinSigma(value.getValue());

			updateValuePanel();
		}

		private synchronized void updateValuePanel() {
			if (transform == null) {
				SwingUtilities.invokeLater(() -> valuePanel.setEnabled(false));
			} else {
				SwingUtilities.invokeLater(() -> {
					synchronized (MinSigmaEditor.this) {
						blockUpdates = true;
						valuePanel.setEnabled(true);
						valuePanel.setValue(valuePanel.getValue().withValue(transform.getMinSigma()));
						blockUpdates = false;
					}
				});
			}
		}
	}
}
