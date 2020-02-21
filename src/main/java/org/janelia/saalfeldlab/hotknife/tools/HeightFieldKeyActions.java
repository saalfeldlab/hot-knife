package org.janelia.saalfeldlab.hotknife.tools;

import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.AbstractNamedAction;
import org.scijava.ui.behaviour.util.InputActionBindings;

import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class HeightFieldKeyActions {

	final protected ViewerPanel viewer;
	final protected RandomAccessibleInterval<FloatType> heightField;
	final protected double avg;

	final protected String n5Path;
	final protected String heightFieldDataset;

	// for keystroke actions
	private final ActionMap ksActionMap = new ActionMap();
	private final InputMap ksInputMap = new InputMap();
	private final KeyStrokeAdder ksKeyStrokeAdder;

	public HeightFieldKeyActions(
			final ViewerPanel viewer,
			final RandomAccessibleInterval<FloatType> heightField,
			final double avg,
			final String n5Path,
			final String heightFieldDataset,
			final InputTriggerConfig config,
			final InputActionBindings inputActionBindings) {

		this.viewer = viewer;
		this.heightField = heightField;
		this.avg = avg;
		this.n5Path = n5Path;
		this.heightFieldDataset = heightFieldDataset;

		ksKeyStrokeAdder = config.keyStrokeAdder(ksInputMap, "persistence");

		new SaveHeightField("save heightfield", "ctrl S").register();
		new Undo("undo", "ctrl Z").register();

		inputActionBindings.addActionMap("persistence", ksActionMap);
		inputActionBindings.addInputMap("persistence", ksInputMap);
	}

	private abstract class SelfRegisteringAction extends AbstractNamedAction {

		private static final long serialVersionUID = -1032489117210681503L;

		private final String[] defaultTriggers;

		public SelfRegisteringAction(final String name, final String... defaultTriggers) {
			super(name);
			this.defaultTriggers = defaultTriggers;
		}

		public void register() {
			put(ksActionMap);
			ksKeyStrokeAdder.put(name(), defaultTriggers);
		}
	}

	public void saveHeightField() throws IOException, InterruptedException, ExecutionException {

		System.out.print("Saving heightfield " + n5Path + ":/" + heightFieldDataset + " ... ");
		final ExecutorService exec = Executors.newFixedThreadPool(4);
		final N5FSWriter n5 = new N5FSWriter(n5Path);
		N5Utils.save(
				heightField,
				n5,
				heightFieldDataset,
				new int[] {1024, 1024},
				new GzipCompression(),
				exec);
		exec.shutdown();
		n5.setAttribute(heightFieldDataset, "avg", avg);
		System.out.println("done.");
	}

	private class SaveHeightField extends SelfRegisteringAction {

		private static final long serialVersionUID = -7884038268749788208L;

		public SaveHeightField(final String name, final String ... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
				try {
					saveHeightField();
				} catch (final IOException | InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
			}
		}
	}

	private class Undo extends SelfRegisteringAction {

		public Undo(final String name, final String ... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
				try {
					final N5FSReader n5 = new N5FSReader(n5Path);
					if (n5.datasetExists(heightFieldDataset)) {
						final long[] dimensions = n5.getAttribute(heightFieldDataset, "dimensions", long[].class);
						if (dimensions[0] == heightField.dimension(0) && dimensions[1] == heightField.dimension(1)) {
							Util.copy(N5Utils.open(n5, heightFieldDataset), heightField);
						}
					}
				} catch (final IOException e) {
					e.printStackTrace();
				}
				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
				viewer.requestRepaint();
			}
		}
	}
}
