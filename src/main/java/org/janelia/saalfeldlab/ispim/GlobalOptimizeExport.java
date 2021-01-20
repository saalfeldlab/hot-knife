package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import mpicbg.models.PointMatch;
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
public class GlobalOptimizeExport implements Callable<Void>, Serializable 
{
	private static final long serialVersionUID = 3323928972188123295L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	public static ArrayList< Pair< Pair< String, String >, ArrayList< PointMatch > > > loadPairwiseMatches( final N5Reader n5, final List<String> ids ) throws IOException, ClassNotFoundException
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
					System.out.println( new Date(System.currentTimeMillis() ) + ": " + idA + " <> " + idB + ", Loaded " + matchesLocal.size() + " matches" );
				}
			}
		}

		return matches;
	}

	@Override
	public Void call() throws Exception
	{
		final N5Reader n5 = new N5FSReader(n5Path);

		final List<String> ids = SparkPaiwiseAlignChannelsGeoAll.getIds(n5);
		Collections.sort( ids );

		loadPairwiseMatches( n5, ids );

		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new GlobalOptimizeExport()).execute(args));
	}

}
