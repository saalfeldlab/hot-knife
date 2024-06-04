package org.janelia.saalfeldlab.hotknife.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import org.janelia.saalfeldlab.hotknife.InpaintMasked;
import org.janelia.saalfeldlab.hotknife.SparkSurfaceFit;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import ij.ImageJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealRandomAccess;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.view.IntervalView;
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
		//final RandomAccess< FloatType > i = img.randomAccess();

		final int n = img.numDimensions();

		//final long[][] distances = new long[ n ][ 2 ];
		//final float[][] values = new float[ n ][ 2 ];

		// random rays being shot out
		final int numOrientations = 256;
		final double p2d = 0.1;
		final Random rnd = new Random( 89656 );

		final RealRandomAccess< FloatType > iR = Views.interpolate( Views.extendBorder( img ), new NLinearInterpolatorFactory<>() ).realRandomAccess();

		final ArrayList< Long > distances = new ArrayList<>( numOrientations );
		final ArrayList< Float > values = new ArrayList<>( numOrientations );

		final double[] vector = new double[ 3 ];

		while ( c.hasNext() )
		{
			final FloatType v = c.next();
			o.setPosition( c );

			if ( !Float.isNaN( v.get() ) )
			{
				// no inpainting necessary
				o.get().set( v );
			}
			else
			{
				//System.out.println( Arrays.toString( o.positionAsLongArray() ) );

				values.clear();
				distances.clear();

				do
				{
					// get a random direction
					Arrays.fill(vector, 0.0);

					// limit some to 2d rays
					final int m;

					if ( n > 2 && rnd.nextDouble() < p2d )
						m = 2;
					else
						m = n;

					for ( int d = 0; d < m; ++d )
						vector[ d ] = rnd.nextDouble() * ( rnd.nextBoolean() ? -1 : 1 );

					LinAlgHelpers.normalize( vector );

					iR.setPosition( c );
					long steps = 0;

					do
					{
						iR.move( vector );
						++steps;

						if ( !isInside( iR, img ) )
						{
							// exited the image boundaries
							break;
						}

						if ( !Float.isNaN( iR.get().get() ) )
						{
							// reached the end of the mask
							distances.add( steps );
							values.add( iR.get().get() );

							break;
						}

					} while ( true );
				} while ( distances.size() < numOrientations );

				//System.out.println( Arrays.toString( vector ) + " l=" + LinAlgHelpers.length(vector));

				// interpolate value
				double sum = 0;
				double sumV = 0;

				for ( int dir = 0; dir < values.size(); ++dir )
				{
					double dist = 1.0 / distances.get( dir );//Math.sqrt( Math.sqrt( distances.get( dir ) + 1 ) );
					//dist = Math.sqrt( dist );
					//System.out.println( " " + dist + " (" + distances.get( dir ) + ")");
					sum += dist;
					sumV += values.get( dir ) * dist;
				}

				//System.exit( 0 );
				final double value = sumV / sum ;

				o.get().setReal( value );


				/*
				// find the nearest pixels in all dim independently, interpolate
				for ( int d = 0; d < n; ++d )
				{
					// going up
					i.setPosition( c );

					for ( long l = c.getLongPosition( d ) - 1; l >= img.min( d ); --l )
					{
						i.setPosition( l, d );

						if ( !Float.isNaN( i.get().get() ) )
						{
							// reached the end
							distances[ d ][ 0 ] = c.getLongPosition( d ) - l;
							values[ d ][ 0 ] = i.get().get();

							break;
						}
					}

					// going down
					i.setPosition( c );

					for ( long l = c.getLongPosition( d ) + 1; l <= img.max( d ); ++l )
					{
						i.setPosition( l, d );

						if ( !Float.isNaN( i.get().get() ) )
						{
							// reached the end
							distances[ d ][ 1 ] = l - c.getLongPosition( d );
							values[ d ][ 1 ] = i.get().get();

							break;
						}
					}
				}

				// interpolate value
				double sum = 0;
				double sumV = 0;

				for ( int d = 0; d < n; ++d )
				{
					for ( int e = 0; e < distances[ d ].length; ++e )
					{
						if ( distances[ d ][ e ] > 0 )
						{
							double dist = 1.0 / distances[ d ][ e ];
							//dist = Math.sqrt( dist );
							//System.out.println( " " + d + ": " + dist + " (" + distances[d][e] + ")");
							sum += dist;
							sumV += values[ d ][ e ] * dist;
						}
					}
				}

				final double value = sumV / sum ;

				o.get().setReal( value );

				
				//System.out.println(" " + Arrays.deepToString( distances ));
				//System.out.println(" " + Arrays.deepToString( values ));
				//System.out.println(" " + value );

				//if ( c.getIntPosition( 0 ) == 160 && c.getIntPosition( 1 ) == 160 )
			//		SimpleMultiThreading.threadHaltUnClean();

				*/
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

		final RandomAccessibleInterval<UnsignedByteType> img = Views.interval( imgRaw , interval );
		final RandomAccessibleInterval<UnsignedByteType> mask = Views.interval( maskRaw , interval );

		ImageJFunctions.show( img );
		ImageJFunctions.show( mask );

		final RandomAccessibleInterval< FloatType > imgF = Views.translate( ArrayImgs.floats( img.dimensionsAsLongArray() ), img.minAsLongArray() );
		Util.copy( Converters.convert( img, (a,b) -> b.set( a.get() ), new FloatType() ), imgF );

		final RandomAccessibleInterval< FloatType > imgOut = Views.translate( ArrayImgs.floats( img.dimensionsAsLongArray() ), img.minAsLongArray() );

		SparkSurfaceFit.maskSlice(imgF, mask, new FloatType(Float.NaN));

		inpaint3d( Views.zeroMin( imgF ), Views.zeroMin( imgOut ));

		ImageJFunctions.show( imgF );
		ImageJFunctions.show( imgOut );
		/*
		// 2d code:
		
		final FloatProcessor fpSlice = Util.materialize( Views.hyperSlice( Views.zeroMin( imgF ), 2, 0 ) );

		final ArrayImg<FloatType, FloatArray> slice =
				ArrayImgs.floats(
						(float[])fpSlice.getPixels(),
						fpSlice.getWidth(),
						fpSlice.getHeight());
		
		SparkSurfaceFit.maskSlice(slice, mask, new FloatType(Float.NaN));

		
		InpaintMasked.run(fpSlice);

		new ImagePlus( "img", fpSlice ).show();
		*/
		/*
		return Views.translate(
				slice,
				Intervals.minAsLongArray(mask));*/
	}
}
