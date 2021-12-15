package org.janelia.saalfeldlab.hotknife.tobi;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.miginfocom.swing.MigLayout;

public class GaussShiftCard {

	private final JPanel panel;

	public GaussShiftCard(GaussShiftEditor editor) {
		panel = new JPanel(new MigLayout("gap 0, ins 5 5 5 0, fill", "[right][grow]", "center"));

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
