package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.viewer.ViewerPanel;
import java.awt.Insets;
import java.io.IOException;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.imglib2.type.numeric.ARGBType;
import net.miginfocom.swing.MigLayout;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

// transform baking in a CellLoader
public class ViewAlignmentPlayground11czi {

	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader(n5Path);

		final String transformGroup1 = passGroup + "/" + "flat.Sec33.bot.face";
		final String faceGroup1 = "/flat/Sec33/bot/face";

		final SurfacePyramid<?, ?> pyramid1 = new N5SurfacePyramid<>(n5, faceGroup1);
		final PositionField pf0 = new PositionField(n5, transformGroup1);
		final int minLevel = pf0.getLevel();
		final int maxLevel = pyramid1.getNumMipmapLevels() - 1;
		final int blockWidth = 256;

		final PositionFieldPyramid pfp0 = PositionFieldPyramid.createFullPyramid(pf0, blockWidth, minLevel, maxLevel);
		final SurfacePyramid<?, ?> sp0 = new RenderedSurfacePyramid<>(pyramid1, pfp0, blockWidth);


		final String transformGroup2 = passGroup + "/" + "flat.Sec32.top.face";
		final String faceGroup2 = "/flat/Sec32/top/face";

		final SurfacePyramid<?, ?> pyramid2 = new N5SurfacePyramid<>(n5, faceGroup2);
		final PositionField pf1 = new PositionField(n5, transformGroup2);

		final PositionFieldPyramid pfp1 = PositionFieldPyramid.createFullPyramid(pf1, blockWidth, minLevel, maxLevel);
		final SurfacePyramid<?, ?> sp1 = new RenderedSurfacePyramid<>(pyramid2, pfp1, blockWidth);



		// set up transform to append to pfp0
		final double maxSlope=0.8;
		final double minSigma=100.0;
		final double sx0=3634.3391666666666;
		final double sy0=14456.360833333334;
		final double sx1=11067.172499999999;
		final double sy1=14679.345833333335;
		final GaussTransform transform1 = new GaussTransform(maxSlope, minSigma);
		transform1.setLine(sx0, sy0, sx1, sy1);

		final SurfacePyramid<?, ?> sp2 = new TransformedSurfacePyramid<>(sp1, transform1);


		final BdvStackSource<?> source0 = BdvFunctions.show(sp0.getSourceAndConverter(), Bdv.options().is2D());
		source0.setDisplayRange(0, 255);
		source0.setDisplayRangeBounds(0, 255);
		source0.setColor(new ARGBType(0xff0000));

		final BdvStackSource<?> source1 = BdvFunctions.show(sp2.getSourceAndConverter(), Bdv.options().addTo(source0));
		source1.setDisplayRange(0, 255);
		source1.setDisplayRangeBounds(0, 255);
		source1.setColor(new ARGBType(0x00ff00));







		final Bdv bdv = source0;
		final ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();
		final TriggerBehaviourBindings triggerbindings = bdv.getBdvHandle().getTriggerbindings();
		final InputTriggerConfig keyconf = new InputTriggerConfig();
		final GaussShiftEditor editor = new GaussShiftEditor(keyconf,
				viewer, triggerbindings, transform1);
		editor.install();

		JPanel panel = new JPanel(new MigLayout( "gap 0, ins 5 5 5 0, fill", "[right][grow]", "center" ));

		final BoundedValuePanel minSigmaSlider = new BoundedValuePanel(new BoundedValue(0, 1000, 100));
		minSigmaSlider.setBorder(null);
		final JLabel minSigmaLabel = new JLabel("min sigma");
		panel.add(minSigmaLabel, "aligny baseline");
		panel.add(minSigmaSlider, "growx, wrap");
		final MinSigmaEditor minSigmaEditor = new MinSigmaEditor(minSigmaLabel, minSigmaSlider, transform1);

		final BoundedValuePanel maxSlopeSlider = new BoundedValuePanel(new BoundedValue(0, 1, 0.8));
		maxSlopeSlider.setBorder(null);
		final JLabel maxSlopeLabel = new JLabel("max slope");
		panel.add(maxSlopeLabel, "aligny baseline");
		panel.add(maxSlopeSlider, "growx, wrap");
		final MaxSlopeEditor maxSlopeEditor = new MaxSlopeEditor(maxSlopeLabel, maxSlopeSlider, transform1);


		final ButtonPanel buttons = new ButtonPanel("Cancel", "Apply");
		panel.add(buttons, "sx2, gaptop 10px, wrap, bottom");

		buttons.onButton(1, () -> SwingUtilities.invokeLater(() -> {
			minSigmaEditor.setTransform(null);
			maxSlopeEditor.setTransform(null);
		}));

		buttons.onButton(0, () -> SwingUtilities.invokeLater(() -> {
			minSigmaEditor.setTransform(transform1);
			maxSlopeEditor.setTransform(transform1);
		}));

		final CardPanel cards = bdv.getBdvHandle().getCardPanel();
		cards.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false);
		cards.addCard("Face Transforms", panel, true, new Insets(0, 0, 0, 0));


	}
}
