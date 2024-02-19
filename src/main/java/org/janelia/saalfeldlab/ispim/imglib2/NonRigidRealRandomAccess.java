package org.janelia.saalfeldlab.ispim.imglib2;

import mpicbg.models.AffineModel3D;
import net.imglib2.Localizable;
import net.imglib2.RealLocalizable;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.type.Type;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.NumericAffineModel3D;

public class NonRigidRealRandomAccess<T> implements RealRandomAccess<T>
{
	final RealRandomAccessible< NumericAffineModel3D > grid;
	final RealRandomAccessible< T > img;

	final RealRandomAccess< NumericAffineModel3D > interpolatedModel;
	final RealRandomAccess< T > imgAccess;

	final int n;
	final double[] l, tmp;

	public NonRigidRealRandomAccess( final RealRandomAccessible< NumericAffineModel3D > grid, final RealRandomAccessible< T > img )
	{
		this.grid = grid;
		this.img = img;

		this.interpolatedModel = grid.realRandomAccess();
		this.imgAccess = img.realRandomAccess();

		this.n = grid.numDimensions();
		this.l = new double[ n ];
		this.tmp = new double[ n ];
		type = (T)( (Type)img.getAt( 0, 0, 0 )).copy();
	}

	final double[] m = new double[ 12 ];
	final T type;

	@Override
	public T get()
	{
		interpolatedModel.setPosition( l );

		final AffineModel3D model = interpolatedModel.get().getModel();

		tmp[ 0 ] = l[ 0 ];
		tmp[ 1 ] = l[ 1 ];
		tmp[ 2 ] = l[ 2 ];
		model.applyInPlace( tmp );

		/*
		((RealType)type).setReal( Math.sqrt( (tmp[0] - l[0])*(tmp[0] - l[0]) + (tmp[1] - l[1])*(tmp[1] - l[1]) + (tmp[2] - l[2])*(tmp[2] - l[2]) ) );
		
		return type;
		*/
		imgAccess.setPosition( tmp );
		return imgAccess.get();
	}

	@Override
	public double getDoublePosition( final int d ) { return l[ d ]; }

	@Override
	public int numDimensions() { return n; }

	@Override
	public void move( final float distance, final int d ) { l[ d ] += distance; }

	@Override
	public void move( final double distance, final int d ) { l[ d ] += distance; }

	@Override
	public void move( final RealLocalizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += localizable.getFloatPosition( d );
	}

	@Override
	public void move( final float[] distance )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += distance[ d ];
	}

	@Override
	public void move( final double[] distance )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += distance[ d ];
	}

	@Override
	public void setPosition( final RealLocalizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] = localizable.getFloatPosition( d );
	}

	@Override
	public void setPosition( final float[] position )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] = position[ d ];
	}

	@Override
	public void setPosition( final double[] position )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] =(float)position[ d ];
	}

	@Override
	public void setPosition( final float position, final int d ) { l[ d ] = position; }

	@Override
	public void setPosition( final double position, final int d ) { l[ d ] = (float)position; }

	@Override
	public void fwd( final int d ) { ++l[ d ]; }

	@Override
	public void bck( final int d ) { --l[ d ]; }

	@Override
	public void move( final int distance, final int d ) { l[ d ] += distance; }

	@Override
	public void move( final long distance, final int d ) { l[ d ] += distance; }

	@Override
	public void move( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += localizable.getFloatPosition( d );
	}

	@Override
	public void move( final int[] distance )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += distance[ d ];
	}

	@Override
	public void move( final long[] distance )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] += distance[ d ];
	}

	@Override
	public void setPosition( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] = localizable.getFloatPosition( d );
	}

	@Override
	public void setPosition( final int[] position )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] = position[ d ];
	}

	@Override
	public void setPosition( final long[] position )
	{
		for ( int d = 0; d < n; ++d )
			l[ d ] = position[ d ];
	}

	@Override
	public void setPosition( final int position, final int d ) { l[ d ] = position; }

	@Override
	public void setPosition( final long position, final int d ) { l[ d ] = position; }

	@Override
	public RealRandomAccess<T> copy() {
		NonRigidRealRandomAccess<T> r = new NonRigidRealRandomAccess<T>(grid, img);
		r.setPosition(this);
		return r;
	}
}
