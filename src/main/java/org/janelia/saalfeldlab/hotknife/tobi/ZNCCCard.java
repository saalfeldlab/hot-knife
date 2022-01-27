package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Source;
import java.util.Arrays;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.blk.copy.ArrayImgBlocks;
import net.imglib2.blk.view.ViewBlocks;
import net.imglib2.blk.view.ViewProps;
import net.imglib2.blk.zncc.ZNCCFloat;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory;
import net.imglib2.cache.img.ReadOnlyCachedCellImgOptions;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import net.miginfocom.swing.MigLayout;

import static net.imglib2.blk.copy.Extension.MIRROR_SINGLE;

public class ZNCCCard {

	private final JPanel panel;

	public ZNCCCard() {
		panel = new JPanel(new MigLayout("gap 0, ins 5 5 5 0, fill", "[right][grow]", "center"));

		JButton updateZNCC = new JButton("Hello!");
		panel.add(updateZNCC, "growx, wrap");
		updateZNCC.addActionListener(e -> System.out.println("Hello!"));
	}

	public JPanel getPanel() {
		return panel;
	}
}
