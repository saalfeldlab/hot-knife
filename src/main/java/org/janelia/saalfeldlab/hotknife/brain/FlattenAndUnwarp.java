package org.janelia.saalfeldlab.hotknife.brain;

import java.util.Arrays;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.position.FunctionRealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ClippedTransitionRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.tobi.IdentityTransform;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.hotknife.util.Grid;
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
	private final RandomAccessibleInterval<UnsignedByteType> compositeUnwarpedCrop;
	private final RealRandomAccessible<UnsignedByteType> unwarpedCrop;
	private final RealRandomAccessible<DoubleType> absDisplacement;
	private final long[] vncTranslation;


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
	 * @param yshift
	 * 		TODO
	 * @param yshiftFadeInPlane
	 * 		TODO
	 * @param yshiftFadeOrtho
	 * 		TODO
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

			final PositionField positionField,

			final int yshift,
			final int yshiftFadeInPlane,
			final int yshiftFadeOrtho
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
		final TransformedPositionField transformedPositionField = new TransformedPositionField(
				positionField,
				imgLevel, new long[] {0, 0});
		final RealTransform transition =
				new ClippedTransitionRealTransform(
						transformedPositionField.getTransform(),
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


		final int iyshift = (int) scale.transformDistance(FULL_RESOLUTION, IMAGE, 0, yshift);
		final int iyshiftFadeInPlane = (int) scale.transformDistance(FULL_RESOLUTION, IMAGE, 0, yshiftFadeInPlane);
		final int iyshiftFadeOrtho = (int) scale.transformDistance(FULL_RESOLUTION, IMAGE, 0, yshiftFadeOrtho);
		final FinalInterval interval = Intervals.createMinSize(
				-iyshift, 0, (long) minZ,
				maxInterval[1] - minInterval[1] + 1,
				maxInterval[2] - minInterval[2] + 1,
				1);
		final double[] shift = {-iyshift, 0, 0};
		final double[] weights = {iyshiftFadeInPlane, iyshiftFadeInPlane, iyshiftFadeOrtho};
		final RealTransform intervalShift = new PlaneIntervalShift(2, interval, shift, weights);








		// --------------------------------------------------------------------
		// concatenate flattening and position field transform
		// --------------------------------------------------------------------
		final RealTransformSequence tfseq = new RealTransformSequence();
		tfseq.add(uncrop);
		if ( yshift != 0 )
			tfseq.add(intervalShift);
		tfseq.add(transition);
		tfseq.add(flatten);

		final RealTransformSequence tfseq2 = new RealTransformSequence();
		tfseq2.add(tfseq);
		tfseq2.add(uncrop.inverse());


		unwarpedCrop = new RealTransformRealRandomAccessible<>(
				Views.interpolate(
						Views.extendValue(crop, new UnsignedByteType()),
						new ClampingNLinearInterpolatorFactory<>()),
				tfseq);

		// --------------------------------------------------------------------
		// left of minModifiedX is the original data, right is the deformed one
		// --------------------------------------------------------------------
		final long minModifiedXScaled = Math.round(Math.floor( scale.transformCoordinate(FULL_RESOLUTION, IMAGE, 0, minModifiedX) ));

		compositeUnwarpedCrop = Views.interval(
				new FunctionRandomAccessible<>(
						3,
						() -> {
							final RandomAccess<UnsignedByteType> ba = Views.raster(unwarpedCrop).randomAccess();
							final RandomAccess<UnsignedByteType> ia = img.randomAccess();
							return (pos, type) -> {
								if ( pos.getDoublePosition( 0 ) < minModifiedXScaled ) {
									ia.setPosition(pos);
									type.set(ia.get());
									//type.set(Math.max(0, ia.get().get() - 50));
								} else {
									ba.setPosition(pos);
									type.set(ba.get());
								}
							};
						},
						UnsignedByteType::new),
				new FinalInterval( img ) );


		absDisplacement = new FunctionRealRandomAccessible<>(
				3,
				() -> {
					final double[] p1 = new double[3];
					final double[] p2 = new double[3];
					final RealTransform transform = tfseq2.copy();
					return (pos, t) -> {
						pos.localize(p1);
						transform.apply(p1, p2);
						t.set(LinAlgHelpers.distance(p1, p2));
					};
				},
				DoubleType::new);

//		final TransformedPositionField t2 = new TransformedPositionField(
//				positionField,
//				imgLevel, new long[] {minInterval[1], minInterval[2]});
//		System.out.println("t2.pfToImgX(0)                       = " + t2.pfToImgX(0));
//		System.out.println("transformedPositionField.pfToImgX(0) = " + transformedPositionField.pfToImgX(0));
//		System.out.println("minInterval[1] = " + minInterval[1]);
//		System.out.println("t2.pfToImgY(0)                       = " + t2.pfToImgY(0));
//		System.out.println("transformedPositionField.pfToImgY(0) = " + transformedPositionField.pfToImgY(0));
//		System.out.println("minInterval[2] = " + minInterval[2]);

		final long[] offset0 = Grid.floorScaled(positionField.getBoundsMin(), 1.0);
		vncTranslation = new long[] {
				(long) (this.cropMax.coordinate(IMAGE, 0) - minZ + 1),
				((cropMin[1] + offset0[0]) >> imgLevel) - iyshift,
				((cropMin[2] + offset0[1]) >> imgLevel)
		};
	}

	public RandomAccessibleInterval<UnsignedByteType> getCompositeUnwarpedCrop(){
		return compositeUnwarpedCrop;
	}

	public RealRandomAccessible<DoubleType> getAbsDisplacement() {
		return absDisplacement;
	}

	public RealRandomAccessible<UnsignedByteType> getUnwarpedCrop() {
		return unwarpedCrop;
	}

	public long[] getVncTranslation() {
		return vncTranslation;
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


	/**
	 * Transform positionfield that is relative to crop coordinates into
	 * positionfield that is relative to full image.
	 */
	public static class TransformedPositionField {

		private final double pfOffsetX;
		private final double pfOffsetY;
		private final double imgToPfScale;
		private final double pfToImgScale;
		private final double imgCropMinX;
		private final double imgCropMinY;

		// interpolated relative position field
		private final RealRandomAccessible<DoubleType> irpf;

		public double pfToImgX(final double pfX)
		{
			return (pfX + pfOffsetX)  * pfToImgScale + imgCropMinX;
		}

		public double pfToImgY(final double pfY)
		{
			return (pfY + pfOffsetY)  * pfToImgScale + imgCropMinY;
		}

		public double imgToPfX(final double imgX)
		{
			return (imgX - imgCropMinX) * imgToPfScale - pfOffsetX;
		}

		public double imgToPfY(final double imgY)
		{
			return (imgY - imgCropMinY) * imgToPfScale - pfOffsetY;
		}

		/**
		 * Transform positionfield that is relative to crop coordinates into
		 * positionfield that is relative to full image.
		 *
		 * @param positionField positionfield that is relative to crop coordinates
		 * @param imgLevel resolution level of the image
		 * @param imgCropMin
		 * 		crop coordinates at image resolution (not
		 * 		necessarily full resolution)
		 */
		public TransformedPositionField(
				final PositionField positionField,
				final int imgLevel,
				final long[] imgCropMin) {
			pfOffsetX = positionField.getOffset(0);
			pfOffsetY = positionField.getOffset(1);
			imgToPfScale = positionField.getScale() * (1 << imgLevel);
			pfToImgScale = 1.0 / imgToPfScale;
			imgCropMinX = imgCropMin[ 0 ];
			imgCropMinY = imgCropMin[ 1 ];

			final RandomAccessibleInterval<DoubleType> pf = positionField.getPositionFieldRAI();
			RandomAccessibleInterval<DoubleType> rpf = Views.interval(
					new FunctionRandomAccessible<>(
							pf.numDimensions(),
							() -> {
								final RandomAccess<DoubleType> input = pf.randomAccess();
								return (pos, value) -> {
									if (Intervals.contains(pf, pos)) {
										input.setPosition(pos);
										final int d = pos.getIntPosition(pos.numDimensions() - 1);
										value.set(input.get().get() - pos.getDoublePosition(d) - positionField.getOffset(d));
									} else {
										value.set(0);
									}
								};
							},
							DoubleType::new),
					pf);
			irpf = Views.interpolate(rpf, new NLinearInterpolatorFactory<>());
		}

		public RealTransform getTransform() {
			return new PFTransform();
		}

		class PFTransform implements RealTransform {

			private final double[] pf = new double[3];
			private final RealRandomAccess<DoubleType> rpfa = irpf.realRandomAccess();

			@Override
			public int numSourceDimensions() {
				return 2;
			}

			@Override
			public int numTargetDimensions() {
				return 2;
			}

			@Override
			public void apply(final double[] source, final double[] target) {
				apply(RealPoint.wrap(source), RealPoint.wrap(target));
			}

			@Override
			public void apply(final RealLocalizable source, final RealPositionable target) {
				final double imgX = source.getDoublePosition(0);
				final double imgY = source.getDoublePosition(1);
				pf[0] = imgToPfX(imgX);
				pf[1] = imgToPfY(imgY);
				rpfa.setPosition(pf);
				final double pfDiffX = rpfa.get().get();
				rpfa.setPosition(1, 2);
				final double pfDiffY = rpfa.get().get();
				target.setPosition(imgX + pfDiffX * pfToImgScale, 0);
				target.setPosition(imgY + pfDiffY * pfToImgScale, 1);
			}

			@Override
			public RealTransform copy() {
				return new PFTransform();
			}
		}
	}
}
