package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.ConstantRandomAccessible;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.ViewerPanel;
import java.io.IOException;
import java.util.Arrays;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

public class Playground2 {

	public static void main(String[] args) throws IOException {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

//		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/full_brain_final.n5";
//		final String imgGroup = "/setup0/timepoint0/s0";
		final String n5Path = "/Users/pietzsch/Downloads/s5";
		final String imgGroup = ".";

		final N5Reader n5 = new N5FSReader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(n5, imgGroup);

		final long[] minIntervalS0 = {47204, 46557, 42756};
		final long[] maxIntervalS0 = {55779, 59038, 53664};
		final long[] minInterval = lscale( minIntervalS0, 5 );
		final long[] maxInterval = lscale( maxIntervalS0, 5 );
		System.out.println("minInterval = " + Arrays.toString(minInterval));
		System.out.println("maxInterval = " + Arrays.toString(maxInterval));
		final IntervalView<UnsignedByteType> crop1 = Views.interval(imgBrain, minInterval, maxInterval);
		final RandomAccessibleInterval<UnsignedByteType> crop2 = Views.rotate(crop1, 1, 0 );
		final RandomAccessibleInterval<UnsignedByteType> crop3 = Views.permute(crop2, 1, 2 );

		final BdvSource bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(imgBrain), "full brain final - s5", Bdv.options());
		BdvFunctions.show(VolatileViews.wrapAsVolatile(crop1), "crop1", Bdv.options().addTo(bdv));

		final ConstantRandomAccessible<UnsignedByteType> constant = new ConstantRandomAccessible<>(new UnsignedByteType(255), 3);
		final IntervalView<UnsignedByteType> interval = Views.interval(constant, minInterval, maxInterval);
		BdvFunctions.show(interval, "interval", Bdv.options().addTo(bdv));

//		BdvFunctions.show(VolatileViews.wrapAsVolatile(crop2), "crop2", Bdv.options().addTo(bdv));
//		BdvFunctions.show(VolatileViews.wrapAsVolatile(crop3), "crop3", Bdv.options().addTo(bdv));

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);
	}

	static long[] lscale(long[] pos, int level) {
		final long[] spos = new long[pos.length];
		Arrays.setAll(spos, d -> pos[d] >> level);
		return spos;
	}
}
