package org.janelia.saalfeldlab.hotknife.cost;

import ij.plugin.filter.RankFilters;
import ij.process.ByteProcessor;
import mpicbg.ij.plugin.NormalizeLocalContrast;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;

public class PreFilter
{

	/**
	 * code for finding the bounding box of the current slice (where is no 0) and optional filtering with
	 * contrast adjustment and median
	 * 
	 * @param bp
	 * @param filter
	 * @return
	 */
	public static Interval filter( final ByteProcessor bp, final boolean filter )
	{
		//new ImagePlus( "in", new ByteProcessor( bp, false ) ).show();

		int minY1 = bp.getHeight();
		int maxY2 = 0;

		// find from > to for contrast adjustment
		for ( int x = 0; x < bp.getWidth(); ++x )
		{
			int y1 = -1;

			// stops on the first pixel that is not 0 anymore
			do
			{
				++y1;
			}
			while ( y1 < bp.getHeight() && bp.get( x, y1 ) == 0 );

			int y2 = bp.getHeight();

			// stops on the first pixel that is not 0 anymore
			do
			{
				--y2;
			}
			while ( y2 >= 0 && bp.get( x, y2 ) == 0 );

			// there is something...
			if ( y2 > y1 )
			{
				minY1 = Math.min( y1, minY1 );
				maxY2 = Math.max( y2, maxY2 );

				if ( filter )
				{
					ByteProcessor tmp = new ByteProcessor( 1, y2 - y1 + 1 );
	
					for ( int y = y1; y <= y2; ++y )
						tmp.set(0, y-y1, bp.get(x, y));
	
					NormalizeLocalContrast.run(tmp, 0, 300, 3.0f, true, true );
	
					for ( int y = y1; y <= y2; ++y )
						bp.set(x, y, tmp.get(0, y-y1));
				}
			}
		}

		//new ImagePlus( "contrast", new ByteProcessor( bp, false ) ).show();

		// median filter
		if ( filter )
			new RankFilters().rank( bp, 10, RankFilters.MEDIAN );

		//new ImagePlus( "median", new ByteProcessor( bp, false ) ).show();

		if ( maxY2 > minY1 )
		{
			return new FinalInterval( new long[] { 0, minY1}, new long[] { bp.getWidth() - 1, maxY2 } );
		}
		else
		{
			// completely empty
			return null;
		}
	}
}
