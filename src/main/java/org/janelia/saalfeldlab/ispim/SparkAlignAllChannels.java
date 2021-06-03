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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import com.google.common.reflect.TypeToken;

import loci.formats.FormatException;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Align a 3D N5 dataset.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command(
		name = "SparkAlignAllChannels",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Align all channels with matches from all its camera images")
public class SparkAlignAllChannels implements Callable<Void>, Serializable {

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
	public static List<String[]> getIdsChannels(final N5Reader n5) throws IOException {

		final HashMap<String, HashMap<String, double[]>> camTransforms = n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, double[]>>>() {}.getType());
		final ArrayList<String> ids = n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());

		final Stream<String[]> idsChannels =
				ids.stream().flatMap(
						id -> camTransforms.keySet().stream().map(
								channel -> new String[] {id, channel}));

		return idsChannels.collect(Collectors.toList());
	}

	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException {

		System.out.println(Arrays.toString(excludeIds.toArray()));

		final N5Reader n5 = new N5FSReader(n5Path);

		final SparkConf conf = new SparkConf().setAppName("SparkAlignAllChannels");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		final JavaRDD<String[]> rddIdsChannels = sc.parallelize(
				getIdsChannels(n5));

		rddIdsChannels.foreach(idc -> {
			if (!excludeIds.contains(idc[0]) && !excludeChannels.contains(idc[1])) {
				System.out.println("Aligning " + idc[0] + "/" + idc[1]);
				new CommandLine(new AlignChannel()).execute(
						new String[] {
								"--n5Path", n5Path,
								"--id", idc[0],
								"--channel", idc[1],
								"--distance", "" + distance,
								"--lambdaModel", "" + lambdaModel,
								"--maxEpsilon", "" + maxEpsilon,
								"--iterations", "" + numIterations});
			}
		});

		sc.close();

		System.out.println("Done.");

		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new SparkAlignAllChannels()).execute(args));
	}
}
