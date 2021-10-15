/*
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.hotknife.tools;

import java.awt.Insets;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import org.janelia.saalfeldlab.hotknife.tools.actions.KeyActionMaps;
import org.janelia.saalfeldlab.hotknife.tools.actions.PrintScale;
import org.janelia.saalfeldlab.hotknife.tools.proofread.LocationsPanel;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.BdvFunctions;
import bdv.util.BdvHandle;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.Interpolation;
import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class ReviewFlattenedFace
        implements Callable<Void>{

	@SuppressWarnings("unused")
	@Option(names = {"--container"}, required = true, description = "container path, e.g. --container /nrs/flyem/render/n5/Z0720_07m_BR")
	private String containerPath;

	@SuppressWarnings("unused")
	@Option(names = {"--dataset"}, required = true, description = "dataset, e.g. --dataset '/flat/Sec06/top/face/s0'")
	private String datasetPath;

	@SuppressWarnings("unused")
	@Option(names = {"--locationsFile"}, description = "full path for review locations JSON file, e.g. /nrs/flyem/render/n5/Z0720_07m_BR/review/Sec06/top13/locations.trautmane.json")
	private String locationsFilePath;

	public static void main(final String... args) throws Exception {
		final CommandLine cmd = new CommandLine(new ReviewFlattenedFace());
		cmd.execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final N5Reader n5Reader = new N5FSReader(containerPath);

		final RandomAccessibleInterval<FloatType> img = N5Utils.open(n5Reader, datasetPath);

		final BdvOptions options = BdvOptions.options()
				.is2D()
				.screenScales(new double[] {0.5})
				.numRenderingThreads(Runtime.getRuntime().availableProcessors());

		BdvStackSource<?> bdv = BdvFunctions.show(img, datasetPath, options);

		final BdvHandle handle = bdv.getBdvHandle();

		// force intensity range to 8-bit
		bdv.setDisplayRange(0, 255);

		// Prefs.showScaleBar(true); // commented-out because this breaks with 2D view

		// -----------------------------------
		// add locations panel UI ...

		final ViewerPanel viewerPanel = handle.getViewerPanel();
		viewerPanel.setInterpolation(Interpolation.NLINEAR);

		final CardPanel cardPanel = handle.getCardPanel();

		Double sourceScale = null;
		// datasetPath = /flat/Sec06/top/face/s0
		if (datasetPath.matches(".*/s\\d")) {
			final double scaleIndex = Double.parseDouble(datasetPath.substring(datasetPath.length() - 1));
			if (scaleIndex > 0) {
				sourceScale = Math.pow(2.0, scaleIndex);
			}
		}
		System.out.println("set sourceScale to " + sourceScale + " for dataset " + datasetPath);

		final LocationsPanel locationsPanel = new LocationsPanel(viewerPanel, locationsFilePath, sourceScale);

		cardPanel.addCard(LocationsPanel.KEY,
						  "Locations",
						  locationsPanel,
						  true,
						  new Insets(0, 4, 0, 0));
		cardPanel.setCardExpanded(BdvDefaultCards.DEFAULT_VIEWERMODES_CARD, false);
		cardPanel.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCES_CARD, false);
		cardPanel.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false);

		viewerPanel.requestRepaint();

		KeyActionMaps keyActionMaps = new KeyActionMaps("persistence", handle);
		final PrintScale printScaleAction = new PrintScale(viewerPanel);
		keyActionMaps.register(printScaleAction);

		// show scale by default
		printScaleAction.actionPerformed(null);

		final JFrame window = (JFrame) SwingUtilities.getWindowAncestor(viewerPanel);
		window.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

		return null;
	}
}
