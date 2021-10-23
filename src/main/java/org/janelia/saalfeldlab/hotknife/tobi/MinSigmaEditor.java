package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bounds;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

public class MinSigmaEditor {

	private final BoundedValuePanel valuePanel;
	private final DoubleValueEditor editor;

	public MinSigmaEditor(
			final JLabel label,
			final BoundedValuePanel valuePanel,
			final GaussTransform transform) {
		this.valuePanel = valuePanel;
		editor = new DoubleValueEditor(label, valuePanel);

		final JPopupMenu menu = new JPopupMenu();
		menu.add(runnableItem("set bounds ...", valuePanel::setBoundsDialog));
		menu.add(setBoundsItem("set bounds 0..10", 0, 10));
		menu.add(setBoundsItem("set bounds 0..100", 0, 100));
		menu.add(setBoundsItem("set bounds 0..1000", 0, 1000));
		menu.add(setBoundsItem("set bounds 0..10000", 0, 10000));
		valuePanel.setPopup(() -> menu);

		setTransform(transform);
	}

	private GaussTransform transform;
	private GaussTransform.ChangeListener notifyModel;

	public synchronized void setTransform(final GaussTransform transform) {
		DoubleValueEditor.ValueModel model = null;
		if (this.transform != null) {
			this.transform.changeListeners().remove(notifyModel);
		}
		this.transform = transform;
		if (this.transform != null) {
			model = new DoubleValueEditor.ValueModel(transform::getMinSigma, transform::setMinSigma);
			notifyModel = model::notifyChanged;
			this.transform.changeListeners().add(notifyModel);
		}
		editor.setModel(model);
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
		SwingUtilities.invokeLater(() -> {
			synchronized (MinSigmaEditor.this) {
				valuePanel.setValue(valuePanel.getValue()
						.withMinBound(bounds.getMinBound())
						.withMaxBound(bounds.getMaxBound()));
			}
		});
	}
}
