package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.hotknife.ops.GradientCenter;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.exception.ImgLibException;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.iterator.ZeroMinIntervalIterator;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import picocli.CommandLine.Option;

public class Cost implements Callable<Void>
{
	final FinalVoxelDimensions voxelDimensions = new FinalVoxelDimensions("px", new double[]{1, 1, 1});

	private boolean useVolatile = true;

	@Option(names = {"--i"}, required = false, description = "N5 file with min face, e.g. --i /nrs/flyem/render/n5/Z1217_19m/Sec04/stacks")
	private String rawN5 = "/nrs/flyem/tmp/VNC.n5";

	@Option(names = {"--d"}, required = false, description = "N5 dataset name, e.g. --d /v1_1_affine_filtered_1_26365___20191217_153959")
	private String datasetName = "/zcorr/Sec22___20200106_083252";

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException
	{
		//CommandLine.call(new Cost(), args);
		new Cost().call();
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException
	{
		new ImageJ();
		final MipMapData data = new MipMapData( new N5FSReader(rawN5), datasetName );

		final int scale = 5; //5: size: (372, 159, 253), t=3d-affine: (32.0, 0.0, 0.0, 15.5, 0.0, 32.0, 0.0, 15.5, 0.0, 0.0, 32.0, 15.5)

		final RandomAccessibleInterval< FloatType > mipmapF =
				Converters.convert(
						data.getMipmap( scale ),
						new RealFloatConverter< UnsignedByteType >(),
						new FloatType() );

		System.out.println( Util.printInterval( mipmapF ));
		final Img< FloatType > tmp = ArrayImgs.floats( data.dimensions()[ scale ] );

		cost( mipmapF, tmp, 1 );
		//Gauss3.gauss( new double[] {5,0,5}, Views.extendZero( mipmapF ), tmp );

		GradientCenter< FloatType > gc = new GradientCenter<>( Views.extendZero( tmp.copy() ), 1, 0.5 );
		gc.accept( tmp );

		//gc = new GradientCenter<>( Views.extendZero( tmp.copy() ), 1, 0.5 );
		//gc.accept( tmp );

		imp( tmp ).show();
		imp( mipmapF ).show();

		//gc.accept( output );
		//data.display( useVolatile );

		return null;
	}

	public static void cost( final RandomAccessibleInterval< FloatType > input, final RandomAccessibleInterval< FloatType > output, final int d )
	{
		final int numResin = 7; // pixels that are for sure in the resin

		final RandomAccess< FloatType > ra = input.randomAccess();
		final RandomAccess< FloatType > raOut = output.randomAccess();

		final long[] pos = new long[ 3 ];

		for ( int z = 0; z < input.dimension( 2 ); ++z )
		{
			System.out.println( "z: " + z );
			pos[ 2 ] = z;

			final ArrayList< Float > values = new ArrayList<>();

			for ( int x = 0; x < input.dimension(  0  ); ++x )
			{
				pos[ 0 ] = x;
				pos[ 1 ] = 0;

				ra.setPosition( pos );

				int count = 0;

				for ( int y = 0; y < input.dimension( d ); ++y )
				{
					final float v = ra.get().get();
					ra.fwd( d );

					if ( v == 0.0 )
						continue;

					if ( ++count == 1 ) // skip one pixel
						continue;

					if ( count >= numResin )
						break;

					values.add( v );
				}

				/*
				pos[ 0 ] = x;
				pos[ 1 ] = 0;
				ra.setPosition( pos );
				count = 0;

				for ( int y = (int)input.dimension( d ) - 1; y >= 0; --y )
				{
					final float v = ra.get().get();
					ra.bck( d );

					if ( v == 0.0 )
						continue;

					if ( ++count == 1 ) // skip one pixel
						continue;

					if ( count >= numResin )
						break;

					values.add( v );
				}*/
			}

			final double avg = avg( values );
			final double stDev = stDev( values, avg );

			System.out.println( "avg = " + avg + ", stDev = " + stDev );

			for ( int x = 0; x < input.dimension(  0  ); ++x )
			{
				pos[ 0 ] = x;
				pos[ 1 ] = 0;

				ra.setPosition( pos );
				int count = 0;

				float last = 0;

				for ( int y = 0; y < input.dimension( d )/2; ++y )
				{
					final float v = ra.get().get();
					ra.fwd( d );

					if ( v == 0.0 )
						continue;

					if ( ++count == 1 ) // skip one pixel
						continue;

					final double diff = avg - v;
					
					if ( diff > stDev/2 )
					{
						raOut.setPosition( ra );
						raOut.get().set( (float)( (diff/stDev) ) );
					}
				}
			}
		}

		/*
		final long[] dim = new long[ input.numDimensions() ];
		input.dimensions( dim );
		dim[ d ] = 1;


		ZeroMinIntervalIterator it = new ZeroMinIntervalIterator( dim );


		while ( it.hasNext() )
		{
			it.fwd();

			ra.setPosition( it );

			int count = 0;

			for ( int i = 0; i < input.dimension( d ) / 2; ++i )
			{
				final float v = ra.get().get();
				ra.fwd( d );

				if ( v == 0.0 )
					continue;

				if ( ++count == 1 ) // skip one pixel
					continue;

				if ( count >= numResin )
					break;

				values.add( v );
				//raOut.setPosition( ra );
				//raOut.get().setOne();
			}
		}

		final double avg = avg( values );
		final double stDev = stDev( values, avg );

		System.out.println( "avg = " + avg );
		System.out.println( "stDev = " + stDev );

		it = new ZeroMinIntervalIterator( dim );
		final RandomAccess< FloatType > raOut = output.randomAccess();

		while ( it.hasNext() )
		{
			it.fwd();

			ra.setPosition( it );

			int count = 0;

			for ( int i = 0; i < input.dimension( d ) / 2; ++i )
			{
				final float v = ra.get().get();
				ra.fwd( d );

				if ( v == 0.0 )
					continue;

				if ( ++count == 1 ) // skip one pixel
					continue;

				final double diff = avg - v;
				
				if ( diff > stDev )
				{
					raOut.setPosition( ra );
					raOut.get().set( (float)Math.pow( diff, 2) );
				}
			}
		}*/
	}

	public static double avg( final List< Float > values )
	{
		final double size = values.size();
		double avg = 0;

		for ( final double v : values )
			avg += v / size;

		return avg;
	}

	public static double stDev( final List< Float > values, final double avg )
	{
		double stDev = 0;
		
		for ( final double value : values )
			stDev += (avg - value) * (avg - value );
		
		stDev /= (double) values.size();
		
		return Math.sqrt( stDev );
	}

	public class MipMapData
	{
		final N5FSReader n5;
		final String datasetName;

		final int numScales;
		final double[][] mipmapScales;
		final long[][] dimensions;
		final AffineTransform3D[] mipmapTransforms;

		final RandomAccessibleInterval<UnsignedByteType>[] volatilemipmaps;
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps;

		@SuppressWarnings("unchecked")
		public MipMapData( final N5FSReader n5, final String datasetName ) throws IOException
		{
			this.n5 = n5;
			this.datasetName = datasetName;
			this.numScales = n5.list(datasetName).length;

			this.volatilemipmaps = new RandomAccessibleInterval[numScales];
			this.mipmaps = new RandomAccessibleInterval[numScales];

			this.mipmapScales = new double[numScales][];
			this.mipmapTransforms = new AffineTransform3D[numScales];
			this.dimensions = new long[numScales][];

			for (int s = 0; s < numScales; ++s)
			{
				final String datasetNameMipMap = datasetName + "/s" + s;

				final long[] dims = n5.getAttribute(datasetNameMipMap, "dimensions", long[].class);
				final long[] downsamplingFactors = n5.getAttribute(datasetNameMipMap, "downsamplingFactors", long[].class);
				final double[] scale = new double[dims.length];

				if (downsamplingFactors == null)
				{
					final int si = 1 << s;
					for (int i = 0; i < scale.length; ++i)
						scale[i] = si;
				}
				else
				{
					for (int i = 0; i < scale.length; ++i)
						scale[i] = downsamplingFactors[i];
				}

				mipmapScales[s] = scale;
				dimensions[s] = dims;

				final AffineTransform3D mipmapTransform = new AffineTransform3D();
				mipmapTransform.set(
						mipmapScales[ s ][ 0 ], 0, 0, 0.5 * ( mipmapScales[ s ][ 0 ] - 1 ),
						0, mipmapScales[ s ][ 1 ], 0, 0.5 * ( mipmapScales[ s ][ 1 ] - 1 ),
						0, 0, mipmapScales[ s ][ 2 ], 0.5 * ( mipmapScales[ s ][ 2 ] - 1 ) );
				mipmapTransforms[ s ] = mipmapTransform;

				//volatilemipmaps[s] = N5Utils.openVolatile( n5, datasetNameMipMap );
				//mipmaps[s] = N5Utils.open( n5, datasetNameMipMap );

				System.out.println( s + ": " +
						", scale: " + Util.printCoordinates( scale ) +
						", size: " + Util.printCoordinates( dims ) +
						", t=" + mipmapTransform);
			}
		}

		public int numScales() { return numScales; }
		public double[][] mipmapScales(){ return mipmapScales; }
		public long[][] dimensions(){ return dimensions; }
		public AffineTransform3D[] mipmapTransforms(){ return mipmapTransforms; }

		public RandomAccessibleInterval<UnsignedByteType> getMipmap( final int scale ) throws IOException
		{
			if ( mipmaps[scale] == null )
				mipmaps[scale] = N5Utils.open( n5, datasetName + "/s" + scale );

			return mipmaps[scale];
		}

		public RandomAccessibleInterval<UnsignedByteType> getVolatileMipmap( final int scale ) throws IOException
		{
			if ( volatilemipmaps[scale] == null )
				volatilemipmaps[scale] = N5Utils.openVolatile( n5, datasetName + "/s" + scale );

			return volatilemipmaps[scale];
		}

		public RandomAccessibleInterval<UnsignedByteType>[] openVolatileMipmaps() throws IOException
		{
			for (int s = 0; s < numScales; ++s)
				if ( volatilemipmaps[s] == null )
					volatilemipmaps[s] = N5Utils.openVolatile( n5, datasetName + "/s" + s );

			return volatilemipmaps;
		}

		public RandomAccessibleInterval<UnsignedByteType>[] openMipmaps() throws IOException
		{
			for (int s = 0; s < numScales; ++s)
				if ( mipmaps[s] == null )
					mipmaps[s] = N5Utils.open( n5, datasetName + "/s" + s );

			return mipmaps;
		}

		public BdvStackSource<?> display( final boolean useVolatile ) throws IOException
		{
			openVolatileMipmaps();

			final int numProc = Runtime.getRuntime().availableProcessors();
			final SharedQueue queue = new SharedQueue(Math.min(8, Math.max(1, numProc / 2)));

			BdvStackSource<?> bdv = null;

			final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
					new RandomAccessibleIntervalMipmapSource<>(
							volatilemipmaps,
							new UnsignedByteType(),
							mipmapScales,
							voxelDimensions,
							datasetName);

			final Source<?> volatileMipmapSource;

			if (useVolatile)
				volatileMipmapSource = mipmapSource.asVolatile(queue);
			else
				volatileMipmapSource = mipmapSource;

			bdv = Show.mipmapSource(volatileMipmapSource, bdv, BdvOptions.options()./*screenScales(new double[] {0.5}).*/numRenderingThreads(numProc));

			return bdv;
		}
	}

	final static ExecutorService service = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );

	public static < T extends RealType< T > & NativeType< T > > ImagePlus imp( final RandomAccessibleInterval< T > img )
	{
		return imp( img, true, img.toString(), Double.NaN, Double.NaN, service );
	}

	public static < T extends RealType< T > & NativeType< T > > ImagePlus imp(
			final RandomAccessibleInterval< T > img,
			final double min,
			final double max )
	{
		return imp( img, true, img.toString(), min, max, service );
	}

	@SuppressWarnings("unchecked")
	public static < T extends RealType< T > & NativeType< T > > ImagePlus imp(
			final RandomAccessibleInterval< T > img,
			final boolean virtualDisplay,
			final String title,
			final double min,
			final double max,
			final ExecutorService service )
	{
		ImagePlus imp = null;

		if ( img instanceof ImagePlusImg )
			try { imp = ((ImagePlusImg<T, ?>)img).getImagePlus(); } catch (ImgLibException e) {}

		if ( imp == null )
		{
			if ( virtualDisplay )
				imp = ImageJFunctions.wrap( img, title, service );
			else
				imp = ImageJFunctions.wrap( img, title, service ).duplicate();
		}

		final double[] minmax = getFusionMinMax( img, min, max );

		imp.setTitle( title );
		imp.setDimensions( 1, (int)img.dimension( 2 ), 1 );
		imp.setDisplayRange( minmax[ 0 ], minmax[ 1 ] );

		return imp;
	}

	public static < T extends RealType< T > > double[] getFusionMinMax(
			final RandomAccessibleInterval<T> img,
			final double min,
			final double max )
	{
		final double[] minmax;

		if ( Double.isNaN( min ) || Double.isNaN( max ) )
			minmax = minMaxApprox( img );
		else if ( min == 0 && max == 65535 )
		{
			// 16 bit input was assumed, little hack in case it was 8-bit
			minmax = minMaxApprox( img );
			if ( minmax[ 1 ] <= 255 )
			{
				minmax[ 0 ] = 0;
				minmax[ 1 ] = 255;
			}
		}
		else
			minmax = new double[]{ (float)min, (float)max };

		return minmax;
	}

	public static < T extends RealType< T > > double[] minMaxApprox( final RandomAccessibleInterval< T > img )
	{
		return minMaxApprox( img, 1000 );
	}
	
	public static < T extends RealType< T > > double[] minMaxApprox( final RandomAccessibleInterval< T > img, final int numPixels )
	{
		return minMaxApprox( img, new Random( 3535 ), numPixels );
	}

	public static < T extends RealType< T > > double[] minMaxApprox( final RandomAccessibleInterval< T > img, final Random rnd, final int numPixels )
	{
		final RandomAccess< T > ra = img.randomAccess();

		// run threads and combine results
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;

		for ( int i = 0; i < numPixels; ++i )
		{
			for ( int d = 0; d < img.numDimensions(); ++d )
				ra.setPosition( rnd.nextInt( (int)img.dimension( d ) ) + (int)img.min( d ), d );

			final double v = ra.get().getRealDouble();

			min = Math.min( min, v );
			max = Math.max( max, v );
		}

		return new double[]{ min, max };
	}
}

