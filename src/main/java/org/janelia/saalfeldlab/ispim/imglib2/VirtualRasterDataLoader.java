package org.janelia.saalfeldlab.ispim.imglib2;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.janelia.saalfeldlab.ispim.Slice;
import org.janelia.saalfeldlab.ispim.ViewISPIMStack;

import bdv.viewer.Interpolation;
import loci.formats.FormatException;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

public class VirtualRasterDataLoader<T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>>
{
	public static AtomicInteger currentlyLoading = new AtomicInteger( 0 );

	final T type;
	final long[] globalMin;
	final int firstSliceIndex, lastSliceIndex; // global first and last slice of the stack
	final AffineTransform2D invCamtransform;
	final List< Slice > slices;
	final RandomAccessible<AffineTransform2D> alignmentTransforms;
	final RealInterval inputBounds;

	public VirtualRasterDataLoader(
			final RealInterval inputBounds,
			final List< Slice > slices,
			final RandomAccessible<AffineTransform2D> alignmentTransforms,
			final int firstSliceIndex,
			final int lastSliceIndex,
			final AffineTransform2D camtransform, // this is the inverse
			final long[] globalMin,
			final T type )
	{
		this.globalMin = globalMin;
		this.type = type;
		this.firstSliceIndex = firstSliceIndex;
		this.lastSliceIndex = lastSliceIndex;
		this.invCamtransform = camtransform.inverse();
		this.slices = slices;
		this.alignmentTransforms = alignmentTransforms;
		this.inputBounds = inputBounds;
	}

	@Override
	public void accept( final RandomAccessibleInterval<T> output )
	{
		try
		{
			final int numLoading = currentlyLoading.addAndGet( 1 );

			final long[] min = new long[ output.numDimensions() ];
			final long[] max = new long[ output.numDimensions() ];

			for ( int d = 0; d < min.length; ++d )
			{
				min[ d ] = globalMin[ d ] + output.min( d );
				max[ d ] = min[ d ] + output.dimension( d ) - 1;
			}

			final int myFirstSlice = Math.max( firstSliceIndex, (int)min[ 2 ] );
			final int myLastSlice = Math.min( lastSliceIndex, (int)max[ 2 ] );

			// test whether the block is outside the image area
			final RealInterval bounds = ViewISPIMStack.estimateStackBounds( inputBounds, slices, invCamtransform, alignmentTransforms, myFirstSlice, myLastSlice, true );

			if (
				max[ 2 ] < firstSliceIndex || min[ 2 ] > lastSliceIndex ||
				max[ 1 ] < bounds.realMin( 1 ) || min[ 1 ] > bounds.realMax( 1 ) ||
				max[ 0 ] < bounds.realMin( 0 ) || min[ 0 ] > bounds.realMax( 0 ) )
			{
				//System.out.println( "block: " + Util.printCoordinates( min ) + ">" + Util.printCoordinates( max ) + " is all black (" + currentlyLoading.addAndGet( -1 ) + " still loading)." );
				fillZero(output);
				return;
			}

			//System.out.println( "block: " + Util.printCoordinates( min ) + ">" + Util.printCoordinates( max ) + " is loading... (" + numLoading + " total)." );

			final Pair< RealRandomAccessible<UnsignedShortType>, Interval > data =
					ViewISPIMStack.prepareCamSource(
							slices,
							new UnsignedShortType(0),
							Interpolation.NLINEAR,
							invCamtransform, // pass the forward transform
							new AffineTransform3D(),
							alignmentTransforms,
							myFirstSlice,
							myLastSlice );

			// TODO: do we have to load +-1 for proper interpolation?
			final RandomAccessibleInterval< UnsignedShortType > img =
					Views.interval( Views.raster( data.getA() ), new FinalInterval( min, max ) );

			final Cursor< UnsignedShortType > in = Views.flatIterable( img ).cursor();
			final Cursor< T > out = Views.flatIterable( output ).cursor();

			while ( in.hasNext() )
			{
				final UnsignedShortType tIn = in.next();
				final T tOut = out.next();

				tOut.setReal( tIn.get() );
			}

			//System.out.println( "block: " + Util.printCoordinates( min ) + ">" + Util.printCoordinates( max ) + " loaded... (" + currentlyLoading.addAndGet( -1 ) + " still loading)." );
		}
		catch ( IOException | FormatException e )
		{
			e.printStackTrace();
		}
	}

	protected void fillZero( final RandomAccessibleInterval<T> output )
	{
		for ( final T t : Views.iterable( output ) )
			t.setZero();
	}
}
