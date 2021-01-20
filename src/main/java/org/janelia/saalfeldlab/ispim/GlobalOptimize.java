package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import mpicbg.models.Affine3D;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TileUtil;
import mpicbg.models.TranslationModel3D;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
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
					final int index = dataset.indexOf( "__" );
					final String idB = dataset.substring( index + 2, index + 8 );

					final String datasetName = idA + "/" + dataset;
					final DatasetAttributes datasetAttributes = n5.getDatasetAttributes(datasetName);

					final ArrayList<PointMatch> matchesLocal = n5.readSerializedBlock(datasetName, datasetAttributes, new long[] {0});

					matches.add( new ValuePair<>( new ValuePair<>(idA, idB),  matchesLocal ) );

					final TranslationModel3D translation = new TranslationModel3D();
					if ( matchesLocal.size() > 4)
						translation.fit( matchesLocal );

					System.out.println(
							new Date(System.currentTimeMillis() ) + ": " + 
							idA + " <> " + idB + ", Loaded " + matchesLocal.size() + " matches, error=" + SparkPaiwiseAlignChannelsGeo.getError( matchesLocal, translation ) );

					//return matches;
				}
			}
		}

		return matches;
	}

	public static < M extends Model< M > & Affine3D< M > > HashMap< String, Tile< M > > createAndConnectTiles(
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

	public void solve( final HashMap< String, Tile< TranslationModel3D > > idToTile ) throws NotEnoughDataPointsException, IllDefinedDataPointsException, InterruptedException, ExecutionException
	{
		final TileConfiguration tileConfig = new TileConfiguration();
		tileConfig.addTiles( idToTile.values() );

		// TODO: pre-align with translation only
		tileConfig.preAlign();

		final int numIterations = 1000;
		final int maxPlateauWidth = 250;
		final double blockMaxAllowedError = 5.0;
		final int numThreads = Runtime.getRuntime().availableProcessors() / 2;

		final ErrorStatistic observer = new ErrorStatistic( maxPlateauWidth + 1 );
		final float damp = 1.0f;
		TileUtil.optimizeConcurrently(
				observer,
				blockMaxAllowedError,
				numIterations,
				maxPlateauWidth,
				damp,
				tileConfig,
				tileConfig.getTiles(),
				tileConfig.getFixedTiles(),
				numThreads );

		System.out.println( tileConfig.getMinError() + "," + tileConfig.getError() + "," + tileConfig.getMaxError() );
		System.out.println( observer.min + ", " + observer.mean + ", " + observer.max );
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

	@Override
	public Void call() throws Exception
	{
		final N5Reader n5 = new N5FSReader(n5Path);

		final List<String> ids = SparkPaiwiseAlignChannelsGeoAll.getIds(n5);
		Collections.sort( ids );

		final ArrayList< Pair< Pair< String, String >, ArrayList< PointMatch > > > matches = loadPairwiseMatches( n5, ids );
		final HashMap< String, Tile< TranslationModel3D > > idToTile = createAndConnectTiles( new TranslationModel3D(), matches );
		solve( idToTile );

		for ( final String id : ids )
			System.out.println( id + ": " + idToTile.get( id ).getModel() );

		for ( final Pair< Pair< String, String >, ArrayList< PointMatch > > entry : matches )
		{
			final String idA = entry.getA().getA();
			final String idB = entry.getA().getB();

			final TranslationModel3D translation = new TranslationModel3D();
			translation.fit( entry.getB() );

			final double localError = SparkPaiwiseAlignChannelsGeo.getError( entry.getB(), translation );
			final double globalError = getError( entry.getB(), idToTile.get( idA ).getModel(), idToTile.get( idB ).getModel() );
			
			System.out.println( idA + " <> " + idB + " global error=" + globalError + ", local error=" + localError );
			
		}

		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new GlobalOptimize()).execute(args));
	}

}
