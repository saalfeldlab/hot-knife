package org.janelia.saalfeldlab.ispim.imglib2.st.render;

import org.janelia.saalfeldlab.ispim.imglib2.st.filter.Filter;
import org.janelia.saalfeldlab.ispim.imglib2.st.filter.RadiusSearchFilterFactory;
import org.janelia.saalfeldlab.ispim.imglib2.st.render.util.SimpleSampler;

import net.imglib2.KDTree;
import net.imglib2.RealLocalizable;
import net.imglib2.Sampler;


public class FilteredMaxDistanceSearchOnKDTree< S, T > implements IntegratingNeighborSearch< T >
{
	protected final int n;
	final KDTree< S > tree;
	final SimpleSampler< T > value;
	final RadiusSearchFilterFactory< S, T > filterFactory;
	final Filter< T > filter;

	public FilteredMaxDistanceSearchOnKDTree(
			final KDTree< S > tree,
			final RadiusSearchFilterFactory< S, T > filterFactory )
	{
		this.n = tree.numDimensions();
		this.tree = tree;
		this.value = new SimpleSampler<>( filterFactory.create() );
		this.filterFactory = filterFactory;
		this.filter = filterFactory.createFilter( tree );
	}

	@Override
	public void search( final RealLocalizable p )
	{
		filter.filter( p, value.get() );
	}

	@Override
	public Sampler< T > getSampler()
	{
		return value;
	}

	@Override
	public int numDimensions()
	{
		return n;
	}

	@Override
	public FilteredMaxDistanceSearchOnKDTree< S, T > copy()
	{
		return new FilteredMaxDistanceSearchOnKDTree< S, T >( tree, filterFactory );
	}
}
