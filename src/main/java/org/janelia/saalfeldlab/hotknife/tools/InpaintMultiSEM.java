package org.janelia.saalfeldlab.hotknife.tools;

import java.util.Random;

import org.janelia.saalfeldlab.hotknife.SparkSurfaceFit;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealRandomAccess;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.view.Views;

public class InpaintMultiSEM
{
	public static boolean isInside( final RealLocalizable p, final Interval r )
	{
		for ( int d = 0; d < p.numDimensions(); ++d )
		{
			final double l = p.getDoublePosition( d );

			if ( l < r.min( d ) || l > r.max( d ) )
				return false;
		}

		return true;
	}

	public static void inpaint3d( final RandomAccessibleInterval< FloatType > img, final RandomAccessibleInterval< FloatType > out )
	{
		final Cursor<FloatType> c = Views.iterable( img ).localizingCursor();
		final RandomAccess< FloatType > o = out.randomAccess();

		// random rays being shot out
		final int numOrientations = 256;
		final double p2d = 0.1;
		final Random rnd = new Random( 89656 );

		final RealRandomAccess< FloatType > iR = Views.interpolate( Views.extendBorder( img ), new NLinearInterpolatorFactory<>() ).realRandomAccess();

		final RayCaster rayCaster = new RayCaster(iR, p2d, rnd);

		while (c.hasNext()) {
			final FloatType v = c.next();
			o.setPosition(c);

			if (!Float.isNaN(v.get())) {
				// pixel has content, no inpainting necessary
				o.get().set(v);
			} else if (!rayCaster.isInPseudoConvexHull(c, img)) {
				// pixel is not in the interior of the image content, no inpainting necessary
				o.get().setZero();
			} else {
				double weightSum = 0;
				double valueSum = 0;
				int nRays = 0;

				// interpolate value by casting rays in random directions and averaging (weighted by distances) the
				// values of the first non-masked pixel
				while (nRays < numOrientations) {
					nRays++;
					final RayCaster.Result result = rayCaster.cast(img, c, RayCaster.Direction.RANDOM);
					if (result != null) {
						final double weight = 1.0 / result.distance;
						weightSum += weight;
						valueSum += result.value * weight;
					}
				}

				final double value = valueSum / weightSum;
				o.get().setReal(value);
			}
		}
	}

	public static void main( String[] args )
	{
		final String n5Path = "/nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5";
		final String maskDataset = "/render/slab_000_to_009/s006_m167_align_no35_horiz_avgshd_ic___mask_20240504_144727/s0";
		final String imageDataset = "/render/slab_000_to_009/s006_m167_align_no35_horiz_avgshd_ic___20240504_085007_norm-layer-clahe/s0";

		final N5Reader n5 = new N5FSReader(n5Path);
		final RandomAccessibleInterval< UnsignedByteType > maskRaw = N5Utils.open(n5, maskDataset);
		final RandomAccessibleInterval< UnsignedByteType > imgRaw = N5Utils.open(n5, imageDataset);

		//final Interval interval = Intervals.createMinMax( 32313, 37000, 12, 33808, 37800, 16 );
		//final Interval interval = Intervals.createMinMax( 32313, 37000, 14, 33808, 37800, 14 );
		final Interval interval = Intervals.createMinMax(35700, 43600, 14, 36900, 44400, 14);
		//final Interval interval = Intervals.createMinMax( 32313, 37000, 14, 32540, 37194, 14 );

		new ImageJ();

		System.out.println("Showing images...");
		final RandomAccessibleInterval<UnsignedByteType> img = Views.interval( imgRaw , interval );
		final RandomAccessibleInterval<UnsignedByteType> mask = Views.interval( maskRaw , interval );

		ImageJFunctions.show( img );
		ImageJFunctions.show( mask );

		System.out.println("Converting to float...");
		final RandomAccessibleInterval< FloatType > imgF = Views.translate( ArrayImgs.floats( img.dimensionsAsLongArray() ), img.minAsLongArray() );
		Util.copy( Converters.convert( img, (a,b) -> b.set( a.get() ), new FloatType() ), imgF );

		final RandomAccessibleInterval< FloatType > imgOut = Views.translate( ArrayImgs.floats( img.dimensionsAsLongArray() ), img.minAsLongArray() );

		System.out.println("Mask slice...");
		SparkSurfaceFit.maskSlice(imgF, mask, new FloatType(Float.NaN));

		System.out.println("Inpainting...");
		final long start = System.currentTimeMillis();
		inpaint3d( Views.zeroMin( imgF ), Views.zeroMin( imgOut ));
		System.out.println("Inpainting took " + (System.currentTimeMillis() - start) + "ms");

		ImageJFunctions.show( imgF );
		ImageJFunctions.show( imgOut );
	}


	private static class RayCaster {
		private final double[] direction = new double[3];
		private final Result result = new Result();

		private static final RayCaster.Direction[] directionsToTry = new RayCaster.Direction[] {
				RayCaster.Direction.UP,
				RayCaster.Direction.DOWN,
				RayCaster.Direction.LEFT,
				RayCaster.Direction.RIGHT
		};

		private final RealRandomAccess<FloatType> image;
		private final double ratioOf2dRays;
		private final Random random;

		/*
		 * @param image the image to cast rays into
		 * @param ratioOf2dRays the ratio of 2d rays to 3d rays. If the number of dimensions is less than 3, this
		 * 		  parameter is ignored.
		 * @param random the random number generator to use
		 */
		public RayCaster(final RealRandomAccess<FloatType> image,
						 final double ratioOf2dRays,
						 final Random random) {
			this.image = image;
			this.ratioOf2dRays = ratioOf2dRays;
			this.random = random;
		}

		/*
		 * Casts a ray from the given position in the given direction until it hits a non-masked (i.e., non-NaN) pixel
		 * or exits the image boundary.
		 *
		 * @param interval the interval in which the ray search is performed
		 * @param position the position from which to cast the ray
		 * @param direction the direction in which to cast the ray
		 * @return the result of the ray casting or null if the ray exited the image boundary without hitting a
		 * 		   non-NaN pixel
		 */
		Result cast(final Interval interval, final RealLocalizable position, final Direction rayDirection) {
			final int n = position.numDimensions();
			final int rayDimension = (n > 2 && random.nextDouble() < ratioOf2dRays) ? 2 : n;

			if (rayDirection == Direction.RANDOM) {
				initializeRandomDirection(rayDimension);
			} else {
				initializeWithGivenDirection(rayDirection);
			}

			image.setPosition(position);
			long steps = 0;

			while(true) {
				image.move(direction);
				++steps;

				if (!isInside(image, interval)) {
					// the ray exited the image boundaries without hitting a non-masked pixel
					return null;
				}

				final float value = image.get().get();
				if (!Float.isNaN(value)) {
					// the ray reached the end of the mask
					result.value = value;
					result.distance = steps;
					return result;
				}

			}
		}

		private void initializeRandomDirection(int dim) {
			for (int d = 0; d < dim; ++d) {
				direction[d] = random.nextDouble() * (random.nextBoolean() ? -1 : 1);
			}
			for (int d = dim; d < 3; ++d) {
				direction[d] = 0;
			}

			LinAlgHelpers.normalize(direction);
		}

		private void initializeWithGivenDirection(Direction rayDirection) {
			direction[0] = rayDirection.get()[0];
			direction[1] = rayDirection.get()[1];
			direction[2] = rayDirection.get()[2];
		}

		/*
		 * Checks if the given position is in the pseudo-convex hull of the image content. The pseudo-convex hull is
		 * defined as the set of all points from which at least three rays in +/-x and +/-y directions hit the image
		 * content. This is taking advantage of the fact that the image content is made up from multiple almost
		 * axes-parallel rectangles.
		 *
		 * @param c the position to check
		 * @param interval the interval in which to check
		 * @return true if the position is in the pseudo-convex hull of the image content, false otherwise
		 */
		public boolean isInPseudoConvexHull(final Cursor<FloatType> c, final Interval interval) {
			int hits = 0;
			int misses = 0;
			for (RayCaster.Direction direction : directionsToTry) {
				final RayCaster.Result result = cast(interval, c, direction);
				if (result != null) {
					hits++;
				} else {
					misses++;
				}

				if (misses > 1) {
					return false;
				} else if (hits > 2) {
					return true;
				}
			}
			return false;
		}

		public static class Result {
			public double value = 0;
			public double distance = 0;
		}

		public enum Direction {
			UP(new double[] { 0, 1, 0 }),
			DOWN(new double[] { 0, -1, 0 }),
			LEFT(new double[] { -1, 0, 0 }),
			RIGHT(new double[] { 1, 0, 0 }),
			RANDOM(new double[] { 0, 0, 0 });

			final private double[] vector;

			Direction(final double[] vector) {
				this.vector = vector;
			}

			public double[] get() {
				return vector;
			}
		}
	}
}
