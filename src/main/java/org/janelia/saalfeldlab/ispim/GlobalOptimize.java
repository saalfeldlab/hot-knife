package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;

import com.google.gson.GsonBuilder;

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
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.explorer.util.ColorStream;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
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

	public static class Description
	{
		String id, channel, cam;

		public Description( final String id, final String channel, final String cam )
		{
			this.id = id;
			this.channel = channel;
			this.cam = cam;
		}

		@Override
		public String toString() { return id + "," + channel + "," + cam; }

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((cam == null) ? 0 : cam.hashCode());
			result = prime * result + ((channel == null) ? 0 : channel.hashCode());
			result = prime * result + ((id == null) ? 0 : id.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			Description other = (Description) obj;
			if (cam == null) {
				if (other.cam != null)
					return false;
			} else if (!cam.equals(other.cam))
				return false;
			if (channel == null) {
				if (other.channel != null)
					return false;
			} else if (!channel.equals(other.channel))
				return false;
			if (id == null) {
				if (other.id != null)
					return false;
			} else if (!id.equals(other.id))
				return false;
			return true;
		}
	}

	public static ArrayList< Pair< Pair< Description, Description >, ArrayList< PointMatch > > > loadPairwiseMatches(
			final N5Reader n5,
			final List<String> ids,
			final int maxNumMatches ) throws IOException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final ArrayList< Pair< Pair< Description, Description >, ArrayList< PointMatch > > > matches = new ArrayList<>();

		Random rnd = new Random( 23 );

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

					final int indexCamA = dataset.indexOf( "_cam" ) + 1; // fake for now, always cam1
					final int indexCamB = dataset.indexOf( "_cam", indexCamA ) + 1; // fake for now, always cam1

					final String channelA = dataset.substring( indexChA, indexCamA - 1);
					final String channelB = dataset.substring( indexChB, indexCamB - 1);

					final String camA = dataset.substring( indexCamA, indexCamA + 4 );
					final String camB = dataset.substring( indexCamB, indexCamB + 4 );

					final String datasetName = idA + "/" + dataset;
					final DatasetAttributes datasetAttributes = n5.getDatasetAttributes(datasetName);

					final ArrayList<PointMatch> matchesLocal = n5.readSerializedBlock(datasetName, datasetAttributes, new long[] {0});

					while ( matchesLocal.size() > maxNumMatches )
						matchesLocal.remove( rnd.nextInt( matchesLocal.size() ) );

					matches.add( new ValuePair<>( new ValuePair<>( new Description(idA, channelA, camA), new Description(idB, channelB, camB ) ),  matchesLocal ) );

					final TranslationModel3D translation = new TranslationModel3D();
					for ( final PointMatch pm : matchesLocal )
						pm.getP2().apply( translation );
					if ( matchesLocal.size() > 4)
						translation.fit( matchesLocal );

					final double localError = getError( matchesLocal, translation, new TranslationModel3D() );

					System.out.println( idA + "," + channelA + "," + camA + " <> " + idB + "," + channelB + "," + camB + ", Loaded " + matchesLocal.size() + " matches, localError=" + localError );

					if ( localError > 6.0 )
						System.out.println( "WARNING, ERROR ABOVE HIGH!" );
				}
				else if (dataset.startsWith( "matches_Ch" ) ) //e.g. matches_Ch488+561+647nm_Ch515+594nm
				{
					// TODO: store in dataset attributes!!!
					final int indexChA = dataset.indexOf( "_Ch" ) + 1 ;
					final int indexChB = dataset.indexOf( "_Ch", indexChA ) + 1;

					final String channelA = dataset.substring( indexChA, indexChB - 1);
					final String channelB = dataset.substring( indexChB, dataset.length() );

					//if ( channelA.contains( "405" ) || channelB.contains( "405" ) )
					//	continue;

					final String camA = "cam1"; // fake for now
					final String camB = "cam1"; // fake for now

					final String datasetName = idA + "/" + dataset;
					final DatasetAttributes datasetAttributes = n5.getDatasetAttributes(datasetName);

					final ArrayList<PointMatch> matchesLocal = n5.readSerializedBlock(datasetName, datasetAttributes, new long[] {0});

					while ( matchesLocal.size() > maxNumMatches )
						matchesLocal.remove( rnd.nextInt( matchesLocal.size() ) );

					matches.add( new ValuePair<>( new ValuePair<>( new Description(idA, channelA, camA), new Description(idA, channelB, camB ) ),  matchesLocal ) );

					final AffineModel3D model = new AffineModel3D();
					for ( final PointMatch pm : matchesLocal )
						pm.getP2().apply( model );
					if ( matchesLocal.size() > 4)
						model.fit( matchesLocal );

					final double localError = getError( matchesLocal, model, new TranslationModel3D() );

					System.out.println( idA + ": " + channelA + "," + camA + " <> " + channelB + "," + camB + ", Loaded " + matchesLocal.size() + " matches, localError=" + localError );

					if ( localError > 6.0 )
						System.out.println( "WARNING, ERROR ABOVE HIGH!" );

					//System.exit( 0 );
				}
			}
		}

		return matches;
	}

	public static < M extends Model< M > > HashMap< Description, Tile< M > > createAndConnectTiles(
			final M model,
			final ArrayList< Pair< Pair< Description, Description >, ArrayList< PointMatch > > > matches )
	{
		final HashMap< Description, Tile< M > > idToTile = new HashMap<>();

		for ( final Pair< Pair< Description, Description >, ArrayList< PointMatch > > entry : matches )
		{
			if ( !idToTile.containsKey( entry.getA().getA() ) )
				idToTile.put( entry.getA().getA(), new Tile<M>( model.copy() ) );

			if ( !idToTile.containsKey( entry.getA().getB() ) )
				idToTile.put( entry.getA().getB(), new Tile<M>( model.copy() ) );
		}

		for ( final Pair< Pair< Description, Description >, ArrayList< PointMatch > > entry : matches )
		{
			final Description idA = entry.getA().getA();
			final Description idB = entry.getA().getB();

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

		public void updateModel( Tile<M> tile ) {}

		@Override
		public String toString() { return "numIterations=" + numIterations + ", maxPlateauWidth=" + maxPlateauWidth + ", blockMaxAllowedError=" + blockMaxAllowedError; }
	}

	public static class InterpolatedModel3DOptimizationParameters<A extends Model< A > & Affine3D< A >,B extends Model< B > & Affine3D< B >> extends OptimizationParameters< InterpolatedAffineModel3D<A,B> >
	{
		double lambda = 0.1;
		final HashMap< Tile< InterpolatedAffineModel3D<AffineModel3D, TranslationModel3D> >, Description > tileToId;

		public InterpolatedModel3DOptimizationParameters(
				final HashMap< Tile< InterpolatedAffineModel3D<AffineModel3D, TranslationModel3D> >, Description > tileToId,
				final int numIterations,
				final int maxPlateauWidth,
				final double blockMaxAllowedError,
				final double lambda )
		{
			this.tileToId = tileToId;
			this.numIterations = numIterations;
			this.maxPlateauWidth = maxPlateauWidth;
			this.blockMaxAllowedError = blockMaxAllowedError;
			this.lambda = lambda;
		}

		@Override
		public void updateModel( final Tile<InterpolatedAffineModel3D<A,B>> tile )
		{
			tile.getModel().setLambda( this.lambda );
		};

		@Override
		public String toString() { return "lambda=" + lambda + ", " + super.toString(); }
	}

	public < M extends Model< M > > void solve(
			final HashMap< Description, Tile< M > > idToTile,
			final OptimizationParameters<M> paramPreAlign,
			final OptimizationParameters<M> param ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException
	{
		List< OptimizationParameters<M> > params = new ArrayList<>();
		params.add( param );

		solve(idToTile, paramPreAlign, params);
	}

	public < M extends Model< M > > void solve(
			final HashMap< Description, Tile< M > > idToTile,
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
				paramPreAlign.updateModel( tile );

			tileConfig.preAlign();
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": Iterative solve." );

		final int numThreads = Runtime.getRuntime().availableProcessors() / 2;

		for ( final OptimizationParameters<M> param : params )
		{
			System.out.println( "Solving with: " + param );

			// update model parameters such as lambdas
			for ( final Tile< M > tile : idToTile.values() )
				param.updateModel( tile );

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
			final List< Pair< Pair< Description, Description >, ArrayList< PointMatch > > > matches,
			final Map< Description, Tile< M > > models )
	{
		BdvStackSource< ? > bdv = null;

		final List< Pair< Pair< Description, Description >, List< ? extends Point > > > allPoints = new ArrayList<>();

		final long[] min = new long[] { Long.MAX_VALUE, Long.MAX_VALUE };//, Long.MAX_VALUE };
		final long[] max = new long[] { -Long.MAX_VALUE, -Long.MAX_VALUE };//, -Long.MAX_VALUE };

		for ( final Pair< Pair< Description, Description >, ArrayList< PointMatch > > entry : matches )
		{
			final Description idA = entry.getA().getA();
			final Description idB = entry.getA().getB();

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

		for ( final Pair< Pair< Description, Description >, List< ? extends Point > > points : allPoints )
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

	public static ArrayList< Description > allDescriptions( final ArrayList< Pair< Pair< Description, Description >, ArrayList< PointMatch > > > matches )
	{
		final HashSet< Description > descriptionsMap = new HashSet<>();
		descriptionsMap.addAll( matches.stream().map( pair -> pair.getA().getA() ).collect( Collectors.toList() ) );
		descriptionsMap.addAll( matches.stream().map( pair -> pair.getA().getB() ).collect( Collectors.toList() ) );

		final ArrayList< Description > descriptions = new ArrayList<>();
		descriptions.addAll( descriptionsMap );

		Collections.sort( descriptions, (o1, o2 ) ->
			{
				final int id = o1.id.compareTo( o2.id );

				if ( id != 0 )
					return id;

				final int channel = o1.channel.compareTo( o2.channel );

				if ( channel != 0 )
					return channel;

				return o1.cam.compareTo( o2.cam );
			});

		return descriptions;
	}

	private void addMissingCh405Tiles(
			final ArrayList< Pair< Pair< Description, Description >, ArrayList< PointMatch > > > matches,
			final List<String> ids )
	{
		final HashSet<Description> allDesc = new HashSet<>( allDescriptions( matches ) );
		final HashSet<String> allIds = new HashSet<>( ids );

		final HashMap<String, Description > all = new HashMap<>();
		final HashSet<String > missing = new HashSet<>();

		for ( final Description d : allDesc )
			if ( d.channel.contains( "Ch405nm" ) )
				all.put( d.id, d );

		for ( final String id : allIds )
			if ( !all.containsKey( id ) )
				missing.add( id );

			for ( final String id : missing )
			{
				System.out.println( "Creating fake matches for: " + id );
	
				Description oldA = null, oldB = null;
				Description newA = null, newB = null;
				ArrayList< PointMatch > matchesA = null;
	
				for ( final Pair< Pair< Description, Description >, ArrayList< PointMatch > > pair : matches )
				{
					if ( pair.getA().getB().id.equals( id ) && !pair.getA().getA().id.equals( id ) && pair.getA().getA().channel.equals( pair.getA().getB().channel ) )
					{
						oldA = pair.getA().getA();
						oldB = pair.getA().getB();
						for ( final Description desc : allDesc )
							if ( desc.channel.equals( "Ch405nm" ) && desc.id.equals( pair.getA().getA().id ) )
								newA = desc;
	
						if ( newA == null )
							continue;
	
						newB = new Description( id, "Ch405nm", "cam1" );
						matchesA = new ArrayList<>();
						for ( final PointMatch pm : pair.getB() )
							matchesA.add( new PointMatch( pm.getP1().clone(), pm.getP2().clone() ) );
						break;
					}
	
					if ( pair.getA().getA().id.equals( id ) && !pair.getA().getB().id.equals( id ) && pair.getA().getA().channel.equals( pair.getA().getB().channel ) )
					{
						oldA = pair.getA().getA();
						oldB = pair.getA().getB();
						for ( final Description desc : allDesc )
							if ( desc.channel.equals( "Ch405nm" ) && desc.id.equals( pair.getA().getB().id ) )
								newB = desc;
	
						if ( newB == null )
							continue;
	
						newA = new Description( id, "Ch405nm", "cam1" );
						matchesA = new ArrayList<>();
						for ( final PointMatch pm : pair.getB() )
							matchesA.add( new PointMatch( pm.getP1().clone(), pm.getP2().clone() ) );
						break;
					}
				}
	
				if ( matchesA != null )
				{
					System.out.println( "added: " + newA + "," + newB + ": " + matchesA.size() + " from " + oldA + ", " + oldB );
					matches.add( new ValuePair<>( new ValuePair<>( newA, newB ), matchesA ) );
				}
				else
				{
					newA = new Description( id, "Ch405nm", "cam1" );
					newB = new Description( id, "Ch488+561+647nm", "cam1" );
					matchesA = new ArrayList<>();
					matchesA.add( new PointMatch( new Point( new double[] { 0, 0, 0 }), new Point( new double[] { 0, 0, 0 } ) ) );
					matchesA.add( new PointMatch( new Point( new double[] { 1, 0, 0 }), new Point( new double[] { 1, 0, 0 } ) ) );
					matchesA.add( new PointMatch( new Point( new double[] { 0, 1, 0 }), new Point( new double[] { 0, 1, 0 } ) ) );
					matchesA.add( new PointMatch( new Point( new double[] { 0, 0, 1 }), new Point( new double[] { 0, 0, 1 } ) ) );
					matchesA.add( new PointMatch( new Point( new double[] { 10, 0, 10 }), new Point( new double[] { 10, 0, 10 } ) ) );
					matchesA.add( new PointMatch( new Point( new double[] { 0, 10, 0 }), new Point( new double[] { 0, 10, 0 } ) ) );
					matchesA.add( new PointMatch( new Point( new double[] { 10, 0, 10 }), new Point( new double[] { 10, 0, 10 } ) ) );
					
					System.out.println( "added: " + newA + "," + newB + ": " + matchesA.size() + " from " + oldA + ", " + oldB );
					matches.add( new ValuePair<>( new ValuePair<>( newA, newB ), matchesA ) );
				}
			}
	}

	@Override
	public Void call() throws Exception
	{
		final N5Writer n5 = new N5FSWriter(
				n5Path,
				new GsonBuilder().
					registerTypeAdapter(
						AffineTransform3D.class,
						new AffineTransform3DAdapter()).
					registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final List<String> allIds = SparkPaiwiseAlignChannelsGeoAll.getIds(n5);
		Collections.sort( allIds );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Loading matches." );
		final ArrayList< Pair< Pair< Description, Description >, ArrayList< PointMatch > > > matches = loadPairwiseMatches( n5, allIds, 1000 );

		addMissingCh405Tiles( matches, allIds );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Setting up tiles." );
		final HashMap< Description, Tile< InterpolatedAffineModel3D<AffineModel3D, TranslationModel3D> > > idToTile =
				createAndConnectTiles(
						new InterpolatedAffineModel3D<AffineModel3D, TranslationModel3D>(new AffineModel3D(), new TranslationModel3D(), 0.1 ), matches );

		final HashMap< Tile< InterpolatedAffineModel3D<AffineModel3D, TranslationModel3D> >, Description > tileToId = new HashMap<>();
		for ( final Entry<Description, Tile<InterpolatedAffineModel3D<AffineModel3D, TranslationModel3D>>> entry : idToTile.entrySet() )
			tileToId.put( entry.getValue(), entry.getKey() );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Solving." );

		// parameters for pre-align (translation-only)
		InterpolatedModel3DOptimizationParameters<AffineModel3D, TranslationModel3D> preAlign =
				new InterpolatedModel3DOptimizationParameters<>( tileToId, 0, 0, 0.0, 1.0 );

		List< InterpolatedModel3DOptimizationParameters<AffineModel3D, TranslationModel3D> > params = new ArrayList<>();
		params.add( new InterpolatedModel3DOptimizationParameters<>( tileToId, 1000, 250, 5.0, 1.0 ) );
		params.add( new InterpolatedModel3DOptimizationParameters<>( tileToId, 500, 250, 5.0, 0.5 ) );
		params.add( new InterpolatedModel3DOptimizationParameters<>( tileToId, 200, 100, 5.0, 0.1 ) );
		params.add( new InterpolatedModel3DOptimizationParameters<>( tileToId, 100, 25, 5.0, 0.01 ) );

		solve( idToTile, preAlign, params );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Saving transformations ... " );

		for ( final Description desc : allDescriptions( matches ) )
		{
			System.out.println( desc + ": " + idToTile.get( desc ).getModel().createAffineModel3D() );
			n5.setAttribute( desc.id + "/" + desc.channel, "3d-affine", TransformationTools.getAffineTransform( idToTile.get( desc ).getModel().createAffineModel3D() ) );
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": Computing errors." );

		double minError = Double.MAX_VALUE;
		double maxError = 0;
		double avgError = 0;

		for ( final Pair< Pair< Description, Description >, ArrayList< PointMatch > > entry : matches )
		{
			final Description idA = entry.getA().getA();
			final Description idB = entry.getA().getB();

			final TranslationModel3D translation = new TranslationModel3D();
			for ( final PointMatch pm : entry.getB() )
				pm.getP2().apply( translation );
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
