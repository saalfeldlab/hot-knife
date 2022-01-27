package net.imglib2.blk.view;

import java.lang.reflect.Field;
import net.imagej.ImgPlus;
import net.imglib2.RandomAccessible;
import net.imglib2.blk.copy.Extension;
import net.imglib2.converter.Converter;
import net.imglib2.converter.read.ConvertedRandomAccessible;
import net.imglib2.converter.read.ConvertedRandomAccessibleInterval;
import net.imglib2.img.ImgView;
import net.imglib2.img.NativeImg;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.cell.AbstractCellImg;
import net.imglib2.img.planar.PlanarImg;
import net.imglib2.outofbounds.OutOfBoundsBorderFactory;
import net.imglib2.outofbounds.OutOfBoundsConstantValueFactory;
import net.imglib2.outofbounds.OutOfBoundsFactory;
import net.imglib2.outofbounds.OutOfBoundsMirrorFactory;
import net.imglib2.transform.integer.Mixed;
import net.imglib2.transform.integer.MixedTransform;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.IntervalView;
import net.imglib2.view.MixedTransformView;

import static net.imglib2.blk.copy.Extension.BORDER;
import static net.imglib2.blk.copy.Extension.CONSTANT;
import static net.imglib2.blk.copy.Extension.MIRROR_DOUBLE;
import static net.imglib2.blk.copy.Extension.MIRROR_SINGLE;

public class ViewProps
{
	NativeImg< ?, ? > img;
	Extension extension = BORDER;
	Object oobValue;
	Converter< ?, ? > converter;
	MixedTransform transform;

	public ViewProps( final RandomAccessible< ? > original )
	{
		final int n = original.numDimensions();
		transform = new MixedTransform( n, n );
		analyze( original );
		if ( !isSupported( transform ) )
			throw new IllegalArgumentException( "aggregated transform is not supported: " + transform );
	}

	private void transform( final Mixed transform )
	{
		this.transform = this.transform.preConcatenate( transform );
	}

	private void analyze( final RandomAccessible< ? > view )
	{
		if ( view instanceof MixedTransformView )
		{
			final MixedTransformView< ? > mixed = ( MixedTransformView< ? > ) view;
			transform( mixed.getTransformToSource() );
			analyze( mixed.getSource() );
		}
		else if ( view instanceof ConvertedRandomAccessible )
		{
			final ConvertedRandomAccessible< ?, ? > converted = ( ConvertedRandomAccessible< ?, ? > ) view;
			if ( converter != null )
				throw new IllegalArgumentException( "Cannot handle more than one converter" );
			converter = converted.getConverter();
			analyze( converted.getSource() );
		}
		else if ( view instanceof ConvertedRandomAccessibleInterval )
		{
			final ConvertedRandomAccessibleInterval< ?, ? > converted = ( ConvertedRandomAccessibleInterval< ?, ? > ) view;
			if ( converter != null )
				throw new IllegalArgumentException( "Cannot handle more than one converter" );
			converter = converted.getConverter();
			analyze( converted.getSource() );
		}
		else if ( view instanceof ExtendedRandomAccessibleInterval )
		{
			ExtendedRandomAccessibleInterval< ?, ? > extended = ( ExtendedRandomAccessibleInterval< ?, ? > ) view;
			final OutOfBoundsFactory< ?, ? > oobFactory = extended.getOutOfBoundsFactory();
			if ( oobFactory instanceof OutOfBoundsBorderFactory )
			{
				extension = BORDER;
			}
			else if ( oobFactory instanceof OutOfBoundsMirrorFactory )
			{
				extension = getExtensionMethod( ( OutOfBoundsMirrorFactory< ?, ? > ) oobFactory );
			}
			else if ( oobFactory instanceof OutOfBoundsConstantValueFactory )
			{
				extension = CONSTANT;
				oobValue = ( ( OutOfBoundsConstantValueFactory ) oobFactory ).getValue();
			}
			else
			{
				throw new IllegalArgumentException( "Cannot handle OutOfBoundsFactory " + oobFactory.getClass() );
			}
			analyze( extended.getSource() );
		}
		else if ( view instanceof ImgPlus )
		{
			analyze( ( ( ImgPlus< ? > ) view ).getImg() );
		}
		else if ( view instanceof ImgView )
		{
			analyze( ( ( ImgView< ? > ) view ).getSource() );
		}
		else if ( view instanceof IntervalView )
		{
			analyze( ( ( IntervalView< ? > ) view ).getSource() );
		}
		else if ( view instanceof AbstractCellImg || view instanceof ArrayImg || view instanceof PlanarImg )
		{
			img = ( NativeImg< ?, ? > ) view;
		}
		else
			throw new IllegalArgumentException( "Cannot handle " + view );
	}

	// TODO: This is a hack. We should add in ImgLib2 OutOfBoundsMirrorFactor.getBoundary().
	private static Extension getExtensionMethod( final OutOfBoundsMirrorFactory< ?, ? > oobFactory )
	{
		try
		{
			final Field f = OutOfBoundsMirrorFactory.class.getDeclaredField( "boundary" );
			f.setAccessible( true );
			if ( ( ( OutOfBoundsMirrorFactory.Boundary ) f.get( oobFactory ) ) == OutOfBoundsMirrorFactory.Boundary.DOUBLE )
				return MIRROR_DOUBLE;
		}
		catch ( NoSuchFieldException | IllegalAccessException e )
		{
		}
		return MIRROR_SINGLE;
	}

	private static boolean isSupported( final Mixed t )
	{
		// We allow translation and slicing, but not axis permutation or inverting.
		final int n = t.numSourceDimensions();
		final int m = t.numTargetDimensions();
		if ( n > m )
			return false;
		for ( int d = 0; d < m; ++d )
			if ( t.getComponentInversion( d ) )
				return false;
		int sourceComponent = -1;
		for ( int d = 0; d < m; ++d )
		{
			if ( !t.getComponentZero( d ) )
			{
				final int s = t.getComponentMapping( d );
				if ( s <= sourceComponent )
					return false;
				sourceComponent = s;
			}
		}
		return true;
	}
}
