package org.janelia.saalfeldlab.hotknife.tools;

import java.util.function.Supplier;

import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.VolatileRandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileTypeMatcher;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;

public class RandomAccessibleIntervalMipmapSource2< T extends NumericType< T > > extends RandomAccessibleIntervalMipmapSource< T >
{
	protected final RandomAccessibleInterval< T >[] mipmapSources;

	protected final AffineTransform3D[] mipmapTransforms;

	protected final VoxelDimensions voxelDimensions;

	public RandomAccessibleIntervalMipmapSource2(
			final RandomAccessibleInterval< T >[] imgs,
			final T type,
			final double[][] mipmapScales,
			final VoxelDimensions voxelDimensions,
			final AffineTransform3D sourceTransform,
			final Translation3D[] offsetsAtOwnScale,
			final String name )
	{
		super( imgs, type, mipmapScales, voxelDimensions, sourceTransform, name );

		assert imgs.length == mipmapScales.length : "Number of mipmaps and scale factors do not match.";

		this.mipmapSources = imgs;
		this.mipmapTransforms = new AffineTransform3D[ mipmapScales.length ];
		for ( int s = 0; s < mipmapScales.length; ++s )
		{
			final AffineTransform3D mipmapTransform = new AffineTransform3D();
			mipmapTransform.setTranslation( offsetsAtOwnScale[ s ].getTranslationCopy() );

			final AffineTransform3D mipmapTransform2 = new AffineTransform3D();
			mipmapTransform2.set(
					mipmapScales[ s ][ 0 ], 0, 0, 0.5 * ( mipmapScales[ s ][ 0 ] - 1 ),
					0, mipmapScales[ s ][ 1 ], 0, 0.5 * ( mipmapScales[ s ][ 1 ] - 1 ),
					0, 0, mipmapScales[ s ][ 2 ], 0.5 * ( mipmapScales[ s ][ 2 ] - 1 ) );
			mipmapTransform.preConcatenate(mipmapTransform2);

			mipmapTransform.preConcatenate(sourceTransform);
			mipmapTransforms[ s ] = mipmapTransform;
		}

		this.voxelDimensions = voxelDimensions;
	}

	@Override
	public RandomAccessibleInterval< T > getSource( final int t, final int level )
	{
		return mipmapSources[ level ];
	}

	@Override
	public synchronized void getSourceTransform( final int t, final int level, final AffineTransform3D transform )
	{
		transform.set( mipmapTransforms[ level ] );
	}

	@Override
	public VoxelDimensions getVoxelDimensions()
	{
		return voxelDimensions;
	}

	@Override
	public int getNumMipmapLevels()
	{
		return mipmapSources.length;
	}

	public < V extends Volatile< T > & NumericType< V > > VolatileRandomAccessibleIntervalMipmapSource< T, V > asVolatile( final V vType, final SharedQueue queue )
	{
		return new VolatileRandomAccessibleIntervalMipmapSource<>( this, vType, queue );
	}

	public < V extends Volatile< T > & NumericType< V > > VolatileRandomAccessibleIntervalMipmapSource< T, V > asVolatile( final Supplier< V > vTypeSupplier, final SharedQueue queue )
	{
		return new VolatileRandomAccessibleIntervalMipmapSource<>( this, vTypeSupplier, queue );
	}

	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public < V extends Volatile< T > & NumericType< V > > VolatileRandomAccessibleIntervalMipmapSource< T, V > asVolatile( final SharedQueue queue )
	{
		final T t = getType();
		if ( t instanceof NativeType )
			return new VolatileRandomAccessibleIntervalMipmapSource<>( this, ( V )VolatileTypeMatcher.getVolatileTypeForType( ( NativeType )getType() ), queue );
		else
			throw new UnsupportedOperationException( "This method only works for sources of NativeType." );
	}
}
