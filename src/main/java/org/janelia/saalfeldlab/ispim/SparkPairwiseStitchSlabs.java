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
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.hotknife.MultiConsensusFilter;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.MovingLeastSquaresTransform3;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;
import org.janelia.saalfeldlab.ispim.imglib2.NonRigidRealRandomAccessible;
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
import bdv.viewer.Interpolation;
import loci.formats.FormatException;
import mpicbg.models.AffineModel3D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel3D;
import mpicbg.models.Model;
import mpicbg.models.MovingLeastSquaresTransform;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel3D;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RealInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealRandomAccessible;
import net.imglib2.iterator.ZeroMinIntervalIterator;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.SimpleReferenceIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.NumericAffineModel3D;
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

	@Option(names = {"-b", "--blocksize"}, required = false, description = "blocksize for point extraction (default: 250, 250, 100)")
	private int[] blocksize = new int[]{ 2000, 2000, 1000 };

	/*
	 * cmdline params for tim's data
	 * 
	--n5Path=/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5
	--positionFile=/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5/m24o.edited.pos.json
	--idA=Pos002
	--idB=Pos005
	--channelA=Ch488+561+647nm
	--camA=cam1
	--channelB=Ch488+561+647nm
	--camB=cam1
	*/

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new SparkPairwiseStitchSlabs()).execute(args);
	}

	public static Pair< ArrayList<PointMatch>, Double > alignAllBlocks(
			final ArrayList< Interval > blocks,
			final TranslationModel3D blockATransform, // can be null
			final ArrayList<InterestPoint> pointsA,
			final ArrayList<InterestPoint> pointsB,
			final int minNumInliers, //12
			final Supplier matchingModel,
			final int minNumInliersICP, //20
			final Supplier icpModel ) throws NotEnoughDataPointsException
	{
		int blocksWithMatches = 0;
		int blocksWithoutMatches = 0;

		ArrayList< PointMatch > allMatches = new ArrayList<>();

		int i = 0;
		for ( final Interval block : blocks )
		{
			System.out.println( "aligning block " + (++i) + "/" + blocks.size() );

			final RealInterval blockA;

			if ( blockATransform == null )
			{
				blockA = block;
			}
			else
			{
				final double[] min = new double[ 3 ];
				final double[] max = new double[ 3 ];
	
				block.realMin( min );
				block.realMax( max );
	
				blockATransform.applyInverseInPlace( min );
				blockATransform.applyInverseInPlace( max );

				blockA = new FinalRealInterval( min, max );
			}

			//Supplier modelSupplier = (Supplier<TranslationModel3D> & Serializable)TranslationModel3D::new;
			ArrayList< PointMatch > matches = alignBlock( pointsA, pointsB, blockA, block, minNumInliers, matchingModel, minNumInliersICP, icpModel );

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

			if ( matches.size() > 0 )
				System.out.println( "added: " + added  + " same: " + sameMatch + " different: " + differentMatch );
		}

		return new ValuePair<>( allMatches, (double)blocksWithMatches / (double)( blocksWithMatches + blocksWithoutMatches ) );
	}

	/*
	public static void alignBlock(
			final String n5Path,
			final String idA,
			final String idB,
			final MetaData metaA,
			final MetaData metaB,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final RealInterval intervalA,
			final RealInterval intervalB,
			final int minNumInliers, //12
			final Supplier matchingModel,
			final int minNumInliersICP, //20
			final Supplier icpModel ) throws IOException
	{
		Pair< ArrayList<InterestPoint>, N5Data > pairA = loadPoints( n5Path, idA, channelA, camA, metaA );
		Pair< ArrayList<InterestPoint>, N5Data > pairB = loadPoints( n5Path, idB, channelB, camB, metaB );

		alignBlock( pairA.getA(), pairB.getA(), intervalA, intervalB, minNumInliers, matchingModel, minNumInliersICP, icpModel );
	}
	*/
	
	public static ArrayList<PointMatch> alignBlock(
			final ArrayList<InterestPoint> pointsChA,
			final ArrayList<InterestPoint> pointsChB,
			final RealInterval intervalA,
			final RealInterval intervalB,
			final int minNumInliers, //12
			final Supplier matchingModel,
			final int minNumInliersICP, //20
			final Supplier icpModel )
	{
		final boolean fastMatching = false;
		final int numNeighbors = 3;
		final int redundancy = 1;
		final double ratioOfDistance = 2;
		final int numIterations = 10000;
		final double maxEpsilon = 5;
		//final int minNumInliers = 12;

		// ICP
		final boolean doICP = true;
		final double maxDistanceICP = maxEpsilon; // for ICP
		final int maxNumIterationsICP = 100;
		//final int minNumInliersICP = 20;
		final int numIterationsICP = 10000;
		final double maxEpsilonICP = maxDistanceICP / 2.0; // for RANSAC

		final ArrayList<PointMatch> matches = SparkPaiwiseAlignChannelsGeo.matchBlock(pointsChA, pointsChB, intervalA,
				intervalB, fastMatching, numNeighbors, redundancy, ratioOfDistance, numIterations, maxEpsilon,
				minNumInliers, matchingModel, doICP, maxDistanceICP, maxNumIterationsICP, minNumInliersICP, numIterationsICP,
				maxEpsilonICP, icpModel);

		return matches;
	}

	public static Pair< ArrayList<InterestPoint>, N5Data > loadPoints(
			final String n5Path,
			final String id,
			final String channel,
			final String cam,
			final MetaData meta,
			final double avgShear ) throws IOException
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
			System.out.println( Util.printInterval( SparkPaiwiseStitchAllSlabs.interval( meta ) ) );

			ArrayList< InterestPoint > pointsChNew = new ArrayList<>();
			for ( final InterestPoint p : pointsCh )
			{
				final double[] l = p.getL().clone();
				l[ 0 ] += meta.position[ 0 ] - meta.position[ 2 ] * avgShear; // it is z along the sheared volume
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
			final double avgShear,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final int[] blockSize ) throws IOException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		Pair< ArrayList<InterestPoint>, N5Data > pairA = loadPoints( n5Path, idA, channelA, camA, metaA, avgShear );
		Pair< ArrayList<InterestPoint>, N5Data > pairB = loadPoints( n5Path, idB, channelB, camB, metaB, avgShear );

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
			final int index = m.file.toLowerCase().indexOf( "pos0" );
			String pos = "P" + m.file.toLowerCase().substring( index + 1, index + 6 );
			metaDataMap.put( pos, m );
		}

		return metaDataMap;
	}

	public static class MetaData
	{
		public String type = "", file = "";
		public int[] position, size;
		public double[] pixelResolution;
		public int index;
	}

	public static ArrayList<PointMatch> globalICP(
			ArrayList<PointMatch> matchesTmp,
			final ArrayList<InterestPoint> pointsA,
			final ArrayList<InterestPoint> pointsB,
			final ArrayList< Interval > blocks,
			final boolean nonRigid ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		System.out.println( "Total matches:" + matchesTmp.size() );

		if ( matchesTmp.size() < 4 )
			return new ArrayList<>();

		TranslationModel3D translation = new TranslationModel3D();
		translation.fit( matchesTmp );
		SparkPaiwiseAlignChannelsGeo.error(matchesTmp, translation );

		AffineModel3D affine = new AffineModel3D();
		affine.fit( matchesTmp );
		SparkPaiwiseAlignChannelsGeo.error(matchesTmp, affine );

		MovingLeastSquaresTransform mls = new MovingLeastSquaresTransform();// MovingLeastSquaresTransform3(); // cuts of correspondences if weight is too small
		mls.setModel( new AffineModel3D() );
		mls.setMatches( matchesTmp );
		SparkPaiwiseAlignChannelsGeo.error( matchesTmp, mls );

		List< InterestPoint > pointsChATmp = containedPoints( blocks, pointsA );
		List< InterestPoint > pointsChANew = new ArrayList<InterestPoint>();

		if ( nonRigid )
		{
			System.out.println( "Deforming " + pointsChATmp.size() + " ChA points " );
	
			for ( final InterestPoint p : pointsChATmp )
			{
				final double[] l = p.getL().clone();
				mls.applyInPlace( l );
				pointsChANew.add( new InterestPoint( p.getId(), l ) );
			}
		}
		else
		{
			System.out.println( "affine transforming " + pointsChATmp.size() + " ChA points " );
			
			for ( final InterestPoint p : pointsChATmp )
			{
				final double[] l = p.getL().clone();
				affine.applyInPlace( l );
				pointsChANew.add( new InterestPoint( p.getId(), l ) );
			}
		}

		// Total matches:10723 -- 1.0
		// Total matches:11918 -- 2.0
		final Model< ? > model = new InterpolatedAffineModel3D<>(new AffineModel3D(), new TranslationModel3D(), 0.1 ); //new AffineModel3D();
		final double maxDistance = nonRigid ? 2.0 : 5.0;
		final int maxIterations = 100;

		matchesTmp = matchICP( pointsChANew, pointsB, model, maxDistance, maxIterations );
		System.out.println( "Total matches after non-rigid ICP:" + matchesTmp.size() );

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

		if ( matches.size() < 4 )
		{
			System.out.println( "NO matches to save for " + idA + "<>" + idB + " ... " );
			return;
		}

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

	public static class AlignStatistics implements Serializable
	{
		private static final long serialVersionUID = -8074811409462717119L;

		String idA, idB, channelA, channelB, camA, camB;

		int firstPassInliers = -1, secondPassInliers = -1, thirdPassInliers = -1;
		double firstPassRatio = -1, secondPassRatio = -1, thirdPassRatio = -1;

		ArrayList< PointMatch > matches = new ArrayList<>();

		@Override
		public String toString()
		{
			if ( matches == null || matches.size() == 0 )
			{
				return idA + "<>" + idB + ": FAILED: " + firstPassInliers + " (" + firstPassRatio + ") -> "+ secondPassInliers + " (" + secondPassRatio + ") -> "+ thirdPassInliers + " (" + thirdPassRatio + ")";
			}
			else
			{
				return idA + "<>" + idB + ": SUCCESS [" + matches.size() + " matches]: " + firstPassInliers + " (" + firstPassRatio + ") -> "+ secondPassInliers + " (" + secondPassRatio + ") -> "+ thirdPassInliers + " (" + thirdPassRatio + ")";
			}
			
		}
	}

	public static AlignStatistics align(
			final String positionFile,
			final double avgShear,
			final String n5Path,
			final String idA,
			final String idB,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final int[] blockSize ) throws IOException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final AlignStatistics statistics = new AlignStatistics();
		statistics.idA = idA;
		statistics.idB = idB;
		statistics.channelA = channelA;
		statistics.channelB = channelB;
		statistics.camA = camA;
		statistics.camB = camB;

		System.out.println( idA + " <> " + idB );

		final HashMap< String, MetaData > meta = readPositionMetaData( positionFile );

		Pair< ArrayList<InterestPoint>, N5Data > pairA = loadPoints( n5Path, idA, channelA, camA, meta.get( idA ), avgShear );
		Pair< ArrayList<InterestPoint>, N5Data > pairB = loadPoints( n5Path, idB, channelB, camB, meta.get( idB ), avgShear );

		// JAYARAM
		//for ( final InterestPoint p : pairB.getA() )
			//l[ 1 ] -= 1730;

		final ArrayList< Interval > blocks =
				findBlocks( pairA.getA(), pairB.getA(), blockSize );

		final Supplier translationSupplier = (Supplier<TranslationModel3D> & Serializable)TranslationModel3D::new;
		final Supplier affineSupplier = (Supplier<AffineModel3D> & Serializable)AffineModel3D::new;

		// align all blocks with translation
		Pair< ArrayList<PointMatch>, Double > resultTmp = alignAllBlocks( blocks, null, pairA.getA(), pairB.getA(), 12, translationSupplier, 20, affineSupplier );

		statistics.firstPassInliers = resultTmp.getA().size();
		statistics.firstPassRatio = resultTmp.getB();

		// if matches were found, adjust block positions and re-run
		System.out.println( resultTmp.getA().size() + " matches ratio of blocks with matches=" + resultTmp.getB() );

		if ( resultTmp.getA().size() <= 4 )
		{
			// fail
			return statistics;
		}

		// re-match with updated blocks
		final TranslationModel3D blockAtransform = new TranslationModel3D();
		blockAtransform.fit( resultTmp.getA() );
		System.out.println( "Adjusting block offset to: " + blockAtransform );

		resultTmp = alignAllBlocks( blocks, blockAtransform, pairA.getA(), pairB.getA(), 12, translationSupplier, 20, affineSupplier );

		statistics.secondPassInliers = resultTmp.getA().size();
		statistics.secondPassRatio = resultTmp.getB();

		System.out.println( resultTmp.getA().size() + " matches ratio of blocks with matches=" + resultTmp.getB() );

		if ( resultTmp.getA().size() <= 4 )
		{
			// fail
			return statistics;
		}

		// block align ICP only ...
		blockAtransform.fit( resultTmp.getA() );
		System.out.println( "Adjusting block offset to: " + blockAtransform );

		resultTmp = alignAllBlocks( blocks, blockAtransform, pairA.getA(), pairB.getA(), 12, null, 40, () -> blockAtransform.copy() );

		statistics.thirdPassInliers = resultTmp.getA().size();
		statistics.thirdPassRatio = resultTmp.getB();

		System.out.println( resultTmp.getA().size() + " matches ratio of blocks with matches=" + resultTmp.getB() );

		if ( resultTmp.getA().size() <= 4 )
		{
			// fail
			return statistics;
		}

		// global consistency check!
		final MultiConsensusFilter filter = new MultiConsensusFilter<>(
				() -> new InterpolatedAffineModel3D<>(new AffineModel3D(), new TranslationModel3D(), 0.1 ),
				10000,
				50.0,
				0,
				400 );

		ArrayList< PointMatch > matches = filter.filter( resultTmp.getA() );

		System.out.println( matches.size() + " matches remaining after global consistency check of candidates=" + ( matches.size() + resultTmp.getA().size() ) );

		// do ICP on affine or non-rigidly transformed points
		//final boolean nonRigid = true;
		//matches = globalICP( matches, pairA.getA(), pairB.getA(), blocks, nonRigid );

		// fix matches to have the same locations as the channel matches
		System.out.println( "Restoring matches to raw coordinates " );

		HashMap<Integer, InterestPoint > lookUpA = new HashMap<>();
		for ( final InterestPoint p : loadPoints( n5Path, idA, channelA, camA, null, 0 ).getA() )
			lookUpA.put( p.getId(), p );

		HashMap<Integer, InterestPoint > lookUpB = new HashMap<>();
		for ( final InterestPoint p : loadPoints( n5Path, idB, channelB, camB, null, 0 ).getA() )
			lookUpB.put( p.getId(), p );

		ArrayList<PointMatch> matchesTmp = new ArrayList<PointMatch>();

		for ( final PointMatch pm : matches )
			matchesTmp.add(
					new PointMatch(
							lookUpA.get( ((InterestPoint)pm.getP1()).getId() ),
							lookUpB.get( ((InterestPoint)pm.getP2()).getId() ) ) );

		writeMatches( matchesTmp, n5Path, idA, idB, channelA, channelB, camA, camB);

		statistics.matches = matches; // without metadata -> matchesTmp;

		// return 
		return statistics;
	}

	public static BdvStackSource<?> visualizeDetections(
			BdvStackSource<?> bdv,
			final String n5Path,
			final String id,
			final String channel,
			final String cam,
			final AffineGet transform,
			final List<InterestPoint> points ) throws FormatException, IOException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id );

		bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channel, cam, n5data.stacks.get( channel ).get( cam ), n5data.alignments.get( channel ), n5data.camTransforms.get( channel ).get( cam ), transform, 0, n5data.lastSliceIndex );
		bdv = BdvFunctions.show( SparkPaiwiseAlignChannelsGeo.renderPoints( points, false ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "detections", new BdvOptions().addTo( bdv ) );

		return bdv;
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

		final AffineTransform3D scale = new AffineTransform3D();
		scale.scale( 0.2, 0.2, 0.85 );

		bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channelA, camA, n5dataA.stacks.get( channelA ).get( camA ), n5dataA.alignments.get( channelA ), n5dataA.camTransforms.get( channelA ).get( camA ), transformA.preConcatenate( scale ), 0, n5dataA.lastSliceIndex );
		bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channelB, camB, n5dataB.stacks.get( channelB ).get( camB ), n5dataB.alignments.get( channelB ), n5dataB.camTransforms.get( channelB ).get( camB ), transformB.preConcatenate( scale ), 0, n5dataB.lastSliceIndex );
	
		if ( pointsB != null && pointsB.size() > 0 )
		{
			bdv = BdvFunctions.show( SparkPaiwiseAlignChannelsGeo.renderPoints( pointsB, false ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "detections", new BdvOptions().addTo( bdv ).sourceTransform( scale ) );
			bdv.setDisplayRange(0, 256);
			bdv.setColor( new ARGBType( ARGBType.rgba(255, 0, 0, 0)));
		}

		ViewISPIMStacksN5.setupRecordMovie( bdv );
	}

	public static void visualizeAlignmentNonRigid(
			final String n5Path,
			final String idA,
			final String channelA,
			final String camA,
			final AffineTransform3D transformA,
			final String idB,
			final String channelB,
			final String camB,
			final AffineTransform3D transformB,
			final ArrayList<PointMatch> matches,
			final List<InterestPoint> pointsB ) throws FormatException, IOException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final N5Data n5dataA = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, idA );
		final N5Data n5dataB = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, idB );

		final double alpha = 1.0;
		final boolean virtual = false;
		final long[] controlPointDistance = new long[] { 100, 100, 20 };

		// get the input image (same coordinate space as correspondences)
		System.out.println( new Date( System.currentTimeMillis() ) + ": Preparing idA: " + idA );

		Pair<RealRandomAccessible<UnsignedShortType>, Interval> prepareCamSource =
				ViewISPIMStack.prepareCamSource(
						n5dataA.stacks.get( channelA ).get( camA ),
						new UnsignedShortType(0),
						Interpolation.NEARESTNEIGHBOR,
						n5dataA.camTransforms.get( channelA ).get( camA ).inverse(), // pass the forward transform
						transformA,
						n5dataA.alignments.get( channelA ),
						0,
						n5dataA.lastSliceIndex );

		final Interval boundingBox = prepareCamSource.getB();

		// interest points in the pairs of images
		System.out.println( new Date( System.currentTimeMillis() ) + ": Setting up corresponding interest points for idA: " + idA );

		final HashSet< SimpleReferenceIP > corrIPs = new HashSet<>();

		double sumDist = 0;
		double minDist = Double.MAX_VALUE;
		double maxDist = -Double.MAX_VALUE;

		for ( final PointMatch pm : matches )
		{
			double dist = Math.sqrt( (pm.getP1().getL()[0] - pm.getP2().getL()[0])*(pm.getP1().getL()[0] - pm.getP2().getL()[0]) + (pm.getP1().getL()[1] - pm.getP2().getL()[1])*(pm.getP1().getL()[1] - pm.getP2().getL()[1]) + (pm.getP1().getL()[2] - pm.getP2().getL()[2])*(pm.getP1().getL()[2] - pm.getP2().getL()[2]) );
			corrIPs.add(
					new SimpleReferenceIP(
							pm.getP1().getL().clone(),
							pm.getP1().getL().clone(),
							pm.getP2().getL().clone() ) );
			
			sumDist += dist;
			maxDist = Math.max( maxDist, dist );
			minDist = Math.min( minDist, dist );
		}

		// TODO: this is after applying the first round of non-rigid!!
		System.out.println( "avg=" + (sumDist/matches.size()) + ", max=" + maxDist + ", minDist=" + minDist );

		// compute Grid
		System.out.println(
				new Date( System.currentTimeMillis() ) + ": Interpolating non-rigid model (a=" + alpha + ") using " + corrIPs.size() + " points and stepsize " +
				Util.printCoordinates( controlPointDistance ) + " Interval: " + Util.printInterval( boundingBox ) );

		RealRandomAccessible< NumericAffineModel3D > /*ModelGrid*/ grid = new ModelGrid( controlPointDistance, boundingBox, corrIPs, alpha, virtual );
		RealRandomAccessible< UnsignedShortType > transformedA = new NonRigidRealRandomAccessible< UnsignedShortType >(grid,  prepareCamSource.getA() );

		System.out.println( new Date( System.currentTimeMillis() ) + ": displaying" );

		BdvStackSource<?> bdv = null;

		bdv = BdvFunctions.show( transformedA, prepareCamSource.getB(), "non-rigid A", new BdvOptions().addTo( bdv ) );
		bdv.setDisplayRange(0, 1024);
		bdv.setColor( new ARGBType( ARGBType.rgba(0, 255, 0, 0)));

		bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channelB, camB, n5dataB.stacks.get( channelB ).get( camB ), n5dataB.alignments.get( channelB ), n5dataB.camTransforms.get( channelB ).get( camB ), transformB, 0, n5dataB.lastSliceIndex );
		bdv.setDisplayRange(0, 1024);
		bdv.setColor( new ARGBType( ARGBType.rgba(255, 0, 255, 0)));

		if ( pointsB != null && pointsB.size() > 0 )
		{
			bdv = BdvFunctions.show( SparkPaiwiseAlignChannelsGeo.renderPoints( pointsB, false ), Intervals.createMinMax( 0, 0, 0, 1, 1, 1), "detections", new BdvOptions().addTo( bdv ) );
			bdv.setDisplayRange(0, 256);
		}
	}

	@Override
	public Void call() throws IOException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException, ClassNotFoundException
	{
		//final double avgShear = 13; //TIM
		final double avgShear = 3.5; //JAYARAM

		final AlignStatistics result =
				align( positionFile, avgShear, n5Path, idA, idB, channelA, channelB, camA, camB, blocksize );

		System.out.println( result );

		final ArrayList< PointMatch > matches = result.matches;

		System.exit( 0 );

		final HashMap< String, MetaData > meta = readPositionMetaData( positionFile );

		// TODO: points are shifted by the metadata derived translation, so we transform the input images into the global space
		AffineTransform3D metaTransformA = new AffineTransform3D();
		metaTransformA.translate( meta.get( idA ).position[ 0 ] - meta.get( idA ).position[ 2 ]*13, meta.get( idA ).position[ 1 ], meta.get( idA ).position[ 2 ] );
		AffineTransform3D metaTransformB = new AffineTransform3D();
		metaTransformB.translate( meta.get( idB ).position[ 0 ] - meta.get( idB ).position[ 2 ]*13, meta.get( idB ).position[ 1 ], meta.get( idB ).position[ 2 ] );

		// visualize
		/*
		Pair< ArrayList<InterestPoint>, N5Data > pairA = loadPoints( n5Path, idA, channelA, camA, meta.get( idA ) );
		Pair< ArrayList<InterestPoint>, N5Data > pairB = loadPoints( n5Path, idB, channelB, camB, meta.get( idB ) );

		BdvStackSource<?> bdv = visualizeDetections(
				null, n5Path, idA, channelA, camA,
				metaTransformA,
				pairA.getA() );

		bdv = visualizeDetections(
				bdv, n5Path, idB, channelB, camB,
				metaTransformB,
				pairB.getA() );

		SimpleMultiThreading.threadHaltUnClean(); */

		final TranslationModel3D translation = new TranslationModel3D();
		if ( matches.size() > 4)
			translation.fit( matches );
		SparkPaiwiseAlignChannelsGeo.error( matches, translation );

		final AffineModel3D affine = new AffineModel3D();
		if ( matches.size() > 4)
			affine.fit( matches );
		SparkPaiwiseAlignChannelsGeo.error( matches, affine );

/*		visualizeAlignmentNonRigid(
				n5Path,
				idA, channelA, camA, metaTransformA,
				idB, channelB, camB, metaTransformB,
				matches,
				matches.stream().map( pm -> ((InterestPoint)pm.getP2()) ).collect( Collectors.toList() ) );*/

		metaTransformA = metaTransformA.preConcatenate( TransformationTools.getAffineTransform( affine ) );

		visualizeAlignment(
				n5Path,
				idA, channelA, camA, metaTransformA,
				idB, channelB, camB, metaTransformB, matches.stream().map( pm -> ((InterestPoint)pm.getP2()) ).collect( Collectors.toList() ) );

		return null;
	}

}
