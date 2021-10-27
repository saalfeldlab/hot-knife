package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.viewer.ViewerPanel;
import java.awt.Insets;
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
		final double sx0 = 611.9999999999999;
		final double sy0 = 473.66666666666663;
		final double sx1 = 780.3333333333333;
		final double sy1 = 558.6666666666665;
		final GaussTransform transform = new GaussTransform(maxSlope, minSigma);
		transform.setLine(sx0, sy0, sx1, sy1);

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
			minSigmaEditor.setTransform(null);
			maxSlopeEditor.setTransform(null);
		}));

		buttons.onButton(0, () -> SwingUtilities.invokeLater(() -> {
			minSigmaEditor.setTransform(transform);
			maxSlopeEditor.setTransform(transform);
		}));

		final CardPanel cards = bdv.getBdvHandle().getCardPanel();
		cards.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false);
		cards.addCard("Face Transforms", panel, true, new Insets(0, 0, 0, 0));
	}

}
