package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.DisplayMode;
import bdv.viewer.ViewerPanel;
import java.io.IOException;
import java.util.Arrays;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.position.FunctionRealRandomAccessible;
import net.imglib2.realtransform.ClippedTransitionRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.LinAlgHelpers;
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

public class Playground12 {

	// Apply heightfield transform to full volume (transformed to crop coordinates), then apply position field
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
		final double max = 330;
		final RealTransform flatten = extendFlattenTransform(scaledHeightfield, scaledAvg, max, 1000);


		// --------------------------------------------------------------------
		// position field transform
		// --------------------------------------------------------------------
		final String n5PathPositionField = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/positionfield";
		final String positionFieldGroup = "/flat.Sec37.bot.face";
		final N5Reader n5PositionField = new N5FSReader(n5PathPositionField);
		final PositionField positionField = new PositionField(n5PositionField, positionFieldGroup);
		final long[] cropMin = {0, 0};
		final Playground8PositionField.TransformedPositionField transformedPositionField = new Playground8PositionField.TransformedPositionField(positionField, n5Level, cropMin);
		final RealTransform transition =
				new ClippedTransitionRealTransform(
						transformedPositionField.getTransform(),
						IdentityTransform.get(),
						scaledAvg,
						max);


		final RealTransformSequence tfseq = new RealTransformSequence();
		tfseq.add(transition);
		tfseq.add(flatten);
		final RealRandomAccessible<UnsignedByteType> unwarpedCrop = new RealTransformRealRandomAccessible<>(
				Views.interpolate(
						Views.extendValue(crop, new UnsignedByteType()),
						new ClampingNLinearInterpolatorFactory<>()),
				tfseq);


		final FunctionRealRandomAccessible<DoubleType> absDisplacement = new FunctionRealRandomAccessible<>(
				3,
				() -> {
					final double[] p1 = new double[3];
					final double[] p2 = new double[3];
					final RealTransform transform = tfseq.copy();
					return (pos, t) -> {
						pos.localize(p1);
						transform.apply(p1, p2);
						t.set(LinAlgHelpers.distance(p1, p2));
					};
				},
				DoubleType::new);

		final FunctionRealRandomAccessible<DoubleType> gradMag = new FunctionRealRandomAccessible<>(
				3,
				() -> {
					final double[] p1 = new double[3];
					final double[] p2 = new double[3];
					final double[] p3 = new double[3];
					final double[] g = new double[3];
					final RealTransform transform = tfseq.copy();
//					final RealTransform transform = flatten.copy();
					return (pos, t) -> {
						pos.localize(p1);
						for (int d = 0; d < 3; ++d) {
							p1[d] -= 0.5;
							transform.apply(p1, p2);
							LinAlgHelpers.subtract(p2, p1, p2);
							p1[d] += 1;
							transform.apply(p1, p3);
							LinAlgHelpers.subtract(p3, p1, p3);
							p1[d] -= 0.5;
							g[d] = LinAlgHelpers.distance(p2, p3);
						}
						t.set(LinAlgHelpers.length(g));
					};
				},
				DoubleType::new);

		// --------------------------------------------------------------------
		// show in BDV: crop, unwarped crop
		// --------------------------------------------------------------------
		final BdvSource bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(crop), "crop", Bdv.options());
		bdv.getBdvHandle().getViewerPanel().setDisplayMode(DisplayMode.SINGLE);
		final BdvSource unwarpedCropSource = BdvFunctions.show(unwarpedCrop, crop, "unwarped", Bdv.options().addTo(bdv));
		final BdvSource absDisplacementSource = BdvFunctions.show(absDisplacement, crop, "absolute displacement", Bdv.options().addTo(bdv));
		absDisplacementSource.setColor(new ARGBType(0xff00ff));
		absDisplacementSource.setDisplayRangeBounds(0, 200);
		absDisplacementSource.setDisplayRange(0, 100);


		final BdvSource gradMagSource = BdvFunctions.show(gradMag, crop, "displacement gradient magnitude", Bdv.options().addTo(bdv));
		gradMagSource.setColor(new ARGBType(0xff00ff));
		gradMagSource.setDisplayRangeBounds(0, 2);
		gradMagSource.setDisplayRange(0, 0.5);


		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);
	}
}
