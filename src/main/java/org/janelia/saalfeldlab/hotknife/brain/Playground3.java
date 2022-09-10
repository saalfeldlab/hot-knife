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
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

public class Playground3 {

	// overlay scaled heightfield and crop (to check whether scaling is correct)
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
		final IntervalView<UnsignedByteType> crop = Views.zeroMin(crop3);


		final BdvSource bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(crop), "crop", Bdv.options());
		BdvFunctions.show(VolatileViews.wrapAsVolatile(Views.zeroMin(crop1)), "crop1", Bdv.options().addTo(bdv));
		BdvFunctions.show(VolatileViews.wrapAsVolatile(Views.zeroMin(crop2)), "crop2", Bdv.options().addTo(bdv));
		BdvFunctions.show(VolatileViews.wrapAsVolatile(Views.zeroMin(crop3)), "crop3", Bdv.options().addTo(bdv));


		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);

		final MyHeightField hf = new MyHeightField("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/", ".", new double[] {6, 6, 1});
		final RandomAccessibleInterval<FloatType> heightfield = hf.heightfield();
		final double[] hfDownsamplingFactors = hf.downsamplingFactors();
		final double[] hfRelativeScale = new double[3];
		Arrays.setAll(hfRelativeScale, d -> hfDownsamplingFactors[d] / n5DownsamplingFactors[d]);
		final RealRandomAccessible<DoubleType> scaledHeightfield = Transform.scaleAndShiftHeightFieldAndValues(heightfield, hfRelativeScale);

		final double avg = 4658.6666161072235;
		final double scaledAvg = (avg + 0.5) * hfRelativeScale[2] - 0.5;


////		new FlattenTransform<>()
//		Transform.sc
//		Transform.scaleAndShiftHeightFieldAndValues(minField, downsamplingFactors),
//				Transform.scaleAndShiftHeightFieldAndValues(maxField, downsamplingFactors),

//		final RandomAccessibleInterval<DoubleType> hfExpanded = Views.addDimension(Views.raster(scaledHeightfield), crop.min(2), crop.max(2));
		final RandomAccessible<DoubleType> hfExpanded = Views.addDimension(Views.raster(scaledHeightfield));//, crop.min(2), crop.max(2));
		BdvSource hfSource = BdvFunctions.show(Views.interval(hfExpanded, crop), "heightfield", Bdv.options().addTo(bdv));
		hfSource.setDisplayRangeBounds(0, 255);
		hfSource.setDisplayRange(0, 255);
		hfSource.setColor(new ARGBType(0x00ff00));

//		BdvSource hfSource = BdvFunctions.show(heightfield, "heightfield", Bdv.options());

	}

	static long[] lscale(long[] pos, int level) {
		final long[] spos = new long[pos.length];
		Arrays.setAll(spos, d -> pos[d] >> level);
		return spos;
	}


	public static class MyHeightField {

		private RandomAccessibleInterval<FloatType> heightfield;
		private double[] downsamplingFactors;

		public MyHeightField() throws IOException {
			this(
					"/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/",
					".",
					new double[] {6, 6, 1}
			);
		}

		public MyHeightField(final String n5Path, final String group, final double[] downsamplingFactors) throws IOException {
			this.downsamplingFactors = downsamplingFactors;

			final N5Reader hfReader = new N5FSReader(n5Path);
			final RandomAccessibleInterval<FloatType> tmpHF3 = N5Utils.openVolatile(hfReader, group);
			heightfield = Views.hyperSlice(tmpHF3, 2, 0);
		}

		public RandomAccessibleInterval<FloatType> heightfield() {
			return heightfield;
		}

		public double[] downsamplingFactors() {
			return downsamplingFactors;
		}
	}
}
