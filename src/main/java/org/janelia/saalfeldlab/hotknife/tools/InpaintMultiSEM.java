package org.janelia.saalfeldlab.hotknife.tools;

import java.util.Arrays;
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

		final RayCaster rayCaster = new RayCaster(p2d, rnd);

		while (c.hasNext()) {
			final FloatType v = c.next();
			o.setPosition(c);

			if (!Float.isNaN(v.get())) {
				// no inpainting necessary
				o.get().set(v);
			} else {
				double weightSum = 0;
				double valueSum = 0;
				int nRays = 0;

				// interpolate value by casting rays in random directions and averaging (weighted by distances) the
				// values of the first non-masked pixel
				while (nRays < numOrientations) {
					nRays++;
					final RayCaster.Result result = rayCaster.castRandom(iR, img, c);
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

		final Interval interval = Intervals.createMinMax( 32313, 37000, 12, 33808, 37800, 16 );
		//final Interval interval = Intervals.createMinMax( 32313, 37000, 14, 33808, 37800, 14 );
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
		inpaint3d( Views.zeroMin( imgF ), Views.zeroMin( imgOut ));

		ImageJFunctions.show( imgF );
		ImageJFunctions.show( imgOut );
	}


	private static class RayCaster {
		private final Random random;
		private final double[] direction = new double[3];
		private final Result result = new Result();

		private final double ratioOf2dRays;

		/*
		 * Default constructor (true random rays in 3d).
		 */
		public RayCaster() {
			this(0.0);
		}

		/*
		 * @param ratioOf2dRays the ratio of 2d rays to 3d rays. If the number of dimensions is less than 3, this
		 * parameter is ignored.
		 */
		public RayCaster(final double ratioOf2dRays) {
			this(ratioOf2dRays, new Random());
		}

		/*
		 * @param ratioOf2dRays the ratio of 2d rays to 3d rays. If the number of dimensions is less than 3, this
		 * parameter is ignored.
		 * @param random the random number generator to use
		 */
		public RayCaster(double ratioOf2dRays, final Random random) {
			this.random = random;
			this.ratioOf2dRays = ratioOf2dRays;
		}

		/*
		 * Casts a random ray from the given position in the given direction until it hits a non-masked pixel or exits the
		 * image boundaries.
		 *
		 * @param img the real random access to the image
		 * @param interval the interval of the image
		 * @param position the position from which to cast the ray
		 * @return the result of the ray casting
		 */
		Result castRandom(final RealRandomAccess<FloatType> img, final Interval interval, final RealLocalizable position) {
			final int n = position.numDimensions();
			final int rayDimension = (n > 2 && random.nextDouble() < ratioOf2dRays) ? 2 : n;
			initializeRandomDirection(rayDimension);

			img.setPosition(position);
			long steps = 0;

			while(true) {
				img.move(direction);
				++steps;

				if (!isInside(img, interval)) {
					// the ray exited the image boundaries without hitting a non-masked pixel
					return null;
				}

				final float value = img.get().get();
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

		public static class Result {
			public double value = 0;
			public double distance = 0;
		}
	}
}
