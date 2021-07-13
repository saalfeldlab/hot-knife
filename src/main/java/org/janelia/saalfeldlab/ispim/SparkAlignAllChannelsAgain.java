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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import com.google.common.reflect.TypeToken;
import com.google.gson.GsonBuilder;

import loci.formats.FormatException;
import net.imglib2.realtransform.AffineTransform2D;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import scala.Tuple2;

/**
 * Align all channels again, initialized with the global average shear in x and y.
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command(
		name = "SparkAlignAllChannelsAgain",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Align all channels again, initialized with the global average shear in x and y")
public class SparkAlignAllChannelsAgain implements Callable<Void>, Serializable {

	private static final long serialVersionUID = 6708886268386777152L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = {"-d", "--distance"}, required = false, description = "max distance for two slices to be compared, e.g. 10")
	private int distance = 10;

	//@Option(names = "--minIntensity", required = false, description = "min intensity")
	//private double minIntensity = 0;

	//@Option(names = "--maxIntensity", required = false, description = "max intensity")
	//private double maxIntensity = 4096;

	@Option(names = "--lambdaModel", required = false, description = "lambda for rigid regularizer in model")
	private double lambdaModel = 0.1;

	//@Option(names = "--lambdaFilter", required = false, description = "lambda for rigid regularizer in filter")
	//private double lambdaFilter = 0.1;

	@Option(names = "--maxEpsilon", required = true, description = "residual threshold for filter in world pixels")
	private double maxEpsilon = 50.0;

	@Option(names = "--iterations", required = false, description = "number of iterations")
	private int numIterations = 2000;

	@Option(names = "--excludeIds", split=",", required = false, description = "ids to be exluded")
	private HashSet<String> excludeIds = new HashSet<>();

	@Option(names = "--excludeChannels", split=",", required = false, description = "channels to be exluded")
	private HashSet<String> excludeChannels = new HashSet<>();

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException {

		System.out.println(Arrays.toString(excludeIds.toArray()));

		final SparkConf conf = new SparkConf().setAppName("SparkAlignAllChannelsAgain");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		final JavaRDD<String[]> rddIdsChannels = sc.parallelize(
				SparkAlignAllChannels.getIdsChannels(new N5FSReader(n5Path)));

		final JavaPairRDD<String[], double[]> rddShears = rddIdsChannels.mapToPair(idc -> {

			if (excludeIds.contains(idc[0]))
			{
				System.out.println( "Ignoring " + idc[0]);
				return null;
			}

			final N5Reader n5 = new N5FSReader(
					n5Path,
					new GsonBuilder().registerTypeAdapter(
							AffineTransform2D.class,
							new AffineTransform2DAdapter()));

			final ArrayList<AffineTransform2D> transforms = n5.getAttribute(n5.groupPath(idc), "transforms", new TypeToken<ArrayList<AffineTransform2D>>() {}.getType());

			if ( transforms == null )
			{
				// empty stack most likely
				return null;
			}

			//if ( idc[0].equals("Pos055"))
			//for ( final AffineTransform2D t : transforms )
			//System.out.println( t );
			final HashSet<AffineTransform2D> consider = new HashSet<>(transforms);

			final double[] shearX = AlignChannel.fit(transforms, consider, 0, 2);
			final double[] shearY = AlignChannel.fit(transforms, consider, 1, 2);

			System.out.println("Averaging shear " + idc[0] + "/" + idc[1]);
			System.out.println("(" + idc[0] + "/" + idc[1] + ") x : a = " + shearX[0] + ", b = " + shearX[1]);
			System.out.println("(" + idc[0] + "/" + idc[1] + ") y : a = " + shearY[0] + ", b = " + shearY[1]);

			return new Tuple2<>(idc, new double[] {shearX[0], shearY[0]});
		});

		final HashMap<String, double[]> shearSums = rddShears.filter(a -> a != null).aggregate(
				new HashMap<String, double[]>(),
				(map, a) -> {
					final HashMap<String, double[]> m = new HashMap<>();
					final double[] shear = a._2();
					m.put(a._1()[1], new double[] {shear[0], shear[1], 1});
					return m;
				},
				(map1, map2) -> {
					final HashSet<String> keys = new HashSet<>(map1.keySet());
					keys.addAll(map2.keySet());
					for (final String key : keys) {
						final double[] value1 = map1.get(key);
						final double[] value2 = map2.get(key);
						if (value1 == null)
							map1.put(key, value2);
						else if (value2 != null)
							Arrays.setAll(value1, i -> value1[i] + value2[i]);
					}
					return map1;
				});

		System.out.println("Average shear");
		for (final Entry<String, double[]> entry : shearSums.entrySet()) {

			final double[] value = entry.getValue();
			System.out.println("  " + entry.getKey() + " : x = " + value[0] / value[2] + ", y = " + value[1] / value[2]);
		}

		rddIdsChannels.foreach(idc -> {
			if (!excludeIds.contains(idc[0]) && !excludeChannels.contains(idc[1])) {
				System.out.println("Aligning " + idc[0] + "/" + idc[1]);
				final double[] channelShear = shearSums.get(idc[1]);
				final double shearX = channelShear[0] / channelShear[2];
				final double shearY = channelShear[1] / channelShear[2];
				new CommandLine(new AlignChannel()).execute(
						new String[] {
								"--n5Path", n5Path,
								"--id", idc[0],
								"--channel", idc[1],
								"--distance", "" + distance,
								"--lambdaModel", "" + lambdaModel,
								"--maxEpsilon", "" + maxEpsilon,
								"--iterations", "" + numIterations,
								"--shearX", "" + shearX,
								"--shearY", "" + shearY});
			}
		});


		sc.close();

		System.out.println("Done.");

		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new SparkAlignAllChannelsAgain()).execute(args));
	}
}
