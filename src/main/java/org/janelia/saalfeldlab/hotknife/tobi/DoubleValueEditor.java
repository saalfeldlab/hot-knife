package org.janelia.saalfeldlab.hotknife.tobi;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.scijava.listeners.Listeners;

public class DoubleValueEditor {

	private final JLabel label;
	private final BoundedValuePanel valuePanel;

	public DoubleValueEditor(
			final JLabel label,
			final BoundedValuePanel valuePanel) {
		this.label = label;
		this.valuePanel = valuePanel;
		valuePanel.changeListeners().add(this::updateModel);
	}

	public static class ValueModel {

		public interface ChangeListener {

			void valueChanged();
		}

		private final DoubleSupplier getter;
		private final DoubleConsumer setter;
		private final Listeners.List<ValueModel.ChangeListener> listeners;

		public ValueModel(DoubleSupplier getter, DoubleConsumer setter) {
			this.getter = getter;
			this.setter = setter;
			this.listeners = new Listeners.SynchronizedList<>();
		}

		void setValue(double value) {
			setter.accept(value);
		}

		double getValue() {
			return getter.getAsDouble();
		}

		Listeners<ValueModel.ChangeListener> changeListeners() {
			return listeners;
		}

		public void notifyChanged() {
			listeners.list.forEach(ValueModel.ChangeListener::valueChanged);
		}
	}

	private ValueModel model;

	private boolean blockUpdates = false;

	private synchronized void updateModel() {
		if (blockUpdates || model == null)
			return;

		final BoundedValue value = valuePanel.getValue();
		model.setValue(value.getValue());

		updateValuePanel();
	}

	private synchronized void updateValuePanel() {
		if (model == null) {
			SwingUtilities.invokeLater(() -> {
				valuePanel.setEnabled(false);
				label.setEnabled(false);
			});
		} else {
			SwingUtilities.invokeLater(() -> {
				synchronized (this) {
					blockUpdates = true;
					valuePanel.setEnabled(true);
					label.setEnabled(true);
					valuePanel.setValue(valuePanel.getValue().withValue(model.getValue()));
					blockUpdates = false;
				}
			});
		}
	}

	private final ValueModel.ChangeListener updateValuePanel = this::updateValuePanel;

	public synchronized void setModel(final ValueModel model) {
		if (this.model != null)
			this.model.changeListeners().remove(updateValuePanel);
		this.model = model;
		if (this.model != null)
			this.model.changeListeners().add(updateValuePanel);
		updateValuePanel();
	}
}
