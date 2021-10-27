package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.viewer.ViewerPanel;
import java.awt.Insets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.imglib2.type.numeric.ARGBType;
import net.miginfocom.swing.MigLayout;
import org.janelia.saalfeldlab.hotknife.tobi.GaussShiftEditor.GaussShiftEditorListener;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

// transform baking in a CellLoader
public class ViewAlignmentPlayground12 {

	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader(n5Path);

		final String transformGroup = passGroup + "/" + "flat.Sec33.bot.face";
		final String faceGroup = "/flat/Sec33/bot/face";

		final SurfacePyramid<?, ?> n5surfacePyramid = new N5SurfacePyramid<>(n5, faceGroup);
		final PositionField n5positionField = new PositionField(n5, transformGroup);

		final int blockWidth = 64;
		final int minLevel = n5positionField.getLevel();
		final int maxLevel = n5surfacePyramid.getNumMipmapLevels() - 1;


		final List<PositionFieldPyramid> pfps = new ArrayList<>();
		pfps.add(PositionFieldPyramid.createFullPyramid(n5positionField, blockWidth, minLevel, maxLevel));

		final AtomicReference<SurfacePyramid<?, ?>> rsp = new AtomicReference<>();
		rsp.set(new RenderedSurfacePyramid<>(n5surfacePyramid, pfps.get(0), blockWidth));




		final DelegatingSourceAndConverter socWrapper = new DelegatingSourceAndConverter(
				n5surfacePyramid.getType(),
				n5surfacePyramid.getVolatileType(),
				"socWrapper");
		socWrapper.setDelegate(rsp.get().getSourceAndConverter());

		final BdvStackSource<?> source0 = BdvFunctions.show(socWrapper.get(), Bdv.options().is2D());
		source0.setDisplayRange(0, 255);
		source0.setDisplayRangeBounds(0, 255);
		source0.setColor(new ARGBType(0xff0000));

		final Bdv bdv = source0;
		final ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();





		final TriggerBehaviourBindings triggerbindings = bdv.getBdvHandle().getTriggerbindings();
		final InputTriggerConfig keyconf = new InputTriggerConfig();
		final GaussShiftEditor editor = new GaussShiftEditor(keyconf,
				viewer, triggerbindings);
		editor.install();
		editor.listeners().add(new GaussShiftEditorListener() {

			@Override
			public void activeChanged() {
				final GaussTransform transform = editor.isActive() ? editor.getModel() : null;
				if (transform != null) {
					final SurfacePyramid<?, ?> tsp = new TransformedSurfacePyramid<>(rsp.get(), transform);
					socWrapper.setDelegate(tsp.getSourceAndConverter());
				} else {
					socWrapper.setDelegate(rsp.get().getSourceAndConverter());
				}
			}

			@Override
			public void apply(final GaussTransform transform) {
				final PositionFieldPyramid pfp = Bake.bakePositionFieldPyramid(
						pfps.get(pfps.size() - 1), transform,
						blockWidth, minLevel, maxLevel);
				pfps.add(pfp);
				rsp.set(new RenderedSurfacePyramid<>(n5surfacePyramid, pfp, blockWidth));
				socWrapper.setDelegate(rsp.get().getSourceAndConverter());

				System.out.println("pfps.size() = " + pfps.size());
			}
		});


		final CardPanel cards = bdv.getBdvHandle().getCardPanel();
		cards.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false);
		cards.addCard("Face Transforms",
				new GaussShiftCard(editor).getPanel(),
				true, new Insets(0, 0, 0, 0));
	}



	public static class GaussShiftCard {

		private final JPanel panel;

		public GaussShiftCard(GaussShiftEditor editor) {
			panel = new JPanel(new MigLayout( "gap 0, ins 5 5 5 0, fill", "[right][grow]", "center" ));

			final BoundedValuePanel minSigmaSlider = new BoundedValuePanel(new BoundedValue(0, 1000, 100));
			minSigmaSlider.setBorder(null);
			final JLabel minSigmaLabel = new JLabel("min sigma");
			panel.add(minSigmaLabel, "aligny baseline");
			panel.add(minSigmaSlider, "growx, wrap");
			final MinSigmaEditor minSigmaEditor = new MinSigmaEditor(minSigmaLabel, minSigmaSlider, editor.getModel());

			final BoundedValuePanel maxSlopeSlider = new BoundedValuePanel(new BoundedValue(0, 1, 0.8));
			maxSlopeSlider.setBorder(null);
			final JLabel maxSlopeLabel = new JLabel("max slope");
			panel.add(maxSlopeLabel, "aligny baseline");
			panel.add(maxSlopeSlider, "growx, wrap");
			final MaxSlopeEditor maxSlopeEditor = new MaxSlopeEditor(maxSlopeLabel, maxSlopeSlider, editor.getModel());

			final ButtonPanel buttons = new ButtonPanel("Cancel", "Apply");
			panel.add(buttons, "sx2, gaptop 10px, wrap, bottom");

			buttons.onButton(0, () -> SwingUtilities.invokeLater(editor::cancel));
			buttons.onButton(1, () -> SwingUtilities.invokeLater(editor::apply));

			editor.listeners().add(() -> {
				final boolean active = editor.isActive();
				final GaussTransform transform = active ? editor.getModel() : null;
				minSigmaEditor.setTransform(transform);
				maxSlopeEditor.setTransform(transform);
				buttons.setEnabled(active);
			});
		}

		public JPanel getPanel() {
			return panel;
		}
	}
}
