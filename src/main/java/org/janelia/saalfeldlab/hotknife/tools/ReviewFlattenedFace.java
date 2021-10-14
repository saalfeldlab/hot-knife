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

import mpicbg.spim.data.sequence.DefaultVoxelDimensions;

import org.janelia.saalfeldlab.hotknife.tools.proofread.LocationsPanel;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.volatiles.VolatileUnsignedByteType;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class ReviewFlattenedFace
        implements Callable<Void>{

	@SuppressWarnings("unused")
	@Option(names = {"--container"}, required = true, description = "container path, e.g. --container /nrs/flyem/render/n5/Z0720_07m_BR")
	private String containerPath;

	@SuppressWarnings("unused")
	@Option(names = {"--dataset"}, required = true, description = "dataset, e.g. --dataset '/flat/Sec06/top/s1'")
	private String datasetPath;

	@SuppressWarnings("unused")
	@Option(names = {"--locationsFile"}, description = "full path for review locations JSON file, e.g. /nrs/flyem/render/n5/Z0720_07m_BR/review/Sec38/v3_acquire_trimmed_sp1_adaptive_ic___20210424_155438_gauss/min/locations.trautmane.json")
	private String locationsFilePath;

	public static void main(final String... args) throws Exception {
		final CommandLine cmd = new CommandLine(new ReviewFlattenedFace());
		cmd.execute(args);
	}

	@SuppressWarnings("unchecked")
	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final N5Reader n5 = new N5FSReader(containerPath);

		final double[][] scales = new double[][] {{ 1, 1, 1 }};
		final RandomAccessibleInterval<UnsignedByteType>[] rawMipmaps = new RandomAccessibleInterval[1];
		rawMipmaps[0] = N5Utils.openVolatile(n5, datasetPath);

		final BdvOptions options =
				BdvOptions.options()
				.screenScales(new double[] {0.5})
				.numRenderingThreads(Runtime.getRuntime().availableProcessors());

//		final UnsignedByteType byteType = net.imglib2.util.Util.getTypeFromInterval(rawMipmaps[0]).createVariable();
		final UnsignedByteType byteType = new VolatileUnsignedByteType(0).get();

		final Source<?> mipmapSource =
				new RandomAccessibleIntervalMipmapSource<>(
						rawMipmaps,
						byteType,
						scales,
						new DefaultVoxelDimensions(3),
						datasetPath);

		BdvStackSource<?> bdv = Show.mipmapSource(mipmapSource, null, options);

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		viewerPanel.setInterpolation(Interpolation.NLINEAR);

		final SynchronizedViewerState viewerState = viewerPanel.state();

		final AffineTransform3D transform = new AffineTransform3D();
		viewerState.getViewerTransform(transform);
		transform.set(0, 3, 4);
		viewerState.setViewerTransform(transform);

		final CardPanel cardPanel = bdv.getBdvHandle().getCardPanel();

		Double sourceScale = null;
		// datasetPath = /flat/Sec06/top/s1
		if (datasetPath.matches(".*/s\\d")) {
			final double scaleIndex = Double.parseDouble(datasetPath.substring(datasetPath.length() - 1));
			sourceScale = Math.pow(2.0, scaleIndex);
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

		final JFrame window = (JFrame) SwingUtilities.getWindowAncestor(viewerPanel);
		window.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

		return null;
	}

}
