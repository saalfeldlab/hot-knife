package org.janelia.saalfeldlab.ispim.imglib2.st.render;

import org.janelia.saalfeldlab.ispim.imglib2.st.render.util.SimpleRealLocalizable;
import org.janelia.saalfeldlab.ispim.imglib2.st.render.util.SimpleSampler;

import net.imglib2.KDTree;
import net.imglib2.RealLocalizable;
import net.imglib2.Sampler;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;

public class NearestNeighborMaxDistanceSearchOnKDTree< T > extends NearestNeighborSearchOnKDTree< T >
{
	final KDTree< T > kdTree;
	final T outofbounds;
	final SimpleSampler< T > oobsSampler;
	final double[] localPos;
	final SimpleRealLocalizable position;
	final double maxSqDistance, maxDistance;

	Sampler< T > value;
	RealLocalizable point;
	double newbestSquDistance;

	public NearestNeighborMaxDistanceSearchOnKDTree( final KDTree< T > tree, final T outofbounds, final double maxDistance )
	{
		super( tree );

		this.kdTree = tree;
		this.oobsSampler = new SimpleSampler< T >( outofbounds );
		this.localPos = new double[ tree.numDimensions() ];
		this.position = new SimpleRealLocalizable( localPos );
		this.maxDistance = maxDistance;
		this.maxSqDistance = maxDistance * maxDistance;
		this.outofbounds = outofbounds;
	}

	@Override
	public void search( final RealLocalizable p )
	{
		p.localize( localPos );
		super.search( p );

		if ( super.getSquareDistance() > maxSqDistance )
		{
			value = oobsSampler;
			point = position;
			newbestSquDistance = 0;
		}
		else
		{
			value = super.getSampler();
			point = super.getPosition();
			newbestSquDistance = super.getSquareDistance();
		}
	}

	@Override
	public Sampler< T > getSampler()
	{
		return value;
	}

	@Override
	public RealLocalizable getPosition()
	{
		return point;
	}

	@Override
	public double getSquareDistance()
	{
		return newbestSquDistance;
	}

	@Override
	public double getDistance()
	{
		return Math.sqrt( newbestSquDistance );
	}

	@Override
	public NearestNeighborMaxDistanceSearchOnKDTree< T > copy()
	{
		final NearestNeighborMaxDistanceSearchOnKDTree< T > copy = new NearestNeighborMaxDistanceSearchOnKDTree< T >( kdTree, outofbounds, maxDistance );
		System.arraycopy( localPos, 0, copy.localPos, 0, localPos.length );
		copy.newbestSquDistance = newbestSquDistance;
		copy.point = point;
		copy.value = value;
		return copy;
	}
}
