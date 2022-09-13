package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.ViewerPanel;
import java.io.IOException;
import java.util.Arrays;
import net.imglib2.RandomAccess;
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

public class Playground4 {

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
		final long[] minInterval = lscale( minIntervalS0, n5Level );
		final long[] maxInterval = lscale( maxIntervalS0, n5Level );
		System.out.println("minInterval = " + Arrays.toString(minInterval));
		System.out.println("maxInterval = " + Arrays.toString(maxInterval));
		final IntervalView<UnsignedByteType> crop1 = Views.interval(imgBrain, minInterval, maxInterval);
		final RandomAccessibleInterval<UnsignedByteType> crop2 = Views.rotate(crop1, 1, 0 );
		final RandomAccessibleInterval<UnsignedByteType> crop3 = Views.permute(crop2, 1, 2 );
		final IntervalView<UnsignedByteType> crop = Views.translate(crop3, -minInterval[1], -minInterval[2], maxInterval[0]);
//		final IntervalView<UnsignedByteType> crop = Views.zeroMin(crop3);
//		final RandomAccess<UnsignedByteType> a = crop.randomAccess();
//		a.setPosition(new long[] {0,0,0});
//		System.out.println("{0,0,0} ==> " + Arrays.toString(a.positionAsLongArray()));

		final BdvSource bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(crop), "crop", Bdv.options());

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);

		final MyHeightField hf = new MyHeightField("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/", ".",
				new double[] {6, 6, 1},
				4658.6666161072235);
		final RandomAccessibleInterval<FloatType> heightfield = hf.heightfield();
		final double[] hfDownsamplingFactors = hf.downsamplingFactors();
		final double[] hfRelativeScale = new double[3];
		Arrays.setAll(hfRelativeScale, d -> hfDownsamplingFactors[d] / n5DownsamplingFactors[d]);
		final RealRandomAccessible<DoubleType> scaledHeightfield = Transform.scaleAndShiftHeightFieldAndValues(heightfield, hfRelativeScale);

		final double avg = hf.avg();
		final double scaledAvg = (avg + 0.5) * hfRelativeScale[2] - 0.5;

		final long max = crop.max(2);
		final RealRandomAccessible<DoubleType> constHeightfield = ConstantUtils.constantRealRandomAccessible(new DoubleType(max), 2);
		final FlattenTransform<DoubleType> flattenTransform = new FlattenTransform<>(
				scaledHeightfield,
				constHeightfield,
				scaledAvg, max);

		final RealRandomAccessible<UnsignedByteType> flattenedCrop = new RealTransformRealRandomAccessible<>(
				Views.interpolate(
						Views.extendValue(crop, new UnsignedByteType()),
						new ClampingNLinearInterpolatorFactory<>()),
				flattenTransform.inverse());

		final RandomAccessible<DoubleType> hfExpanded = Views.addDimension(Views.raster(scaledHeightfield));//, crop.min(2), crop.max(2));
		final BdvSource hfSource = BdvFunctions.show(Views.interval(hfExpanded, crop), "heightfield", Bdv.options().addTo(bdv));
		hfSource.setDisplayRangeBounds(0, 255);
		hfSource.setDisplayRange(0, 255);
		hfSource.setColor(new ARGBType(0x00ff00));

		final BdvSource flattenedCropSource = BdvFunctions.show(flattenedCrop, crop, "flattened", Bdv.options().addTo(bdv));
	}

	static long[] lscale(long[] pos, int level) {
		final long[] spos = new long[pos.length];
		Arrays.setAll(spos, d -> pos[d] >> level);
		return spos;
	}
}
