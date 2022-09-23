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
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;
import org.janelia.saalfeldlab.hotknife.tobi.IdentityTransform;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

public class ExtractStatic {


	public static class FlattenAndUnwarp {

		private final RandomAccessibleInterval<UnsignedByteType> imgBrain; // --> img
		private final int n5Level; // --> imgLevel
		private final long[] minIntervalS0; // --> cropMin
		private final long[] maxIntervalS0; // --> cropMax


		private final RandomAccessibleInterval<FloatType> heightfield;
		private final double avg; // --> heightfieldAvg
		private final double[] plane; // --> heightfieldPlane
		private final double[] hfDownsamplingFactors; // --> heightfieldDownsamplingFactors

		private final int fadeToPlaneDist; // TODO: is in heightfield resolution, should be in full resolution
		private final int fadeToAvgDist;  // TODO: is in heightfield resolution, should be in full resolution

		// TODO: is in img resolution, should be in full resolution.
		//       is in crop-relative coordinates, should be full volume relative.
		private final double max; // --> minModifiedX
		// TODO: is in img resolution, should be in full resolution.
		//       is in crop-relative coordinates, should be full volume relative.
		private double fadeFlattenToIdentityDist;
		private PositionField positionField;


		// permuted, translated input
		private RandomAccessibleInterval<UnsignedByteType> crop;

		// outputs
		private RealRandomAccessible<UnsignedByteType> unwarpedCrop;
		private RealRandomAccessible<DoubleType> absDisplacement;


		/**
		 * @param img image to flatten and unwarp
		 * @param imgLevel resolution level of {@code img} (in power-of-two
		 * 			downsampling pyramid), that is, length {@code l} in {@code img} is
		 * 			length {@code l * 2^imgLevel} in full resolution.
		 *
		 * @param cropMin minimum of the crop region (in full resolution pixel coordinates).
		 * @param cropMin maximum of the crop region (in full resolution pixel coordinates).
		 *          The crop region is what the height field (for flattening) and position field (for unwarping) were computed on.
		 *          We need to know the crop region to properly translate the fields to align with the full image.
		 *
		 * @param heightfield TODO
		 * @param heightfieldAvg TODO
		 * @param heightfieldPlane TODO
		 * @param heightfieldDownsamplingFactors TODO
		 *
		 * @param fadeToPlaneDist TODO
		 * @param fadeToAvgDist TODO
		 */
		public FlattenAndUnwarp(
				final RandomAccessibleInterval<UnsignedByteType> img, // imgBrain
				final int imgLevel, // n5Level

				final long[] cropMin, // minIntervalS0
				final long[] cropMax, // maxIntervalS0

				final RandomAccessibleInterval<FloatType> heightfield,
				final double heightfieldAvg, // avg
				final double[] heightfieldPlane, // plane
				final double[] heightfieldDownsamplingFactors, // hfDownsamplingFactors

				final int fadeToPlaneDist, // TODO: is in heightfield resolution, should be in full resolution
				final int fadeToAvgDist,  // TODO: is in heightfield resolution, should be in full resolution
				// TODO: max should be <= 335 to match stuart's line
				// TODO: is in img resolution, should be in full resolution.
				//       is in crop-relative coordinates, should be full volume relative.
				final double minModifiedX, // max
				// TODO: is in img resolution, should be in full resolution.
				//       is in crop-relative coordinates, should be full volume relative.
				final double fadeFlattenToIdentityDist, // extendFlattenTransform(..., fadeDist) argument

				final PositionField positionField
		)
		{
			this.imgBrain = img;
			this.n5Level = imgLevel;
			this.minIntervalS0 = cropMin;
			this.maxIntervalS0 = cropMax;
			this.heightfield = heightfield;
			this.avg = heightfieldAvg;
			this.plane = heightfieldPlane;
			this.hfDownsamplingFactors = heightfieldDownsamplingFactors;
			this.fadeToPlaneDist = fadeToPlaneDist;
			this.fadeToAvgDist = fadeToAvgDist;
			this.max = minModifiedX;
			this.fadeFlattenToIdentityDist = fadeFlattenToIdentityDist;
			this.positionField = positionField;

			unwarp();
		}

		private void unwarp() {
			final long[] minInterval = lscale(minIntervalS0, n5Level);
			final long[] maxInterval = lscale(maxIntervalS0, n5Level);


			final RandomAccessibleInterval<UnsignedByteType> crop1 = Views.rotate(imgBrain, 1, 0 );
			final RandomAccessibleInterval<UnsignedByteType> crop2 = Views.permute(crop1, 1, 2 );
			// crop2 has transformed axes: {X, Y, Z} --> {-Z, X, Y}
			//
			// Mow additionally translate to such that the first (in original coordinates) ZY plane, X=max,
			// is slice Z'=0, and X=max-1 is slice Z'=1, etc.
			// ==> And this all with respect to the [minInterval, maxInterval] crop region.
			final long[] translation = {-minInterval[1], -minInterval[2], maxInterval[0]};
			crop = Views.translate(crop2, translation);


			// --------------------------------------------------------------------
			// expand heightfield
			// --------------------------------------------------------------------
			final RandomAccessible<FloatType> extendHeightfield = ExtendHeightField.extendHeightfield(heightfield, avg, plane, fadeToPlaneDist, fadeToAvgDist);


			// --------------------------------------------------------------------
			// flatten crop with expanded heightfield
			// --------------------------------------------------------------------
			final double[] n5DownsamplingFactors = {1 << n5Level, 1 << n5Level, 1 << n5Level};
			final double[] hfRelativeScale = new double[3];
			Arrays.setAll(hfRelativeScale, d -> hfDownsamplingFactors[d] / n5DownsamplingFactors[d]);
			final RealRandomAccessible<DoubleType> scaledHeightfield = Transform.scaleAndShiftHeightFieldAndValues(extendHeightfield, hfRelativeScale);
			final double scaledAvg = (avg + 0.5) * hfRelativeScale[2] - 0.5;
			final RealTransform flatten = ExtendFlattenTransform.extendFlattenTransform(scaledHeightfield, scaledAvg, max, fadeFlattenToIdentityDist);


			// --------------------------------------------------------------------
			// scale  position field transform and fade to identity
			// --------------------------------------------------------------------
			final long[] cropMin = {0, 0};
			final Playground8PositionField.TransformedPositionField transformedPositionField = new Playground8PositionField.TransformedPositionField(positionField, n5Level, cropMin);
			final RealTransform transition =
					new ClippedTransitionRealTransform(
							transformedPositionField.getTransform(),
							IdentityTransform.get(),
							scaledAvg,
							max);


			// --------------------------------------------------------------------
			// concatenate flattening and position field transform
			// --------------------------------------------------------------------
			final RealTransformSequence tfseq = new RealTransformSequence();
			tfseq.add(transition);
			tfseq.add(flatten);


			// TODO: unwarpedCrop is the first output we compute.
			//       We still need to transform it back to img coordinates
			unwarpedCrop = new RealTransformRealRandomAccessible<>(
					Views.interpolate(
							Views.extendValue(crop, new UnsignedByteType()),
							new ClampingNLinearInterpolatorFactory<>()),
					tfseq);


			// TODO: absDisplacement is the second output we compute.
			//       We still need to transform it back to img coordinates
			absDisplacement = new FunctionRealRandomAccessible<>(
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
		}

		public RandomAccessibleInterval<UnsignedByteType> getCrop() {
			return crop;
		}

		public RealRandomAccessible<DoubleType> getAbsDisplacement() {
			return absDisplacement;
		}

		public RealRandomAccessible<UnsignedByteType> getUnwarpedCrop() {
			return unwarpedCrop;
		}

		/**
		 * Scale {@code pos} by {@code 2^level}.
		 */
		private static long[] lscale(long[] pos, int level) {
			final long[] spos = new long[pos.length];
			Arrays.setAll(spos, d -> pos[d] >> level);
			return spos;
		}
	}


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


		// --------------------------------------------------------------------
		// load heightfield
		// --------------------------------------------------------------------
		final MyHeightField hf = new MyHeightField("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/", ".", new double[] {6, 6, 1}, 4658.6666161072235);
		final RandomAccessibleInterval<FloatType> heightfield = hf.heightfield();
		final double[] hfDownsamplingFactors = hf.downsamplingFactors();
		final double avg = hf.avg();
		final double[] plane = {2.004294094052206, -1.8362464688517335, 4243.432822291761};
		final int fadeToPlaneDist = 1000;
		final int fadeToAvgDist = 2000;
		final double max = 500; // TODO: max should be <= 335 to match stuart's line


		// --------------------------------------------------------------------
		// load position field
		// --------------------------------------------------------------------
		final String n5PathPositionField = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/positionfield";
		final String positionFieldGroup = "/flat.Sec37.bot.face";
		final N5Reader n5PositionField = new N5FSReader(n5PathPositionField);
		final PositionField positionField = new PositionField(n5PositionField, positionFieldGroup);


		// --------------------------------------------------------------------
		// flatten and unwarp
		// --------------------------------------------------------------------
		FlattenAndUnwarp fau = new FlattenAndUnwarp(
				imgBrain, n5Level, minIntervalS0, maxIntervalS0,
				heightfield, avg, plane, hfDownsamplingFactors, fadeToPlaneDist, fadeToAvgDist, max, 1000,
				positionField);

		final RandomAccessibleInterval<UnsignedByteType> crop = fau.getCrop();
		final RealRandomAccessible<UnsignedByteType> unwarpedCrop = fau.getUnwarpedCrop();
		final RealRandomAccessible<DoubleType> absDisplacement =fau.getAbsDisplacement();


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


		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);
	}
}
