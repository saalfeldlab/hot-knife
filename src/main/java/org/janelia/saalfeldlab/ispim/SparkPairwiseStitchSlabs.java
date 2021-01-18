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
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.MovingLeastSquaresTransform3;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import loci.formats.FormatException;
import mpicbg.models.AffineModel3D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel3D;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RealInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.iterator.ZeroMinIntervalIterator;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointParameters;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class SparkPairwiseStitchSlabs implements Callable<Void>, Serializable {

	private static final long serialVersionUID = -7726815061775332505L;

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

	public static Pair< ArrayList<PointMatch>, Double > alignAll(
			final ArrayList< Interval > blocks,
			final ArrayList<InterestPoint> pointsA,
			final ArrayList<InterestPoint> pointsB )
	{
		int blocksWithMatches = 0;
		int blocksWithoutMatches = 0;

		ArrayList< PointMatch > allMatches = new ArrayList<>();

		for ( final Interval block : blocks )
		{
			ArrayList< PointMatch > matches = align( pointsA, pointsB, block );

			if ( matches.size() > 0 )
				++blocksWithMatches;
			else
				++blocksWithoutMatches;

			// build two lookup trees for existing Interestpoints that were matched
			HashMap< InterestPoint, PointMatch > p1 = new HashMap<>();
			HashMap< InterestPoint, PointMatch > p2 = new HashMap<>();

			for ( final PointMatch pm : allMatches )
			{
				p1.put( (InterestPoint)pm.getP1(), pm );
				p2.put( (InterestPoint)pm.getP2(), pm );
			}
		
			int sameMatch = 0;
			int differentMatch = 0;
			int added = 0;

			for ( final PointMatch pm : matches )
			{
				InterestPoint ip1 = (InterestPoint)pm.getP1();
				InterestPoint ip2 = (InterestPoint)pm.getP2();
				
				if ( p1.containsKey( ip1 ) )
				{
					if ( ((InterestPoint)p1.get( ip1 ).getP2()).getId() == ip2.getId() )
						++sameMatch;
					else
						++differentMatch;
				}
				else if ( p2.containsKey( ip2 ) )
				{
					if ( ((InterestPoint)p2.get( ip2 ).getP1()).getId() == ip1.getId() )
						++sameMatch;
					else
						++differentMatch;
				}
				else
				{
					allMatches.add( pm );
					++added;
				}
			}

			System.out.println( "added: " + added  + " same: " + sameMatch + " different: " + differentMatch );
		}

		return new ValuePair<>( allMatches, (double)blocksWithMatches / (double)( blocksWithMatches + blocksWithoutMatches ) );
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
			final Interval interval ) throws IOException
	{
		Pair< ArrayList<InterestPoint>, N5Data > pairA = loadPoints( n5Path, idA, channelA, camA, metaA );
		Pair< ArrayList<InterestPoint>, N5Data > pairB = loadPoints( n5Path, idB, channelB, camB, metaB );

		align( pairA.getA(), pairB.getA(), interval );
	}

	public static ArrayList<PointMatch> align(
			final ArrayList<InterestPoint> pointsChA,
			final ArrayList<InterestPoint> pointsChB,
			final Interval interval )
	{
		final boolean fastMatching = false;
		final int numNeighbors = 3;
		final int redundancy = 1;
		final double ratioOfDistance = 5;
		final int numIterations = 10000;
		final double maxEpsilon = 5;
		final int minNumInliers = 20;

		// ICP
		final boolean doICP = true;
		final double maxDistanceICP = maxEpsilon;
		final int maxNumIterationsICP = 100;
		final int minNumInliersICP = 30;
		final int numIterationsICP = 10000;
		final double maxEpsilonICP = maxDistanceICP / 2.0;

		final ArrayList<PointMatch> matches = SparkPaiwiseAlignChannelsGeo.matchBlock(pointsChA, pointsChB, interval,
				interval, fastMatching, numNeighbors, redundancy, ratioOfDistance, numIterations, maxEpsilon,
				minNumInliers, doICP, maxDistanceICP, maxNumIterationsICP, minNumInliersICP, numIterationsICP,
				maxEpsilonICP);

		return matches;
	}

	public static Pair< ArrayList<InterestPoint>, N5Data > loadPoints(
			final String n5Path,
			final String id,
			final String channel,
			final String cam,
			final MetaData meta ) throws IOException
	{
		System.out.println( new Date(System.currentTimeMillis() ) + ": Opening N5." );

		final N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id );

		System.out.println( new Date(System.currentTimeMillis() ) + ": lastSliceIndexA=" + n5data.lastSliceIndex + " for " + id );

		ArrayList<InterestPoint> pointsCh = null;

		System.out.println( "loading points ... " );

		try
		{
			final String datasetNameA = id + "/" + channel + "/Stack-DoG-detections";
			final DatasetAttributes datasetAttributesA = n5data.n5.getDatasetAttributes(datasetNameA);

			pointsCh = n5data.n5.readSerializedBlock(datasetNameA, datasetAttributesA, new long[] {0});
		}
		catch ( Exception e ) // java.nio.file.NoSuchFileException
		{
			e.printStackTrace();
			System.out.println( new Date(System.currentTimeMillis() ) + ": Failed to load points for " + id );
			return null;
		}

		if ( meta != null )
		{
			System.out.println( "transforming points to stage coordinates ... " );
	
			ArrayList< InterestPoint > pointsChNew = new ArrayList<>();
			for ( final InterestPoint p : pointsCh )
			{
				final double[] l = p.getL().clone();
				l[ 0 ] += meta.position[ 0 ];
				l[ 1 ] += meta.position[ 1 ];
				l[ 2 ] += meta.position[ 2 ];
				pointsChNew.add( new InterestPoint( p.getId(), l ) );
			}
	
			return new ValuePair<>( pointsChNew, n5data );
		}
		else
		{
			return new ValuePair<>( pointsCh, n5data );
		}
	}

	public static ArrayList< Interval > findBlocks(
			final String n5Path,
			final String idA,
			final String idB,
			final MetaData metaA,
			final MetaData metaB,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final int[] blockSize ) throws IOException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		Pair< ArrayList<InterestPoint>, N5Data > pairA = loadPoints( n5Path, idA, channelA, camA, metaA );
		Pair< ArrayList<InterestPoint>, N5Data > pairB = loadPoints( n5Path, idB, channelB, camB, metaB );

		return findBlocks( pairA.getA(), pairB.getA(), blockSize );
	}

	public static ArrayList< Interval > findBlocks(
			final ArrayList<InterestPoint> pointsChA,
			final ArrayList<InterestPoint> pointsChB,
			final int[] blockSize ) throws IOException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		RealInterval bbA = realInterval( pointsChA );
		RealInterval bbB = realInterval( pointsChB );

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
			final long countA = containedPoints( block, pointsChA ).size();
			final long countB = containedPoints( block, pointsChB ).size();

			sumA += countA;
			sumB += countB;

			if ( countA > 10 && countB > 10 )
				intervals.add( block );
		}

		System.out.println( "numBlocks with detections: " + intervals.size() + "/" + ( numBlocks + numOverlappingBlocks ) + ", #detectionsA=" + sumA + ", #detectionsB=" + sumB );

		return intervals;
	}

	public static ArrayList<InterestPoint> containedPoints( final ArrayList< Interval > blocks, final List<InterestPoint> points )
	{
		final ArrayList<InterestPoint> containedPoints = new ArrayList<>();

		for ( final InterestPoint point : points )
		{
			boolean anyContains = false;

			for ( final Interval block : blocks )
			{
				if ( contains( point, block ) )
				{
					anyContains = true;
					break;
				}
			}

			if ( anyContains )
				containedPoints.add( point );
		}

		return containedPoints;
	}

	// TODO: more efficient
	public static ArrayList<InterestPoint> containedPoints( final Interval block, List<InterestPoint> points )
	{
		final ArrayList<InterestPoint> containedPoints = new ArrayList<>();

		for ( final InterestPoint point : points )
			if ( contains( point, block ) )
				containedPoints.add( point );

		return containedPoints;
	}

	protected static boolean contains( final InterestPoint point, final Interval block )
	{
		boolean isOutside = false;

		for ( int d = 0; d < point.numDimensions() && !isOutside; ++d )
		{
			final double p = point.getL()[ d ];

			if ( p < block.min( d ) || p > block.max( d ) )
				isOutside = true;
		}

		return !isOutside;
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

	public static ArrayList<PointMatch> matchICP(
			List<InterestPoint> pointsChAIn,
			List<InterestPoint> pointsChBIn,
			final Model< ? > model,
			final double maxDistance,
			final int maxIterations )
	{
		//final Model< ? > model = new AffineModel3D();
		//final double maxDistance = 1.0;
		//final int maxIterations = 100;

		ArrayList<InterestPoint> pointsChA = new ArrayList<InterestPoint>();
		ArrayList<InterestPoint> pointsChB = new ArrayList<InterestPoint>();

		for ( final InterestPoint ip : pointsChAIn )
			pointsChA.add( ip.duplicate() );

		for ( final InterestPoint ip : pointsChBIn )
			pointsChB.add( ip.duplicate() );

		IterativeClosestPointPairwise<InterestPoint> icp =
				new IterativeClosestPointPairwise<>(
						new IterativeClosestPointParameters( model, maxDistance, maxIterations ) );

		// not enough points to build a descriptor
		if ( pointsChA.size() < model.getMinNumMatches() || pointsChB.size() < model.getMinNumMatches() )
			return null;

		final ArrayList< PointMatch > matches = new ArrayList<>(
				icp.match( pointsChA, pointsChB ).getInliers().stream().map( v -> (PointMatch)v ).collect( Collectors.toList() ) );

		return matches;
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

	public static void visualizeDetections(
			final String n5Path,
			final String id,
			final String channel,
			final String cam,
			final AffineGet transform,
			final List<InterestPoint> points ) throws FormatException, IOException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id );

		BdvStackSource<?> bdv = null;

		bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channel, cam, n5data.stacks.get( channel ).get( cam ), n5data.alignments.get( channel ), n5data.camTransforms.get( channel ).get( cam ), transform, 0, n5data.lastSliceIndex );
		bdv = BdvFunctions.show( SparkPaiwiseAlignChannelsGeo.renderPoints( points ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "detections", new BdvOptions().addTo( bdv ) );
	}

	public static void visualizeAlignment(
			final String n5Path,
			final String idA,
			final String channelA,
			final String camA,
			final AffineTransform3D transformA,
			final String idB,
			final String channelB,
			final String camB,
			final AffineTransform3D transformB,
			final List<InterestPoint> pointsB ) throws FormatException, IOException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final N5Data n5dataA = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, idA );
		final N5Data n5dataB = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, idB );

		BdvStackSource<?> bdv = null;

		bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channelA, camA, n5dataA.stacks.get( channelA ).get( camA ), n5dataA.alignments.get( channelA ), n5dataA.camTransforms.get( channelA ).get( camA ), transformA, 0, n5dataA.lastSliceIndex );
		bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channelB, camB, n5dataB.stacks.get( channelB ).get( camB ), n5dataB.alignments.get( channelB ), n5dataB.camTransforms.get( channelB ).get( camB ), transformB, 0, n5dataB.lastSliceIndex );
	
		if ( pointsB != null )
			bdv = BdvFunctions.show( SparkPaiwiseAlignChannelsGeo.renderPoints( pointsB ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "detections", new BdvOptions().addTo( bdv ) );
	}

	public static ArrayList<PointMatch> globalICP(
			ArrayList<PointMatch> matchesTmp,
			final ArrayList<InterestPoint> pointsA,
			final ArrayList<InterestPoint> pointsB,
			final ArrayList< Interval > blocks ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		System.out.println( "Total matches:" + matchesTmp.size() );

		TranslationModel3D translation = new TranslationModel3D();
		translation.fit( matchesTmp );
		SparkPaiwiseAlignChannelsGeo.error(matchesTmp, translation );

		AffineModel3D affine = new AffineModel3D();
		affine.fit( matchesTmp );
		SparkPaiwiseAlignChannelsGeo.error(matchesTmp, affine );

		MovingLeastSquaresTransform3 mls = new MovingLeastSquaresTransform3(); // cuts of correspondences if weight is too small
		mls.setModel( new AffineModel3D() );
		mls.setMatches( matchesTmp );
		SparkPaiwiseAlignChannelsGeo.error( matchesTmp, mls );

		List< InterestPoint > pointsChATmp = containedPoints( blocks, pointsA );
		System.out.println( "Deforming " + pointsChATmp.size() + " ChA points " );

		List< InterestPoint > pointsChANew = new ArrayList<InterestPoint>();
		for ( final InterestPoint p : pointsChATmp )
		{
			final double[] l = p.getL().clone();
			mls.applyInPlace( l );
			pointsChANew.add( new InterestPoint( p.getId(), l ) );
		}

		// Total matches:10723 -- 1.0
		// Total matches:11918 -- 2.0
		final Model< ? > model = new AffineModel3D();
		final double maxDistance = 2.0;
		final int maxIterations = 100;

		matchesTmp = matchICP( pointsChANew, pointsB, model, maxDistance, maxIterations );
		System.out.println( "Total matches:" + matchesTmp.size() );

		// fix matches back to use non-deformed points!
		System.out.println( "Restoring ChA points " );

		HashMap<Integer, InterestPoint > lookUpA = new HashMap<>();
		for ( final InterestPoint p : pointsA )
			lookUpA.put( p.getId(), p );

		ArrayList<PointMatch> matches = new ArrayList<PointMatch>();

		for ( final PointMatch pm : matchesTmp )
			matches.add(
					new PointMatch(
							lookUpA.get( ((InterestPoint)pm.getP1()).getId() ),
							(InterestPoint)pm.getP2() ) );

		return matches;
	}

	public static void writeMatches(
			final ArrayList<PointMatch> matches,
			final String n5Path,
			final String idA,
			final String idB,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB ) throws IOException
	{
		// TODO: locations include metadata, remove to be consistent

		// write matches
		System.out.println( "saving matches for " + idA + "<>" + idB + " ... " );

		if ( matches.size() > 0 )
		{
			final N5FSWriter n5Writer = new N5FSWriter(n5Path);

			final String datasetName = idA + "/matches_" + idA + "_" + channelA + "_" + camA + "__" + idB + "_" + channelB + "_" + camB;

			if (n5Writer.exists(datasetName))
				n5Writer.remove(datasetName);
			
			n5Writer.createDataset(
					datasetName,
					new long[] {1},
					new int[] {1},
					DataType.OBJECT,
					new GzipCompression());

			final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);

			n5Writer.writeSerializedBlock(
					matches,
					datasetName,
					datasetAttributes,
					new long[] {0});
		}

		System.out.println( "saved" + idA + "<>" + idB );
	}

	@Override
	public Void call() throws IOException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException, ClassNotFoundException
	{
		System.out.println( idA + " <> " + idB );

		final HashMap< String, MetaData > meta = readPositionMetaData( positionFile );

		Pair< ArrayList<InterestPoint>, N5Data > pairA = loadPoints( n5Path, idA, channelA, camA, meta.get( idA ) );
		Pair< ArrayList<InterestPoint>, N5Data > pairB = loadPoints( n5Path, idB, channelB, camB, meta.get( idB ) );

		final ArrayList< Interval > blocks =
				findBlocks( pairA.getA(), pairB.getA(), new int[] { 600, 600, 200 } );

		Pair< ArrayList<PointMatch>, Double > resultTmp = alignAll( blocks, pairA.getA(), pairB.getA() );

		System.out.println( resultTmp.getA().size() + " matches ratio of blocks with matches=" + resultTmp.getB() );

		// do ICP on non-rigidly transformed points
		final ArrayList<PointMatch> matches = globalICP(resultTmp.getA(), pairA.getA(), pairB.getA(), blocks);

		// TODO: points are shifted by the metadata derived translation, so we transform the input images into the global space
		AffineTransform3D metaTransformA = new AffineTransform3D();
		metaTransformA.translate( meta.get( idA ).position[ 0 ], meta.get( idA ).position[ 1 ], meta.get( idA ).position[ 2 ] );
		AffineTransform3D metaTransformB = new AffineTransform3D();
		metaTransformB.translate( meta.get( idB ).position[ 0 ], meta.get( idB ).position[ 1 ], meta.get( idB ).position[ 2 ] );

		// visualize
		visualizeDetections(
				n5Path, idB, channelB, camB,
				metaTransformB,
				matches.stream().map( pm -> ((InterestPoint)pm.getP2()) ).collect( Collectors.toList() ) );

		final AffineModel3D affine = new AffineModel3D();
		affine.fit( matches );
		metaTransformA = metaTransformA.preConcatenate( TransformationTools.getAffineTransform( affine ) );

		visualizeAlignment(
				n5Path,
				idA, channelA, camA, metaTransformA,
				idB, channelB, camB, metaTransformB, matches.stream().map( pm -> ((InterestPoint)pm.getP2()) ).collect( Collectors.toList() ) );

		// TODO: write matches
		return null;
	}

}
