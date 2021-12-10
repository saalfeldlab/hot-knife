/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.ispim.SparkPairwiseStitchSlabs.AlignStatistics;
import org.janelia.saalfeldlab.ispim.SparkPairwiseStitchSlabs.MetaData;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.Block;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import com.google.common.reflect.TypeToken;

import loci.formats.FormatException;
import mpicbg.models.PointMatch;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import scala.Tuple2;
import scala.Tuple3;

/**
 * Align a 3D N5 dataset.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command(
		name = "SparkPaiwiseStitchAllSlabs",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Align all pairs of slabs using geometric local descriptor matching")
public class SparkPaiwiseStitchAllSlabs implements Callable<Void>, Serializable {

	private static final long serialVersionUID = 6708886268386777152L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--positionFile", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5/m24o.edited.pos.json")
	private String positionFile = null;

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

	@Option(names = "--excludeIds", split=",", required = false, description = "ids to be exluded")
	private HashSet<String> excludeIds = new HashSet<>();

	/*
	--n5Path=/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5
	--positionFile=/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5/m24o.edited.pos.json
	--channelA=Ch488+561+647nm
	--camA=cam1
	--channelB=Ch488+561+647nm
	--camB=cam1
	*/

	public static ArrayList< Tuple2< String, String > > overlappingStacks(
			final List<String> ids,
			final HashMap< String, MetaData > meta )
	{
		final ArrayList< Tuple2< String, String > > pairs = new ArrayList<>();

		for ( int a = 0; a < ids.size() - 1; ++a )
			for ( int b = a + 1; b < ids.size(); ++b )
			{
				MetaData metaA = meta.get( ids.get( a ) );
				MetaData metaB = meta.get( ids.get( b ) );

				final Interval intervalA = interval( metaA );
				final Interval intervalB = interval( metaB );

				Interval intersection =  Intervals.intersect(intervalA, intervalB);

				boolean contains = true;

				for ( int d = 0; d < intervalA.numDimensions(); ++d )
					if ( intersection.dimension( d ) <= 0 )
						contains = false;

				if ( contains )
					pairs.add( new Tuple2<>( ids.get( a ), ids.get( b ) ) );
			}

		return pairs;
	}

	public static Interval interval( final MetaData meta )
	{
		final long[] min = new long[ meta.position.length ];
		final long[] max = new long[ meta.position.length ];

		for ( int d = 0; d < min.length; ++d )
		{
			min[ d ] = meta.position[ d ];
			max[ d ] = meta.position[ d ] + meta.size[ d ] - 1;
		}

		return new FinalInterval(min, max);
	}

	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException {

		System.out.println(Arrays.toString(excludeIds.toArray()));

		final N5Reader n5 = new N5FSReader(n5Path);

		final SparkConf conf = new SparkConf().setAppName("SparkPaiwiseStitchAllSlabs");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		List<String> ids = SparkPaiwiseAlignChannelsGeoAll.getIds(n5);
		Collections.sort( ids );

		//final double avgShear = 13; //TIM
		final double avgShear = 3.5; //JAYARAM

		// Tim's data
		//final HashMap< String, MetaData > meta = SparkPairwiseStitchSlabs.readPositionMetaData( positionFile );
		//final ArrayList< Tuple2< String, String > > pairs = overlappingStacks( ids, meta );

		// Jayaram's data
		final HashMap< String, MetaData > meta = SparkPairwiseStitchSlabs.readPositionMetaData( positionFile );
		List< Tuple2< String, String > > pairs = overlappingStacks( ids, meta );

		//final ArrayList< Tuple2< String, String > > pairs = new ArrayList<>();
		//for ( int i = 0; i < ids.size() - 1; ++i )
			//pairs.add( new Tuple2<>(ids.get(i), ids.get(i+1)) );

		int i = 0;

		//pairs = pairs.stream().filter( pair -> pair._1().contains( "000") && pair._2().contains( "014" ) ).collect( Collectors.toList() );

		for ( final Tuple2< String, String > pair : pairs )
			System.out.println( ++i + ": " + pair._1() + " <> " + pair._2() );

		//System.exit( 0 );

		final JavaRDD<Tuple2< String, String >> rddIds = sc.parallelize( pairs );

		final JavaPairRDD<Tuple2< String, String >, AlignStatistics > rddResults = rddIds.mapToPair( pair -> {

			final AlignStatistics result = 
					SparkPairwiseStitchSlabs.align( positionFile, avgShear, n5Path, pair._1(), pair._2(), channelA, channelB, camA, camB, blocksize );

			return new Tuple2<>(pair, result );
		});

		rddResults.cache();

		final ArrayList<Tuple2<Tuple2< String, String >, AlignStatistics > > results = new ArrayList<>();
		results.addAll( rddResults.collect() );

		Collections.sort( results, (o1, o2 ) -> { return o1._1()._1().compareTo( o2._1()._1() ); } );

		for ( final Tuple2<Tuple2< String, String >, AlignStatistics > tuple : results )
			System.out.println( tuple._1()._1() + "<>" + tuple._1()._2() + ": " + tuple._2() );

		sc.close();

		System.out.println("Done.");

		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new SparkPaiwiseStitchAllSlabs()).execute(args));
	}
}
