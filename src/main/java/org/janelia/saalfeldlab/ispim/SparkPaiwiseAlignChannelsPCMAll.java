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
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import com.google.common.reflect.TypeToken;

import loci.formats.FormatException;
import mpicbg.models.AffineModel3D;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel3D;
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
		name = "SparkPaiwiseAlignChannelsPCMAll",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Align all pairs of channels using phase correlation")
public class SparkPaiwiseAlignChannelsPCMAll implements Callable<Void>, Serializable {

	private static final long serialVersionUID = 6708886268386777152L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--channelA", required = true, description = "Channel A key, e.g. Ch488+561+647nm")
	private String channelA = null;

	@Option(names = "--channelB", required = true, description = "Channel B key, e.g. Ch405nm")
	private String channelB = null;

	@Option(names = "--camA", required = true, description = "CamA key, e.g. cam4")
	private String camA = null;

	@Option(names = "--camB", required = true, description = "CamB key, e.g. cam4")
	private String camB = null;

	@Option(names = {"-b", "--blocksize"}, required = false, description = "blocksize in z for point extraction (default: 40)")
	private int blocksize = 20;

	@Option(names = "--rThreshold", required = false, description = "correlation threshold (default: 0.5)")
	private double rThreshold = 0.5;

	@Option(names = "--excludeIds", split=",", required = false, description = "ids to be exluded")
	private HashSet<String> excludeIds = new HashSet<>();

	@Option(names = "--excludeChannels", split=",", required = false, description = "channels to be exluded")
	private HashSet<String> excludeChannels = new HashSet<>();

	@SuppressWarnings("serial")
	public static List<String> getIds(final N5Reader n5) throws IOException {

		return n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());
	}

	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException {

		System.out.println(Arrays.toString(excludeIds.toArray()));

		final N5Reader n5 = new N5FSReader(n5Path);

		final SparkConf conf = new SparkConf().setAppName("SparkPaiwiseAlignChannelsPCMAll");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		final JavaRDD<String> rddIds = sc.parallelize(getIds(n5));

		final JavaPairRDD<String, Tuple3<Integer, Double, Double > > rddResults = rddIds.mapToPair( id -> {
			
			final ArrayList< PointMatch > matches = SparkPaiwiseAlignChannelsPCM.alignChannels( n5Path, id, channelA, channelB, camA, camB, blocksize, rThreshold, null, 1 );
	
			double errorTranslation = -1.0;
			double errorAffine = -1.0;
			int numInliers = -1;

			try
			{
				if ( matches != null && matches.size() >= 4 )
				{
					numInliers = matches.size();
					final TranslationModel3D translation = new TranslationModel3D();
					translation.fit( matches );
					errorTranslation = PointMatch.meanDistance( matches );
					System.out.println( "translation(" + errorTranslation + ")" + translation );
					
					final AffineModel3D affine = new AffineModel3D();
					affine.fit( matches );
					errorAffine = PointMatch.meanDistance( matches );
					System.out.println( "affine (" + errorAffine + "): " + affine );
				}
			}
			catch ( Exception e ) {}

			return new Tuple2<>(id, new Tuple3<>( numInliers, errorTranslation, errorAffine ) );
		});

		rddResults.cache();

		final ArrayList<Tuple2<String, Tuple3<Integer, Double, Double >> > results = new ArrayList<>();
		results.addAll( rddResults.collect() );

		Collections.sort( results, (o1, o2 ) -> { return o1._1().compareTo( o2._1() ); } );

		for ( final Tuple2<String, Tuple3<Integer, Double, Double >> tuple : results )
			System.out.println( tuple._1() + ": inliers=" + tuple._2()._1() + ", errT: " + tuple._2()._2() + ", errA=" + tuple._2()._3( ));

		sc.close();

		System.out.println("Done.");

		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new SparkPaiwiseAlignChannelsPCMAll()).execute(args));
	}
}
