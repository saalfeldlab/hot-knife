package org.janelia.saalfeldlab.hotknife.tobi;

import javax.swing.JLabel;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;

public class ZNCCCard {

	private final JPanel panel;

	public ZNCCCard(final UpdatingZNCCSurfacePyramid zncc) {
		panel = new JPanel(new MigLayout("gap 0, ins 5 5 5 0, fill", "[right][grow]", "center"));

		final BoundedValuePanel widthSlider = new BoundedValuePanel(new BoundedValue(5, 99, 15));
		widthSlider.setBorder(null);
		final JLabel widthLabel = new JLabel("window size");
		panel.add(widthLabel, "aligny baseline");
		panel.add(widthSlider, "growx, wrap");
		final WidthEditor widthEditor = new WidthEditor(widthLabel, widthSlider, zncc);
	}

	public JPanel getPanel() {
		return panel;
	}

	static class WidthEditor {

		private final DoubleValueEditor editor;

		private int size = 5;
		public WidthEditor(
				final JLabel label,
				final BoundedValuePanel valuePanel,
				final UpdatingZNCCSurfacePyramid zncc) {
			editor = new DoubleValueEditor(label, valuePanel);
			setUpdatingZNCCSurfacePyramid(zncc);
		}

		private UpdatingZNCCSurfacePyramid zncc;

		private void setUpdatingZNCCSurfacePyramid(final UpdatingZNCCSurfacePyramid zncc) {
			this.zncc = zncc;
			DoubleValueEditor.ValueModel model = null;
			if (zncc != null) {
				model = new DoubleValueEditor.ValueModel(zncc::getCorrelationWindowWidth, v -> zncc.setCorrelationWindowWidth((int) Math.round(v)));
			}
			editor.setModel(model);
		}
	}

}
