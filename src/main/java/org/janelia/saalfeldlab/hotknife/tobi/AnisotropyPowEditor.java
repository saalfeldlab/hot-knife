package org.janelia.saalfeldlab.hotknife.tobi;

import javax.swing.JLabel;
import org.janelia.saalfeldlab.hotknife.tobi.DoubleValueEditor.ValueModel;

public class AnisotropyPowEditor {

	private final DoubleValueEditor editor;

	public AnisotropyPowEditor(
			final JLabel label,
			final BoundedValuePanel valuePanel,
			final GaussTransform transform) {
		editor = new DoubleValueEditor(label, valuePanel);
		setTransform(transform);
	}

	private GaussTransform transform;
	private GaussTransform.ChangeListener notifyModel;

	public synchronized void setTransform(final GaussTransform transform) {
		ValueModel model = null;
		if (this.transform != null) {
			this.transform.changeListeners().remove(notifyModel);
		}
		this.transform = transform;
		if (this.transform != null) {
			model = new ValueModel(transform::getAnisotropyPow, transform::setAnisotropyPow);
			notifyModel = model::notifyChanged;
			this.transform.changeListeners().add(notifyModel);
		}
		editor.setModel(model);
	}
}
