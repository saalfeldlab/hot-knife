package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.viewer.ViewerPanel;
import java.awt.Insets;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.imglib2.type.numeric.ARGBType;
import net.miginfocom.swing.MigLayout;
import org.janelia.saalfeldlab.hotknife.AbstractOptions;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.scijava.plugin.Plugin;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Actions;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

// transform baking in a CellLoader
public class ViewAlignmentPlayground13 {



	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /flyem/render/n5/Z0720_07m_BR")
		private String n5Path = null;

		@Option(name = "--n5Group", required = true, usage = "N5 group, e.g. /surface_align/pass02")
		private String n5Group = null;

		@Option(name = "--transform1", required = true, usage = "first transform flat.Sec26.top.face")
		private String transform1 = null;

		@Option(name = "--transform2", required = true, usage = "second transform flat.Sec27.bot.face")
		private String transform2 = null;

		@Option(name = "--n5OutputPath", required = true, usage = "N5 output path, e.g. /flyem/render/n5/Z0720_07m_BR")
		private String n5OutputPath = null;

		@Option(name = "--n5OutputGroup", required = true, usage = "N5 output group, e.g. /surface_align/pass02_edit")
		private String n5OutputGroup = null;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				parsedSuccessfully = true;
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		public boolean parsedSuccessfully() {
			return parsedSuccessfully;
		}

		public String getN5Path() {
			return n5Path;
		}

		public String getN5Group() {
			return n5Group;
		}

		public String getTransform1() {
			return transform1;
		}

		public String getTransform2() {
			return transform2;
		}

		public String getN5OutputPath() {
			return n5OutputPath;
		}

		public String getN5OutputGroup() {
			return n5OutputGroup;
		}
	}




	public static final String UNDO = "undo";
	public static final String REDO = "redo";

	static final String[] UNDO_KEYS = new String[] { "meta Z", "ctrl Z" };
	static final String[] REDO_KEYS = new String[] { "meta shift Z", "ctrl shift Z" };





	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final Options options = new Options(args);
		if (!options.parsedSuccessfully())
			return;

		final String n5Path = options.getN5Path();
		final N5Reader n5 = new N5FSReader(n5Path);

		final String n5Group = options.getN5Group();
		final List<String> datasetNames = Arrays.asList(n5.getAttribute(n5Group, "datasets", String[].class));
		final List<String> transformDatasetNames = Arrays.asList(n5.getAttribute(n5Group, "transforms", String[].class));
//		final double[] boundsMin = n5.getAttribute(n5Group, "boundsMin", double[].class);
//		final double[] boundsMax = n5.getAttribute(n5Group, "boundsMax", double[].class);

		final String transform1 = options.getTransform1();
		final int i1 = transformDatasetNames.indexOf(transform1);
		final String dataset1 = datasetNames.get(i1);

		final String transform2 = options.getTransform2();
		final int i2 = transformDatasetNames.indexOf(transform2);
		final String dataset2 = datasetNames.get(i2);



		final int blockWidth = 64;
		final int[] outputBlockSize = {400, 400, 2};

		// open pyramids
		final TransformedSurfaceStack<?, ?> stack1 = new TransformedSurfaceStack<>(
				n5, dataset1, n5Group + "/" + transform1, blockWidth, transform1);
		final TransformedSurfaceStack<?, ?> stack2 = new TransformedSurfaceStack<>(
				n5, dataset2, n5Group + "/" + transform2, blockWidth, transform2);


		final BdvStackSource<?> source1 = BdvFunctions.show(stack1.getSourceAndConverter(), Bdv.options().is2D());
		source1.setDisplayRange(0, 255);
		source1.setDisplayRangeBounds(0, 350);
		source1.setColor(new ARGBType(0xff7f7f));

		final BdvStackSource<?> source2 = BdvFunctions.show(stack2.getSourceAndConverter(), Bdv.options().addTo(source1));
		source2.setDisplayRange(0, 255);
		source2.setDisplayRangeBounds(0, 350);
		source2.setColor(new ARGBType(0x7fff7f));

		final Bdv bdv = source1;
		final ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();
		final TriggerBehaviourBindings triggerbindings = bdv.getBdvHandle().getTriggerbindings();
		final InputTriggerConfig keyconf = new InputTriggerConfig();


		final BdvStackSource<?> source3 = BdvFunctions.show(stack1.createSocWrapper(), Bdv.options().addTo(source1));
		source3.setDisplayRange(0, 255);
		source3.setDisplayRangeBounds(0, 255);
		source3.setActive(false);

		final BdvStackSource<?> source4 = BdvFunctions.show(stack2.createSocWrapper(), Bdv.options().addTo(source1));
		source4.setDisplayRange(0, 255);
		source4.setDisplayRangeBounds(0, 255);
		source4.setActive(false);







		final GaussShiftEditor editor = new GaussShiftEditor(keyconf,
				viewer, triggerbindings);
		editor.install();
		editor.listeners().add(new GaussShiftEditor.GaussShiftEditorListener() {

			@Override
			public void activeChanged() {
				final GaussTransform transform = editor.isActive()
						? editor.getModel()
						: null;
				stack1.setIncrementalTransform(
						transform);
				viewer.requestRepaint();
			}

			@Override
			public void apply(final GaussTransform transform) {
				stack1.bakeIncrementalTransform(transform);
				viewer.requestRepaint();
			}
		});


		final CardPanel cards = bdv.getBdvHandle().getCardPanel();
		cards.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false);
		cards.addCard("Face Transforms",
				new GaussShiftCard(editor).getPanel(),
				true, new Insets(0, 0, 0, 0));




		final JPanel panel = new JPanel(new MigLayout( "gap 0, ins 5 5 5 0, fill", "[right][grow]", "center" ));
		final ButtonPanel buttons = new ButtonPanel("Save " + transform1);
		panel.add(buttons, "sx2, gaptop 10px, wrap, bottom");

		final String n5OutputPath = options.getN5OutputPath();
		final String n5OutputGroup = options.getN5OutputGroup();
		buttons.onButton(0, () -> {
			buttons.setEnabled(false);
			SwingUtilities.invokeLater(() -> {
				try {
					final PositionFieldPyramid pfp = stack1.getPositionFieldPyramid();
					final PositionField positionField = pfp.getPositionField(pfp.getMinLevel());
					positionField.write(new N5FSWriter(n5OutputPath), n5OutputGroup + "/" + transform1, outputBlockSize);
				} catch (IOException e) {
					e.printStackTrace();
				}
				buttons.setEnabled(true);
			});
		});
		cards.addCard("Save Transforms",
				panel,
				true, new Insets(0, 0, 0, 0));


		final Actions actions = new Actions(keyconf);
		actions.runnableAction( () -> {
			System.out.println("UNDO");
			stack1.undo();
			viewer.requestRepaint();
		}, UNDO, UNDO_KEYS );
		actions.runnableAction( () -> {
			System.out.println("REDO");
			stack1.redo();
			viewer.requestRepaint();
		}, REDO, REDO_KEYS );
		actions.install(bdv.getBdvHandle().getKeybindings(), "view alignment");
	}



}
