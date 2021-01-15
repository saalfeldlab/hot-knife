package org.janelia.saalfeldlab.ispim;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;
import org.janelia.saalfeldlab.n5.DatasetAttributes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import loci.formats.FormatException;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.KDTree;
import net.imglib2.RealInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.iterator.ZeroMinIntervalIterator;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class SparkPairwiseStitchSlabs implements Callable<Void>, Serializable {

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--positionFile", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5/m24o.edited.pos.json")
	private String positionFile = null;

	@Option(names = "--idA", required = true, description = "Stack key A, e.g. Pos012")
	private String idA = null;

	@Option(names = "--idB", required = true, description = "Stack key B, e.g. Pos013")
	private String idB = null;

	@Option(names = "--channelA", required = true, description = "Channel A key, e.g. Ch488+561+647nm")
	private String channelA = null;

	@Option(names = "--channelB", required = true, description = "Channel B key, e.g. Ch405nm")
	private String channelB = null;

	@Option(names = "--camA", required = true, description = "CamA key, e.g. cam1")
	private String camA = null;

	@Option(names = "--camB", required = true, description = "CamB key, e.g. cam1")
	private String camB = null;

	@Option(names = {"-b", "--blocksize"}, required = false, description = "blocksize in z for point extraction (default: 20)")
	private int blocksize = 50;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new SparkPairwiseStitchSlabs()).execute(args);
	}

	public static void align(
			final String n5Path,
			final String idA,
			final String idB,
			final MetaData metaA,
			final MetaData metaB,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final int[] blockSize,
			final boolean doICP ) throws IOException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		System.out.println( new Date(System.currentTimeMillis() ) + ": Opening N5." );

		final N5Data n5dataA = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, idA );
		final N5Data n5dataB = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, idB );

		System.out.println( new Date(System.currentTimeMillis() ) + ": lastSliceIndexA=" + n5dataA.lastSliceIndex + " for " + idA );
		System.out.println( new Date(System.currentTimeMillis() ) + ": lastSliceIndexB=" + n5dataB.lastSliceIndex + " for " + idB );

		ArrayList<InterestPoint> pointsChA = null;
		ArrayList<InterestPoint> pointsChB = null;

		System.out.println( "loading points ... " );

		try
		{
			final String datasetNameA = idA + "/" + channelA + "/Stack-DoG-detections";
			final DatasetAttributes datasetAttributesA = n5dataA.n5.getDatasetAttributes(datasetNameA);

			final String datasetNameB = idB + "/" + channelB + "/Stack-DoG-detections";
			final DatasetAttributes datasetAttributesB = n5dataB.n5.getDatasetAttributes(datasetNameB);

			pointsChA = n5dataA.n5.readSerializedBlock(datasetNameA, datasetAttributesA, new long[] {0});
			pointsChB = n5dataB.n5.readSerializedBlock(datasetNameB, datasetAttributesB, new long[] {0});
		}
		catch ( Exception e ) // java.nio.file.NoSuchFileException
		{
			e.printStackTrace();
			System.out.println( new Date(System.currentTimeMillis() ) + ": Failed to load points for " + idA + " <> " + idB );
			return;
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": channelA: " + pointsChA.size() + " points for " + idA );
		System.out.println( new Date(System.currentTimeMillis() ) + ": channelB: " + pointsChB.size() + " points for " + idB );

		RealInterval bbA = realInterval( pointsChA );
		RealInterval bbB = realInterval( pointsChB );

		System.out.println( Intervals.toString( bbA ) );
		System.out.println( Intervals.toString( bbB ) );

		System.out.println( "transforming points to stage coordinates ... " );

		List< InterestPoint > pointsChANew = new ArrayList<InterestPoint>();
		for ( final InterestPoint p : pointsChA )
		{
			final double[] l = p.getL().clone();
			l[ 0 ] += metaA.position[ 0 ];
			l[ 1 ] += metaA.position[ 1 ];
			l[ 2 ] += metaA.position[ 2 ];
			pointsChANew.add( new InterestPoint( p.getId(), l ) );
		}

		List< InterestPoint > pointsChBNew = new ArrayList<InterestPoint>();
		for ( final InterestPoint p : pointsChB )
		{
			final double[] l = p.getL().clone();
			l[ 0 ] += metaB.position[ 0 ];
			l[ 1 ] += metaB.position[ 1 ];
			l[ 2 ] += metaB.position[ 2 ];
			pointsChBNew.add( new InterestPoint( p.getId(), l ) );
		}

		bbA = realInterval( pointsChANew );
		bbB = realInterval( pointsChBNew );

		System.out.println( Intervals.toString( bbA ) );
		System.out.println( Intervals.toString( bbB ) );

		RealInterval overlap = Intervals.intersect( bbA, bbB );

		double[] size = size( overlap );

		System.out.println( "overlap: " + Intervals.toString( overlap ) + ", size=" + Util.printCoordinates( size ) );

		// now expand till it is a multiple of the blockSize, but at least ...
		Interval interval = expandToFit( overlap, blockSize, new double[] { 40, 40, 10 } );

		System.out.println( "final interval for testing: " + Util.printInterval( interval ) );

		// creating blocks for testing
		final int n = interval.numDimensions();
		final long[] blockIndicies = new long[ n ];

		// only the not 50%overlapping blocks
		long numBlocks = 1;
		long numOverlappingBlocks = 1;

		for ( int d = 0; d < n; ++d )
		{
			blockIndicies[ d ] = interval.dimension( d ) / blockSize[ d ];
			numBlocks *= blockIndicies[ d ];
			numOverlappingBlocks *= blockIndicies[ d ] - 1;
		}

		System.out.println( "blocks: " + Util.printCoordinates( blockIndicies ) );
		System.out.println( "numBlocks: " + numBlocks );
		System.out.println( "numOverlappingBlocks: " + numOverlappingBlocks );
		System.out.println( "total numBlocks: " + ( numBlocks + numOverlappingBlocks ) );

		final ArrayList< Interval > tmpIntervals = new ArrayList<>();
		final ZeroMinIntervalIterator i = new ZeroMinIntervalIterator( blockIndicies );

		while ( i.hasNext() )
		{
			i.fwd();

			long[] min = new long[ n ];
			long[] max = new long[ n ];

			for ( int d = 0; d < n; ++d )
			{
				min[ d ] = i.getIntPosition( d ) * blockSize[ d ] + interval.min( d );
				max[ d ] = min[ d ] + blockSize[ d ] - 1;
			}

			tmpIntervals.add( new FinalInterval( min, max ) );

			// add the 50% overlapping intervals if we are not the last block in any dimension
			boolean isLast = false;
	
			for ( int d = 0; d < n; ++d )
				if ( i.getIntPosition( d ) == blockIndicies[ d ] - 1 )
					isLast = true;

			if ( !isLast )
			{
				min = new long[ n ];
				max = new long[ n ];

				for ( int d = 0; d < n; ++d )
				{
					min[ d ] = i.getIntPosition( d ) * blockSize[ d ] + interval.min( d ) + blockSize[ d ] / 2;
					max[ d ] = min[ d ] + blockSize[ d ] - 1;
				}

				tmpIntervals.add( new FinalInterval( min, max ) );
			}
		}

		// check which blocks actually contain points in both images
		long sumA = 0;
		long sumB = 0;

		final ArrayList< Interval > intervals = new ArrayList<>();

		for ( final Interval block : tmpIntervals )
		{
			final long countA = numPoints( block, pointsChANew );
			final long countB = numPoints( block, pointsChBNew );

			sumA += countA;
			sumB += countB;

			if ( countA > 10 && countB > 10 )
			{
				intervals.add( block );
				System.out.println( Util.printInterval( block ) + ": " + countA + " -- " + countB );
			}
		}

		System.out.println( "numBlocks with detections: " + intervals.size() + "/" + ( numBlocks + numOverlappingBlocks ) + ", #detectionsA=" + sumA + ", #detectionsB=" + sumB );
	}


	// TODO: more efficient
	public static long numPoints( final Interval block, List<InterestPoint> points )
	{
		final int n = block.numDimensions();
		long count = 0;

		for ( final InterestPoint point : points )
		{
			boolean isOutside = false;

			for ( int d = 0; d < n && !isOutside; ++d )
			{
				final double p = point.getL()[ d ];

				if ( p < block.min( d ) || p > block.max( d ) )
					isOutside = true;
			}

			if ( !isOutside )
				++count;
		}

		return count;
	}

	public static Interval expandToFit( final RealInterval interval, final int[] blockSize, final double[] minExpansion )
	{
		final double[] size = size( interval );

		final int n = size.length;

		final long[] min = new long[ n ];
		final long[] max = new long[ n ];

		for ( int d = 0; d < n; ++d )
		{
			final double factor = ( size[ d ] / blockSize[ d ] );

			long factorL = Math.round( Math.floor( factor ) ) + 1;

			while ( factorL * blockSize[ d ] - size[ d ] < minExpansion[ d ] * 2 )
				++factorL;
	
			final double expansion = ( factorL * blockSize[ d ] - size[ d ] - 1 ) / 2.0;

			min[ d ] = Math.round( interval.realMin( d ) - expansion );
			max[ d ] = Math.round( interval.realMax( d ) + expansion );
		}

		return new FinalInterval(min, max);
	}

	public static double[] size( final RealInterval interval )
	{
		final double[] size = new double[ interval.numDimensions() ];
		for ( int d = 0; d < interval.numDimensions(); ++d )
			size[ d ] = interval.realMax( d ) - interval.realMin( d );
		return size;
	}

	public static FinalRealInterval expand( final RealInterval interval, final double[] border )
	{
		final int n = interval.numDimensions();
		final double[] min = new double[ n ];
		final double[] max = new double[ n ];
		interval.realMin(min);
		interval.realMax(max);
		for ( int d = 0; d < n; ++d )
		{
			min[ d ] -= border[ d ];
			max[ d ] += border[ d ];
		}
		return new FinalRealInterval( min, max );
	}

	public static RealInterval realInterval( Collection< ? extends RealLocalizable > points )
	{
		final int n = points.iterator().next().numDimensions();

		double[] min = new double[ n ];
		double[] max = new double[ n ];

		for ( int d = 0; d < n; ++d )
		{
			min[ d ] = Double.MAX_VALUE;
			max[ d ] = -Double.MAX_VALUE;
		}

		for ( final RealLocalizable l : points )
		{
			for ( int d = 0; d < n; ++d )
			{
				final double p = l.getDoublePosition( d );
				min[ d ] = Math.min( min[ d ], p );
				max[ d ] = Math.max( max[ d ], p );
			}
		}

		return new FinalRealInterval( min, max );
	}

	public static HashMap< String, MetaData > readPositionMetaData( final String positionFile ) throws FileNotFoundException
	{
		final GsonBuilder gsonBuilder = new GsonBuilder();
		final Gson gson = gsonBuilder.create();

		final JsonReader reader = new JsonReader( new FileReader( positionFile ) );

		List< MetaData > metaData = Arrays.asList( gson.fromJson( reader, MetaData[].class ) );

		HashMap< String, MetaData > metaDataMap = new HashMap<>();

		for ( final MetaData m : metaData )
		{
			final int index = m.file.indexOf( "pos0" );
			String pos = "P" + m.file.substring( index + 1, index + 6 );
			metaDataMap.put( pos, m );
		}

		return metaDataMap;
	}

	public static class MetaData
	{
		String type = "", file = "";
		int[] position, size;
		double[] pixelResolution;
		int index;
	}

	@Override
	public Void call() throws IOException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		System.out.println( idA + " <> " + idB );

		final HashMap< String, MetaData > meta = readPositionMetaData( positionFile );

		align( n5Path, idA, idB, meta.get( idA ), meta.get( idB ), channelA, channelB, camA, camB, new int[] { 400, 400, 100 }, true );

		return null;
	}

}
