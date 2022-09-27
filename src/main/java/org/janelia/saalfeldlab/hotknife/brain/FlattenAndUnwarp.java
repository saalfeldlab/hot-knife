package org.janelia.saalfeldlab.hotknife.brain;

import java.util.Arrays;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.position.FunctionRealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ClippedTransitionRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.tobi.IdentityTransform;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.hotknife.util.Transform;

import static org.janelia.saalfeldlab.hotknife.brain.FlattenAndUnwarp.Scale.System.FULL_RESOLUTION;
import static org.janelia.saalfeldlab.hotknife.brain.FlattenAndUnwarp.Scale.System.HEIGHTFIELD;
import static org.janelia.saalfeldlab.hotknife.brain.FlattenAndUnwarp.Scale.System.IMAGE;

public class FlattenAndUnwarp {

	private final Scale scale;

	private final RandomAccessibleInterval<UnsignedByteType> img;
	private final int imgLevel;
	private final Scale.Coordinate cropMin;
	private final Scale.Coordinate cropMax;


	private final RandomAccessibleInterval<FloatType> heightfield;
	private final double heightfieldAvg;
	private final double[] heightfieldPlane;
	private final double[] heightfieldDownsamplingFactors;

	private final int fadeToPlaneDist;
	private final int fadeToAvgDist;

	private final double minModifiedX;

	private final double fadeFlattenToIdentityDist;
	private final PositionField positionField;

	// permuted, translated input
	private final RandomAccessibleInterval<UnsignedByteType> crop;

	// outputs
	private final RealRandomAccessible<UnsignedByteType> unwarpedCrop;
	private final RealRandomAccessible<DoubleType> absDisplacement;


	/**
	 * @param img
	 * 		image to flatten and unwarp
	 * @param imgLevel
	 * 		resolution level of {@code img} (in power-of-two downsampling pyramid), that is,
	 * 		length {@code l} in {@code img} is length {@code l * 2^imgLevel} in full
	 * 		resolution.
	 * @param cropMin
	 * 		minimum of the crop region (in full resolution pixel coordinates).
	 * @param cropMin
	 * 		maximum of the crop region (in full resolution pixel coordinates). The crop
	 * 		region is what the height field (for flattening) and position field (for
	 * 		unwarping) were computed on. We need to know the crop region to properly
	 * 		translate the fields to align with the full image.
	 * @param heightfield
	 * 		2D heightfield (value at coordinate xy is the z height). The heightfield
	 * 		coordinates and value may are scaled by {@code heightfieldDownsamplingFactors}
	 * 		wrt full resolution.
	 * @param heightfieldAvg
	 * 		average value of the heightfield (in heightfield scaled coordinates).
	 * @param heightfieldPlane
	 * 		average plane of the heightfield. {@code heightfieldPlane = {a,b,c} represents
	 * 		the plane {@code z = ax + by + c}. (in heightfield scaled coordinates)
	 * @param heightfieldDownsamplingFactors
	 * 		downsampling factors wrt full resolution in X, Y (coordinates), and Z (values).
	 * @param fadeToPlaneDist
	 * 		The distance from the border of the heightfield (in full resolution pixel
	 * 		coordinates), where the extended heightfield fades to {@code heightfieldPlane}.
	 * @param fadeToAvgDist
	 * 		The distance (in full resolution pixel coordinates) from the border of the
	 * 		heightfield, where the extended heightfield fades to {@code heightfieldAvg}.
	 * @param minModifiedX
	 * 		nothing below this X coordinate (in full resolution pixel coordinates) must be
	 * 		modified by the transformation
	 * @param fadeFlattenToIdentityDist
	 * 		distance (in X) from flattened surface where the heightfield transform fades
	 * 		back to identity (in full resolution pixel coordinates)
	 * @param positionField
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

			final int fadeToPlaneDist,
			final int fadeToAvgDist,
			final double minModifiedX,
			final double fadeFlattenToIdentityDist,

			final PositionField positionField
	) {
		this.scale = new Scale(imgLevel, heightfieldDownsamplingFactors);
		this.img = img;
		this.imgLevel = imgLevel;
		this.cropMin = scale.fullres(cropMin);
		this.cropMax = scale.fullres(cropMax);
		this.heightfield = heightfield;
		this.heightfieldAvg = heightfieldAvg;
		this.heightfieldPlane = heightfieldPlane;
		this.heightfieldDownsamplingFactors = heightfieldDownsamplingFactors;
		this.fadeToPlaneDist = fadeToPlaneDist;
		this.fadeToAvgDist = fadeToAvgDist;
		this.minModifiedX = minModifiedX;
		this.fadeFlattenToIdentityDist = fadeFlattenToIdentityDist;
		this.positionField = positionField;


		final long[] minInterval = round(this.cropMin.coordinate(IMAGE));
		final long[] maxInterval = round(this.cropMax.coordinate(IMAGE));

		final RandomAccessibleInterval<UnsignedByteType> crop1 = Views.rotate(img, 1, 0);
		final RandomAccessibleInterval<UnsignedByteType> crop2 = Views.permute(crop1, 1, 2);
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
		final RandomAccessible<FloatType> extendHeightfield = ExtendHeightField.extendHeightfield(
				heightfield,
				heightfieldAvg,
				heightfieldPlane,
				scale.transformDistance(FULL_RESOLUTION, HEIGHTFIELD, 0, fadeToPlaneDist),
				scale.transformDistance(FULL_RESOLUTION, HEIGHTFIELD, 0, fadeToAvgDist));


		// --------------------------------------------------------------------
		// flatten crop with expanded heightfield
		// --------------------------------------------------------------------
		final double[] hfRelativeScale = new double[3];
		Arrays.setAll(hfRelativeScale, d -> scale.scaleFactor(HEIGHTFIELD, IMAGE, d));
		final RealRandomAccessible<DoubleType> scaledHeightfield = Transform.scaleAndShiftHeightFieldAndValues(extendHeightfield, hfRelativeScale);
		final double minZ = scale.transformCoordinate(HEIGHTFIELD, IMAGE, 2, heightfieldAvg);
		final double maxZ = scale.transformCoordinate(FULL_RESOLUTION, IMAGE, 0, this.cropMax.coordinate(FULL_RESOLUTION, 0) - minModifiedX);
		final double fade = scale.transformDistance(FULL_RESOLUTION, IMAGE, 0, fadeFlattenToIdentityDist);
		final RealTransform flatten = ExtendFlattenTransform.extendFlattenTransform(scaledHeightfield, minZ, maxZ, fade);


		// --------------------------------------------------------------------
		// fade position field to identity
		// --------------------------------------------------------------------
		final RealTransform transition =
				new ClippedTransitionRealTransform(
						positionField.getTransform(imgLevel),
						IdentityTransform.get(),
						minZ,
						maxZ);


		// --------------------------------------------------------------------
		// transform back to original image coordinates
		// --------------------------------------------------------------------
		final AffineTransform3D uncrop = new AffineTransform3D();
		uncrop.set(
				0, 1, 0, translation[0],
				0, 0, 1, translation[1],
				-1, 0, 0, translation[2]);


		// --------------------------------------------------------------------
		// concatenate flattening and position field transform
		// --------------------------------------------------------------------
		final RealTransformSequence tfseq = new RealTransformSequence();
		tfseq.add(uncrop);
		tfseq.add(transition);
		tfseq.add(flatten);


		unwarpedCrop = new RealTransformRealRandomAccessible<>(
				Views.interpolate(
						Views.extendValue(crop, new UnsignedByteType()),
						new ClampingNLinearInterpolatorFactory<>()),
				tfseq);


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

	public RealRandomAccessible<DoubleType> getAbsDisplacement() {
		return absDisplacement;
	}

	public RealRandomAccessible<UnsignedByteType> getUnwarpedCrop() {
		return unwarpedCrop;
	}

	private static long[] round(double[] pos) {
		final long[] spos = new long[pos.length];
		Arrays.setAll(spos, d -> Math.round(pos[d]));
		return spos;
	}




	public static class Scale {

		private final double[] imgDownsamplingFactors;
		private final double[] heightfieldDownsamplingFactors;

		public Scale(
				final int imgLevel,
				final double[] heightfieldDownsamplingFactors) {
			this.imgDownsamplingFactors = new double[] {1 << imgLevel, 1 << imgLevel, 1 << imgLevel};
			this.heightfieldDownsamplingFactors = heightfieldDownsamplingFactors;
		}

		public enum System {
			FULL_RESOLUTION,
			IMAGE,
			HEIGHTFIELD
		}

		public Scale.Coordinate image(final double... pos) {
			return new Scale.Coordinate(IMAGE, pos);
		}

		public Scale.Coordinate heightfield(final double... pos) {
			return new Scale.Coordinate(HEIGHTFIELD, pos);
		}

		public Scale.Coordinate fullres(final double... pos) {
			return new Scale.Coordinate(FULL_RESOLUTION, pos);
		}

		public Scale.Coordinate fullres(final long... pos) {
			final double[] dpos = new double[pos.length];
			Arrays.setAll(dpos, d -> pos[d]);
			return new Scale.Coordinate(FULL_RESOLUTION, dpos);
		}

		public class Coordinate {

			private final Scale.System system;
			private final double[] pos;

			public Coordinate(final Scale.System system, final double[] pos) {
				this.system = system;
				this.pos = pos;
			}

			public double coordinate(final Scale.System toSystem, final int d) {
				return (pos[d] + 0.5) * scaleFactor(this.system, toSystem, d) - 0.5;
			}

			public double[] coordinate(final Scale.System toSystem) {
				final double[] coordinate = new double[pos.length];
				Arrays.setAll(coordinate, d -> coordinate(toSystem, d));
				return coordinate;
			}

			public double distance(final Scale.System toSystem, final int d) {
				return pos[d] * scaleFactor(this.system, toSystem, d);
			}

			public double[] distance(final Scale.System toSystem) {
				final double[] distance = new double[pos.length];
				Arrays.setAll(distance, d -> distance(toSystem, d));
				return distance;
			}
		}

		private double toFullRes(final Scale.System system, final int d) {
			switch (system) {
			case IMAGE:
				return imgDownsamplingFactors[d];
			case HEIGHTFIELD:
				return heightfieldDownsamplingFactors[d];
			case FULL_RESOLUTION:
			default:
				return 1.0;
			}
		}

		private double scaleFactor(final Scale.System fromSystem, final Scale.System toSystem, final int d) {
			return toFullRes(fromSystem, d) / toFullRes(toSystem, d);
		}

		public double transformCoordinate(final Scale.System fromSystem, final Scale.System toSystem, final int d, final double pos) {
			return (pos + 0.5) * scaleFactor(fromSystem, toSystem, d) - 0.5;
		}

		public double transformDistance(final Scale.System fromSystem, final Scale.System toSystem, final int d, final double distance) {
			return distance * scaleFactor(fromSystem, toSystem, d);
		}
	}
}
