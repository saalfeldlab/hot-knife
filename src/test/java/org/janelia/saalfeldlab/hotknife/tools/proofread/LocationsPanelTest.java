package org.janelia.saalfeldlab.hotknife.tools.proofread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.JFrame;

import bdv.util.PlaceHolderSource;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;

public class LocationsPanelTest {

	public static void main(String[] args) {

		JFrame frame = new JFrame("Test Locations Panel");
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		final Source< Void > source = new PlaceHolderSource("test source");
		final SourceAndConverter< Void > sourceAndConverter = new SourceAndConverter<>(source, null);
		final List< SourceAndConverter< ? > > sources =
				new ArrayList<>(Collections.singletonList(sourceAndConverter));

		ViewerPanel viewerPanel = new ViewerPanel(sources, 1, null);
		final LocationsPanel locationsPanel = new LocationsPanel(viewerPanel, null);

		frame.getContentPane().add(locationsPanel);

		frame.pack();
		frame.setVisible(true);
	}
}
