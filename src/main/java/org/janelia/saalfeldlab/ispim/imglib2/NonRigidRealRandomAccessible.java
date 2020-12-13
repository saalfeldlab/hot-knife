package org.janelia.saalfeldlab.ispim.imglib2;

import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.NumericAffineModel3D;

public class NonRigidRealRandomAccessible< T > implements RealRandomAccessible<T>
{
	final RealRandomAccessible< NumericAffineModel3D > grid; // e.g. ModelGrid
	final RealRandomAccessible< T > imageData;

	public NonRigidRealRandomAccessible(
			final RealRandomAccessible< NumericAffineModel3D > grid,
			final RealRandomAccessible< T > imageData )
	{
		this.grid = grid;
		this.imageData = imageData;
	}

	@Override
	public int numDimensions() { return grid.numDimensions(); }

	@Override
	public RealRandomAccess<T> realRandomAccess() { return new NonRigidRealRandomAccess<>(grid, imageData); }

	@Override
	public RealRandomAccess<T> realRandomAccess(RealInterval interval) { return realRandomAccess(); }
}
