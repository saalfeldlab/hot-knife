package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.ViewerPanel;
import java.io.IOException;
import java.util.Arrays;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.ConstantUtils;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.FlattenTransform;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

public class Playground4b {

	// apply FlattenTransform to transformed crop
	public static void main(String[] args) throws IOException {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/s5/";
		final String imgGroup = ".";

		final N5Reader n5 = new N5FSReader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(n5, imgGroup);
		final int n5Level = 5;
		final double[] n5DownsamplingFactors = {1 << n5Level, 1 << n5Level, 1 << n5Level};

		final long[] minIntervalS0 = {47204, 46557, 42756};
		final long[] maxIntervalS0 = {55779, 59038, 53664};
		final long[] minInterval = lscale(minIntervalS0, n5Level);
		final long[] maxInterval = lscale(maxIntervalS0, n5Level);
		final long[] translation = {-minInterval[1], -minInterval[2], maxInterval[0]};
		System.out.println("minInterval = " + Arrays.toString(minInterval));
		System.out.println("maxInterval = " + Arrays.toString(maxInterval));
		System.out.println("translation = " + Arrays.toString(translation));

		final Bdv bdv;
		{
			final IntervalView<UnsignedByteType> crop1 = Views.interval(imgBrain, minInterval, maxInterval);
			final RandomAccessibleInterval<UnsignedByteType> crop2 = Views.rotate(crop1, 1, 0);
			final RandomAccessibleInterval<UnsignedByteType> crop3 = Views.permute(crop2, 1, 2);
			final IntervalView<UnsignedByteType> crop = Views.translate(crop3, translation);
			bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(crop), "crop", Bdv.options());
		}

		minInterval[1] = imgBrain.min(1);
		minInterval[2] = imgBrain.min(2);
		maxInterval[1] = imgBrain.max(1);
		maxInterval[2] = imgBrain.max(2);

		final IntervalView<UnsignedByteType> crop1 = Views.interval(imgBrain, minInterval, maxInterval);
		final RandomAccessibleInterval<UnsignedByteType> crop2 = Views.rotate(crop1, 1, 0 );
		final RandomAccessibleInterval<UnsignedByteType> crop3 = Views.permute(crop2, 1, 2 );
		final IntervalView<UnsignedByteType> crop = Views.translate(crop3, translation);
		BdvFunctions.show(VolatileViews.wrapAsVolatile(crop), "big crop", Bdv.options().addTo(bdv));
	}

	static long[] lscale(long[] pos, int level) {
		final long[] spos = new long[pos.length];
		Arrays.setAll(spos, d -> pos[d] >> level);
		return spos;
	}
}
