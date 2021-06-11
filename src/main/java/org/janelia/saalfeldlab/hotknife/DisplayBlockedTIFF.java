package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;

import org.janelia.saalfeldlab.hotknife.SparkConvertBlockedTiffSeriestoN5.BFCellLoader;
import org.janelia.saalfeldlab.hotknife.SparkConvertBlockedTiffSeriestoN5.MetaData;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.volatiles.VolatileViews;
import loci.formats.FormatException;
import net.imglib2.FinalInterval;
import net.imglib2.algorithm.lazy.Lazy;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.type.NativeType;
import net.imglib2.util.Util;

public class DisplayBlockedTIFF {

	public static < T extends NativeType< T > > void displayFile( final MetaData<T> metaData, final int ... blockSizeIn )
	{
		System.out.println( "Size: " + Util.printCoordinates( metaData.dim() ) );

		final int[] blockSize;

		if ( blockSizeIn.length != metaData.dim().length )
		{
			blockSize = new int[ metaData.dim().length ];
			for ( int d = 0; d < blockSize.length; ++d )
				blockSize[ d ] = blockSizeIn[ blockSizeIn.length - 1 ];
			for ( int d = 0; d < blockSizeIn.length; ++d )
				blockSize[ d ] = blockSizeIn[ d ];
		}
		else
		{
			blockSize = blockSizeIn;
		}

		final CachedCellImg<T, ?> img = Lazy.generate(
				new FinalInterval( metaData.dim() ),
				blockSize,
				metaData.type(),
				AccessFlags.setOf( AccessFlags.VOLATILE ),
				new BFCellLoader<>( metaData ) );

		BdvOptions options = BdvOptions.options().numRenderingThreads( Runtime.getRuntime().availableProcessors() );

		if ( metaData.dim.length == 2 )
			options = options.is2D();

		BdvFunctions.show( VolatileViews.wrapAsVolatile( img ), "cellopen", options );
	}

	@SuppressWarnings("unchecked")
	public static void main( String[] args ) throws FormatException, IOException
	{
		final String input = "/Users/spreibi/Downloads/mosaic_DAPI_z0.tif";
		//final String input = "/Users/spreibi/Documents/BIMSB/Publications/radialsymmetry/N2_1639_cropped_3974 (low SNR)_ch0.tif";

		displayFile( (MetaData)(Object)SparkConvertBlockedTiffSeriestoN5.openAndParseMetaData( input ), 4096 );
	}

}
