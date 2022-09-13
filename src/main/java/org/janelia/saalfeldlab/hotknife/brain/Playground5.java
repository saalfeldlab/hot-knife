package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvSource;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.ViewerPanel;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.ToDoubleFunction;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.ConstantUtils;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.FlattenTransform;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import static org.janelia.saalfeldlab.hotknife.brain.PlaygroundHeightfieldPlane.fitPlaneToHeightfield;

public class Playground5 {

	public static void main(String[] args) throws IOException {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

		// --------------------------------------------------------------------
		// load and crop image
		// (the crop region covers the full image in Y and Z)
		// --------------------------------------------------------------------
		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/s5/";
		final String imgGroup = ".";

		final N5Reader n5 = new N5FSReader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(n5, imgGroup);
		final int n5Level = 5;

		final long[] minIntervalS0 = {47204, 46557, 42756};
		final long[] maxIntervalS0 = {55779, 59038, 53664};
		final long[] minInterval = lscale(minIntervalS0, n5Level);
		final long[] maxInterval = lscale(maxIntervalS0, n5Level);
		final long[] translation = {-minInterval[1], -minInterval[2], maxInterval[0]};
		final long[] translation4 = {-minInterval[1], -minInterval[2], 0};

		minInterval[1] = imgBrain.min(1);
		minInterval[2] = imgBrain.min(2);
		maxInterval[1] = imgBrain.max(1);
		maxInterval[2] = imgBrain.max(2);

		final IntervalView<UnsignedByteType> crop1 = Views.interval(imgBrain, minInterval, maxInterval);
		final RandomAccessibleInterval<UnsignedByteType> crop2 = Views.rotate(crop1, 1, 0 );
		final RandomAccessibleInterval<UnsignedByteType> crop3 = Views.permute(crop2, 1, 2 );
		final IntervalView<UnsignedByteType> crop = Views.translate(crop3, translation);



		// --------------------------------------------------------------------
		// load and expand heightfield
		// --------------------------------------------------------------------
		final MyHeightField hf = new MyHeightField("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/", ".", new double[] {6, 6, 1}, 4658.6666161072235);
		final RandomAccessibleInterval<FloatType> heightfield = hf.heightfield();
		final double avg = hf.avg();
		final double[] plane = {2.004294094052206, -1.8362464688517335, 4243.432822291761};
		final int fadeToPlaneDist = 1000;
		final int fadeToAvgDist = 2000;
		final RandomAccessible<FloatType> extendHeightfield = extendHeightfield(heightfield, avg, plane, fadeToPlaneDist, fadeToAvgDist);



		// --------------------------------------------------------------------
		// flatten crop with expanded heightfield
		// --------------------------------------------------------------------
		final double[] hfDownsamplingFactors = hf.downsamplingFactors();
		final double[] n5DownsamplingFactors = {1 << n5Level, 1 << n5Level, 1 << n5Level};
		final double[] hfRelativeScale = new double[3];
		Arrays.setAll(hfRelativeScale, d -> hfDownsamplingFactors[d] / n5DownsamplingFactors[d]);
		final RealRandomAccessible<DoubleType> scaledHeightfield = Transform.scaleAndShiftHeightFieldAndValues(extendHeightfield, hfRelativeScale);

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



		// --------------------------------------------------------------------
		// show in BDV: crop, flattened crop, extended heightfield
		// --------------------------------------------------------------------
		final BdvSource bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(crop), "crop", Bdv.options());
			final IntervalView<UnsignedByteType> crop4 = Views.translate(crop3, translation4);
			BdvFunctions.show(VolatileViews.wrapAsVolatile(crop4), "crop4", Bdv.options().addTo(bdv));
		final BdvSource flattenedCropSource = BdvFunctions.show(flattenedCrop, crop, "flattened", Bdv.options().addTo(bdv));



		final RandomAccessible<DoubleType> hfExpanded = Views.addDimension(Views.raster(scaledHeightfield));//, crop.min(2), crop.max(2));
		final BdvSource hfSource = BdvFunctions.show(Views.interval(hfExpanded, crop), "heightfield", Bdv.options().addTo(bdv));
		hfSource.setDisplayRangeBounds(0, 255);
		hfSource.setDisplayRange(0, 255);
		hfSource.setColor(new ARGBType(0x00ff00));

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);
	}

	/**
	 * Extend {@code heightfield} to infinity:
	 * First, the difference of the heightfield to the average plane is computed.
	 * This is then border-extended and faded to 0 (at {@code fadeToPlaneDist}).
	 * Then the average plane is added again, resulting in the original {@code heightfield} smoothly faded to the average plane out-ouf-bounds.
	 * Finally, this is faded to the {@code Z=avg} plane.
	 *
	 * @param heightfield
	 * @param avg
	 * @param plane
	 * @param fadeToPlaneDist
	 * @param fadeToAvgDist
	 *
	 * @return {@code heightfield} extended to infinity
	 */
	public static RandomAccessible<FloatType> extendHeightfield(
			final RandomAccessibleInterval<FloatType> heightfield,
			final double avg,
			final double[] plane,
			final double fadeToPlaneDist,
			final double fadeToAvgDist)
	{
		// make a FunctionRandomAccessible that subtracts the plane from the heightfield
		final ThreadLocal<RandomAccess<FloatType>> hfAccess = ThreadLocal.withInitial(() -> heightfield.randomAccess());
		final RandomAccessible<FloatType> planeDiff = new FunctionRandomAccessible<>(2,
				(xy, t) -> {
					final double x = xy.getDoublePosition(0);
					final double y = xy.getDoublePosition(1);
					final RandomAccess<FloatType> a = hfAccess.get();
					a.setPosition(xy);
					final double z = a.get().getRealDouble();
					final double pz = plane[0] * x + plane[1] * y + plane[2];
					t.set((float) (z - pz));
				},
				FloatType::new);
		// ... and border-extend it
		final RandomAccessible<FloatType> planeDiffExt = Views.extendBorder(Views.interval(planeDiff, heightfield));

		// then first fade it to the plane and then further fade it to the avg
		final ToDoubleFunction<Localizable> fadeToPlaneWeights = intervalDistWeights(heightfield, fadeToPlaneDist);
		final ToDoubleFunction<Localizable> fadeToAvgWeights = intervalDistWeights(heightfield, fadeToAvgDist);
		final ThreadLocal<RandomAccess<FloatType>> pdAccess = ThreadLocal.withInitial(() -> planeDiffExt.randomAccess());
		final RandomAccessible<FloatType> planeFadeToAvg = new FunctionRandomAccessible<>(2,
				(xy, t) -> {
					final double x = xy.getDoublePosition(0);
					final double y = xy.getDoublePosition(1);
					final RandomAccess<FloatType> a = pdAccess.get();
					a.setPosition(xy);
					final double z = a.get().getRealDouble() * fadeToPlaneWeights.applyAsDouble(xy);
					final double pz = plane[0] * x + plane[1] * y + plane[2];

					final double w = fadeToAvgWeights.applyAsDouble(xy);
					t.set((float) (avg + (z + pz - avg) * w));
				},
				FloatType::new);

		return planeFadeToAvg;
	}

	/**
	 * Create a {@code ToDoubleFunction} that computes a weight for a
	 * coordinate. The weight is 1, if the coordinate is inside the specified
	 * {@code interval}. Outside the interval, the weight fades to 0
	 * exponentially with the distance to the interval.
	 *
	 * @param fadeDist
	 * 		approximate distance to interval where weight fades to zero
	 */
	public static ToDoubleFunction<Localizable> intervalDistWeights(final RealInterval interval, final double fadeDist) {
		final double[] min = interval.minAsDoubleArray();
		final double[] max = interval.maxAsDoubleArray();
		final int n = interval.numDimensions();
		final double wFadeOut = 4.0 / fadeDist;
		return (pos) -> {
			double squdist = 0;
			for (int d = 0; d < n; d++) {
				final double x = pos.getDoublePosition(d);
				final double dd = distToInterval(x, min[d], max[d]) * wFadeOut;
				squdist += dd * dd;
			}
			return Math.exp(squdist / (-2.0));
		};
	}

	private static double distToInterval(final double x, final double min, final double max) {
		if (x < min)
			return min - x;
		else if (x > max)
			return x - max;
		else
			return 0;
	}

	/**
	 * Scale {@code pos} by {@code 2^level}.
	 */
	public static long[] lscale(long[] pos, int level) {
		final long[] spos = new long[pos.length];
		Arrays.setAll(spos, d -> pos[d] >> level);
		return spos;
	}

}
