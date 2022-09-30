package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.ViewerPanel;
import java.io.IOException;
import java.util.Arrays;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.realtransform.ClippedTransitionRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;
import org.janelia.saalfeldlab.hotknife.tobi.IdentityTransform;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import static org.janelia.saalfeldlab.hotknife.brain.Playground5.extendHeightfield;
import static org.janelia.saalfeldlab.hotknife.brain.Playground5.lscale;
import static org.janelia.saalfeldlab.hotknife.brain.Playground6.extendFlattenTransform;

public class Playground11 {

	// Apply heightfield transform to full volume (transformed to crop coordinates), then apply IntervalShift
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
		System.out.println("minInterval = " + Arrays.toString(minInterval));
		System.out.println("maxInterval = " + Arrays.toString(maxInterval));
		final RandomAccessibleInterval<UnsignedByteType> crop2 = Views.rotate(imgBrain, 1, 0 );
		final RandomAccessibleInterval<UnsignedByteType> crop3 = Views.permute(crop2, 1, 2 );
		// crop3 has transformed axes: {X, Y, Z} --> {-Z, X, Y}
		//
		// Mow additionally translate to such that the first (in original coordinates) ZY plane, X=max,
		// is slice Z'=0, and X=max-1 is slice Z'=1, etc.
		// ==> And this all with respect to the [minInterval, maxInterval] crop region.
		final long[] translation = {-minInterval[1], -minInterval[2], maxInterval[0]};

		final IntervalView<UnsignedByteType> crop = Views.translate(crop3, translation);
		System.out.println();
		System.out.println("min imgBrain = " + Arrays.toString(imgBrain.minAsLongArray()));
		System.out.println("min crop     = " + Arrays.toString(crop3.minAsLongArray()));
		System.out.println();
		System.out.println("max imgBrain = " + Arrays.toString(imgBrain.maxAsLongArray()));
		System.out.println("max crop     = " + Arrays.toString(crop3.maxAsLongArray()));
		System.out.println();


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
		// TODO: max should be <= 335 to match stuart's line
		final double max = 500;
		final RealTransform flatten = extendFlattenTransform(scaledHeightfield, scaledAvg, max, 1000);
		final RealRandomAccessible<UnsignedByteType> flattenedCrop = new RealTransformRealRandomAccessible<>(
				Views.interpolate(
						Views.extendValue(crop, new UnsignedByteType()),
						new ClampingNLinearInterpolatorFactory<>()),
				flatten);



		// --------------------------------------------------------------------
		// apply IntervalShift
		// --------------------------------------------------------------------

		final FinalInterval interval = Intervals.createMinSize(
				-100, 0, (long) scaledAvg,
				maxInterval[1] - minInterval[1] + 1,
				maxInterval[2] - minInterval[2] + 1,
				1);
		System.out.println("Intervals.toString(interval) = " + Intervals.toString(interval));
		final double[] shift = {-100, 0, 0};
		final double[] weights = {100, 100, 200};
		final RealTransform intervalShift = new IntervalShift(interval, shift, weights);
		final RealRandomAccessible<UnsignedByteType> shiftedCrop = new RealTransformRealRandomAccessible<>(
				flattenedCrop,
				intervalShift);


		// --------------------------------------------------------------------
		// show in BDV: crop, flattened crop, unwarped crop
		// --------------------------------------------------------------------
		final BdvSource bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(crop), "crop", Bdv.options());
		final BdvSource flattenedCropSource = BdvFunctions.show(flattenedCrop, crop, "flattened", Bdv.options().addTo(bdv));
		final BdvSource shiftedCropSource = BdvFunctions.show(shiftedCrop, crop, "shifted", Bdv.options().addTo(bdv));












		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);
	}
}
