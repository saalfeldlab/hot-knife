package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import mpicbg.models.Affine3D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel3D;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.models.TranslationModel3D;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RealPoint;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.explorer.util.ColorStream;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
		name = "GlobalOptimizeExport",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Globally optimize stitching result")
public class GlobalOptimize implements Callable<Void>, Serializable 
{
	private static final long serialVersionUID = 3323928972188123295L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	public static ArrayList< Pair< Pair< String, String >, ArrayList< PointMatch > > > loadPairwiseMatches( final N5Reader n5, final List<String> ids ) throws IOException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final ArrayList< Pair< Pair< String, String >, ArrayList< PointMatch > > > matches = new ArrayList<>();

		for ( final String idA : ids )
		{
			for ( final String dataset : n5.list( idA ) )
			{
				if ( dataset.startsWith( "matches_" + idA ) )
				{
					// TODO: store in dataset attributes!!!
					final int indexA = dataset.indexOf( "matches_" ) + 8;
					final int indexB = dataset.indexOf( "__" ) + 2;
					final String idB = dataset.substring( indexB, indexB + 6 );

					if (!dataset.substring( indexA, indexA + 6 ).equals( idA ) )
						throw new RuntimeException( "wrong matches object found for " + idA + ": " + dataset );

					final int indexChA = dataset.indexOf( "_Ch" ) + 1 ;
					final int indexChB = dataset.indexOf( "_Ch", indexChA ) + 1;

					final int indexCamA = dataset.indexOf( "_cam" ) + 1;
					final int indexCamB = dataset.indexOf( "_cam", indexCamA ) + 1;

					final String channelA = dataset.substring( indexChA, indexCamA - 1);
					final String channelB = dataset.substring( indexChB, indexCamB - 1);

					final String camA = dataset.substring( indexCamA, indexCamA + 4 );
					final String camB = dataset.substring( indexCamB, indexCamB + 4 );

					final String datasetName = idA + "/" + dataset;
					final DatasetAttributes datasetAttributes = n5.getDatasetAttributes(datasetName);

					final ArrayList<PointMatch> matchesLocal = n5.readSerializedBlock(datasetName, datasetAttributes, new long[] {0});

					matches.add( new ValuePair<>( new ValuePair<>(idA, idB),  matchesLocal ) );

					final TranslationModel3D translation = new TranslationModel3D();
					if ( matchesLocal.size() > 4)
						translation.fit( matchesLocal );

					final double localError = getError( matchesLocal, translation, new TranslationModel3D() );

					System.out.println( idA + "," + channelA + "," + camA + " <> " + idB + "," + channelB + "," + camB + ", Loaded " + matchesLocal.size() + " matches, localError=" + localError );

					if ( localError > 5.0 )
						System.out.println( "WARNING, ERROR ABOVE HIGH!" );
				}
			}
		}

		return matches;
	}

	public static < M extends Model< M > > HashMap< String, Tile< M > > createAndConnectTiles(
			final M model,
			final ArrayList< Pair< Pair< String, String >, ArrayList< PointMatch > > > matches )
	{
		final HashMap< String, Tile< M > > idToTile = new HashMap<>();

		for ( final Pair< Pair< String, String >, ArrayList< PointMatch > > entry : matches )
		{
			if ( !idToTile.containsKey( entry.getA().getA() ) )
				idToTile.put( entry.getA().getA(), new Tile<M>( model.copy() ) );

			if ( !idToTile.containsKey( entry.getA().getB() ) )
				idToTile.put( entry.getA().getB(), new Tile<M>( model.copy() ) );
		}

		for ( final Pair< Pair< String, String >, ArrayList< PointMatch > > entry : matches )
		{
			final String idA = entry.getA().getA();
			final String idB = entry.getA().getB();

			idToTile.get( idA ).connect( idToTile.get( idB ),  entry.getB() );
		}

		return idToTile;
	}

	public static class OptimizationParameters< M extends Model< M > >
	{
		int numIterations = 1000;
		int maxPlateauWidth = 250;
		double blockMaxAllowedError = 5.0;

		public OptimizationParameters() {}
		public OptimizationParameters( final int numIterations, final int maxPlateauWidth, final double blockMaxAllowedError )
		{
			this.numIterations = numIterations;
			this.maxPlateauWidth = maxPlateauWidth;
			this.blockMaxAllowedError = blockMaxAllowedError;
		}

		public void updateModel( M model ) {}

		@Override
		public String toString() { return "numIterations=" + numIterations + ", maxPlateauWidth=" + maxPlateauWidth + ", blockMaxAllowedError=" + blockMaxAllowedError; }
	}

	public static class InterpolatedModel3DOptimizationParameters<A extends Model< A > & Affine3D< A >,B extends Model< B > & Affine3D< B >> extends OptimizationParameters< InterpolatedAffineModel3D<A,B> >
	{
		double lambda = 0.1;

		public InterpolatedModel3DOptimizationParameters() {}
		public InterpolatedModel3DOptimizationParameters( final int numIterations, final int maxPlateauWidth, final double blockMaxAllowedError, final double lambda )
		{
			this.numIterations = numIterations;
			this.maxPlateauWidth = maxPlateauWidth;
			this.blockMaxAllowedError = blockMaxAllowedError;
			this.lambda = lambda;
		}

		@Override
		public void updateModel( final InterpolatedAffineModel3D<A,B> model ) { model.setLambda( this.lambda ); };

		@Override
		public String toString() { return "lambda=" + lambda + ", " + super.toString(); }
	}

	public < M extends Model< M > > void solve(
			final HashMap< String, Tile< M > > idToTile,
			final OptimizationParameters<M> paramPreAlign,
			final OptimizationParameters<M> param ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException
	{
		List< OptimizationParameters<M> > params = new ArrayList<>();
		params.add( param );

		solve(idToTile, paramPreAlign, params);
	}

	public < M extends Model< M > > void solve(
			final HashMap< String, Tile< M > > idToTile,
			final OptimizationParameters<M> paramPreAlign,
			final List<? extends OptimizationParameters<M>> params ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException
	{
		final TileConfiguration tileConfig = new TileConfiguration();
		tileConfig.addTiles( idToTile.values() );

		// pre-align with translation only
		if ( paramPreAlign != null )
		{
			System.out.println( new Date(System.currentTimeMillis() ) + ": Prealigning with " + paramPreAlign.toString() );

			// update model parameters such as lambdas
			for ( final Tile< M > tile : idToTile.values() )
				paramPreAlign.updateModel( tile.getModel() );

			tileConfig.preAlign();
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": Iterative solve." );

		final int numThreads = Runtime.getRuntime().availableProcessors() / 2;

		for ( final OptimizationParameters<M> param : params )
		{
			System.out.println( "Solving with: " + param );

			// update model parameters such as lambdas
			for ( final Tile< M > tile : idToTile.values() )
				param.updateModel( tile.getModel() );

			final ErrorStatistic observer = new ErrorStatistic( param.maxPlateauWidth + 1 );
			final float damp = 1.0f;
			TileUtil.optimizeConcurrently(
					observer,
					param.blockMaxAllowedError,
					param.numIterations,
					param.maxPlateauWidth,
					damp,
					tileConfig,
					tileConfig.getTiles(),
					tileConfig.getFixedTiles(),
					numThreads );
		}

	}

	protected static double getError( List<PointMatch> matches, final CoordinateTransform modelA, final CoordinateTransform modelB ) throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		for ( final PointMatch pm : matches )
		{
			pm.getP1().apply( modelA );
			pm.getP2().apply( modelB ); // make sure the world coordinates are ok
		}
		
		return PointMatch.meanDistance( matches );
	}

	public static < M extends Model< M > & Affine3D< M > > void visualizeMatches(
			final List< Pair< Pair< String, String >, ArrayList< PointMatch > > > matches,
			final Map< String, Tile< M > > models )
	{
		BdvStackSource< ? > bdv = null;

		final List< Pair< Pair< String, String >, List< ? extends Point > > > allPoints = new ArrayList<>();

		final long[] min = new long[] { Long.MAX_VALUE, Long.MAX_VALUE };//, Long.MAX_VALUE };
		final long[] max = new long[] { -Long.MAX_VALUE, -Long.MAX_VALUE };//, -Long.MAX_VALUE };

		for ( final Pair< Pair< String, String >, ArrayList< PointMatch > > entry : matches )
		{
			final String idA = entry.getA().getA();
			final String idB = entry.getA().getB();

			final M model = models.get( idA ).getModel();

			// only showing points of idA (maybe use average location?)
			/*
			final List<InterestPoint> points = entry.getB().stream().map( pm -> ((InterestPoint)pm.getP1()) ).collect( Collectors.toList() );

			for ( final InterestPoint p : points )
			{
				p.apply( model );

				for ( int d = 0; d < p.getW().length; ++d )
				{
					min[ d ] = Math.min( min[ d ], Math.round( p.getW()[ d ] ) );
					max[ d ] = Math.max( max[ d ], Math.round( p.getW()[ d ] ) );
				}
			}
			*/

			final List<Point> points = new ArrayList<>();
			for ( final PointMatch pm : entry.getB() )
			{
				pm.getP1().apply( model );
				double[] l = new double[] { pm.getP1().getW()[ 0 ], pm.getP1().getW()[ 1 ] };
				Point p = new Point( l );
				points.add( p );

				for ( int d = 0; d < p.getW().length; ++d )
				{
					min[ d ] = Math.min( min[ d ], Math.round( p.getW()[ d ] ) );
					max[ d ] = Math.max( max[ d ], Math.round( p.getW()[ d ] ) );
				}

			}

			allPoints.add( new ValuePair<>( entry.getA(), points ) );
		}

		final Interval interval = Intervals.expand( new FinalInterval( min, max ), new long[] { 1000, 1000 } );

		System.out.println( "interval=" + Util.printInterval( interval ) );

		for ( final Pair< Pair< String, String >, List< ? extends Point > > points : allPoints )
		{
			bdv = BdvFunctions.show(
					SparkPaiwiseAlignChannelsGeo.renderPointsNoCopy( points.getB(), 10, true ),
					interval,
					points.getA().getA() + "<>" + points.getA().getB(),
					new BdvOptions().addTo( bdv ) );
			bdv.setDisplayRange(0, 2048);
			bdv.setColor( new ARGBType( ColorStream.next() ) );
		}
	}

	@Override
	public Void call() throws Exception
	{
		final N5Reader n5 = new N5FSReader(n5Path);

		final List<String> ids = SparkPaiwiseAlignChannelsGeoAll.getIds(n5);
		Collections.sort( ids );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Loading matches." );
		final ArrayList< Pair< Pair< String, String >, ArrayList< PointMatch > > > matches = loadPairwiseMatches( n5, ids );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Setting up tiles." );
		final HashMap< String, Tile< InterpolatedAffineModel3D<AffineModel3D, TranslationModel3D> > > idToTile =
				createAndConnectTiles(
						new InterpolatedAffineModel3D<AffineModel3D, TranslationModel3D>(new AffineModel3D(), new TranslationModel3D(), 0.1 ), matches );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Solving." );

		// parameters for pre-align (translation-only)
		InterpolatedModel3DOptimizationParameters<AffineModel3D, TranslationModel3D> preAlign =
				new InterpolatedModel3DOptimizationParameters<>( 0, 0, 0.0, 1.0 );

		List< InterpolatedModel3DOptimizationParameters<AffineModel3D, TranslationModel3D> > params = new ArrayList<>();
		params.add( new InterpolatedModel3DOptimizationParameters<>( 1000, 250, 5.0, 1.0 ) );
		params.add( new InterpolatedModel3DOptimizationParameters<>( 500, 250, 5.0, 0.5 ) );
		params.add( new InterpolatedModel3DOptimizationParameters<>( 200, 100, 5.0, 0.1 ) );
		params.add( new InterpolatedModel3DOptimizationParameters<>( 100, 25, 5.0, 0.01 ) );

		solve( idToTile, preAlign, params );

		for ( final String id : ids )
			System.out.println( id + ": " + idToTile.get( id ).getModel() );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Computing errors." );

		double minError = Double.MAX_VALUE;
		double maxError = 0;
		double avgError = 0;

		for ( final Pair< Pair< String, String >, ArrayList< PointMatch > > entry : matches )
		{
			final String idA = entry.getA().getA();
			final String idB = entry.getA().getB();

			final TranslationModel3D translation = new TranslationModel3D();
			translation.fit( entry.getB() );

			final double localError = getError( entry.getB(), translation, new TranslationModel3D() );
			final double globalError = getError( entry.getB(), idToTile.get( idA ).getModel(), idToTile.get( idB ).getModel() );

			minError = Math.min( globalError, minError );
			maxError = Math.max( globalError, maxError );
			avgError += globalError;

			System.out.println( idA + " <> " + idB + " global error=" + globalError + ", local error (Translation3D)=" + localError );
		}

		avgError /= matches.size();

		System.out.println( minError + "," + avgError + "," + maxError );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Visualizing." );
		visualizeMatches( matches, idToTile );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Done." );
		SimpleMultiThreading.threadHaltUnClean();
		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new GlobalOptimize()).execute(args));
	}

}
