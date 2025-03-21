package org.janelia.saalfeldlab.hotknife.tools;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import ij.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.PositionFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

public class TestFieldLoading
{

	public static void run( final String group ) throws IOException
	{
		System.out.println( "\n" + group );

		final N5Reader n5 = new N5FSReader("/Users/preibischs/Downloads/msem-test.n5");

		final int showScaleIndex = 4;
		final double showScale = 1.0 / (1 << showScaleIndex);
		

		final double[] boundsMin = n5.getAttribute(group, "boundsMin", double[].class);
		final double[] boundsMax = n5.getAttribute(group, "boundsMax", double[].class);

		final String dataset = "flat_mask.s001_m239.bot4_clahe.face";

		//RealTransform transform = Transform.loadScaledTransform( n5, group + "/" + dataset );
		final double transformScale = n5.getAttribute(group + "/" + dataset, "scale", double.class);
		final RandomAccessibleInterval<DoubleType> positionField = N5Utils.open(n5, group + "/" + dataset);

		// note: transformation field for pass00-sofima is different by a constant 128 in the X plane
		// (identical to the result of the on the fly modification below)
		//ImageJFunctions.show( positionField ).setTitle( group );

		// TODO: copy into an ArrayImg 
		// on the fly modification (this is a hack, only works if the cached cells are kept in memory) 
		//IntervalView<DoubleType> positionFieldX = Views.hyperSlice( positionField, 2, 0 );
		//Views.iterable( positionFieldX ).forEach( v -> v.set( v.get() + 128 ) );

		final int n = positionField.numDimensions() - 1;
		final long[] translation = Arrays.copyOf(Grid.floorScaled(boundsMin, transformScale), n + 1);
		final PositionFieldTransform<DoubleType> pfTransform =
				Transform.createPositionFieldTransform(
						Views.translate(positionField, translation));
		final RealTransform transform = Transform.createScaledRealTransform(pfTransform, transformScale);

		System.out.println( "transformScale: " + transformScale );
		System.out.println( "translation: " + Arrays.toString( translation ) );

		final double[] l1 = new double[] { 2000, 3000 };
		final double[] l2 = new double[ 2 ];
		transform.apply(l1, l2);

		// sofima: [35301.037084840544, 59427.077371588675]
		// pass00: [31205.037084840547, 59427.077371588675]
		System.out.println( Arrays.toString( l1 ) + " maps to: " + Arrays.toString( l2 ) );

		final int scaleIndex = 4;

		final RandomAccessibleInterval<UnsignedByteType> source =
				Converters.convertRAI( (RandomAccessibleInterval<FloatType>)N5Utils.open(
						n5, "/flat_mask/s001_m239/bot4_clahe/face/s" + scaleIndex),
						(in,o) -> o.set( Math.round( in.get() )), new UnsignedByteType() );

		// note: sources are identical for both cases
		//ImageJFunctions.show( source ).setTitle( "source " + group );

		// sofima: source: [0, 0] -> [3315, 3261], dimensions (3316, 3262)
		// pass00: source: [0, 0] -> [3315, 3261], dimensions (3316, 3262)
		System.out.println( "source: " + Util.printInterval( source ) );

		final Interval interval = new FinalInterval(
				Grid.floorScaled(boundsMin, showScale),
				Grid.ceilScaled(boundsMax, showScale));

		// sofima: [-690, -724] -> [4145, 4002], dimensions (4836, 4727)
		// pass00: [-690, -724] -> [4145, 4002], dimensions (4836, 4727)
		System.out.println( "interval: " + Util.printInterval( interval ) );

		RandomAccessibleInterval<UnsignedByteType> rai = Transform.createTransformedInterval(
				source,
				interval,
				Transform.createScaledRealTransform(transform, scaleIndex),
				new UnsignedByteType(255));

		// sofima: [-690, -724] -> [4145, 4002], dimensions (4836, 4727)
		// pass00: [-690, -724] -> [4145, 4002], dimensions (4836, 4727)
		System.out.println( "rai: " + Util.printInterval( rai ) );

		// so, what the hack is going on when I now make a randomaccess???
		//RandomAccess<UnsignedByteType> ra1 = rai.randomAccess();
		RealTransformRandomAccessible<UnsignedByteType, ?>.RealTransformRandomAccess ra =
				(RealTransformRandomAccessible<UnsignedByteType, ?>.RealTransformRandomAccess)rai.randomAccess();

		// TODO: solution, we need to pre-concatenate!!! (i.e. transform the constant 128 offset)
		ra.setPosition( new long[] { 3120 + rai.min( 0 ), 1920 + rai.min( 1 ) } );

		// note: apply(); in RealTransformRandomAccess.get() gives the same value in both cases
		// sofima: [2430.0, 1196.0] >> [1054.2288091916444, 1477.0132572053976]
		// pass00: [2430.0, 1196.0] >> [798.2288091916444, 1477.0132572053976]
		UnsignedByteType v = ra.get();
		int value = v.get();

		// sofima: Pixel value at [3120, 1920]: 55
		// pass00: Pixel value at [3120, 1920]: 159
		System.out.println( "Pixel value at [3120, 1920]: " + value );

		ImageJFunctions.show( rai, Executors.newFixedThreadPool( 36 ) );
	}

	public static void main( String[] args ) throws IOException
	{
		new ImageJ();

		run( "run_20240517_200443/pass00-sofima" );
		run( "run_20240517_200443/pass00" );
	}
}
