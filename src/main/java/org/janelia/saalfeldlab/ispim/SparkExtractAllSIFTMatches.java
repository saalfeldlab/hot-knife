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
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.apache.spark.SparkConf;
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
		name = "SparkExtractAllSIFTMatches",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Extract SIFT matches from all iSPIM camera series in a project")
public class SparkExtractAllSIFTMatches implements Callable<Void>, Serializable {

	private static final long serialVersionUID = 6708886268386777152L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = {"-d", "--distance"}, required = false, description = "max distance for two slices to be compared, e.g. 10")
	private int distance = 10;

	@Option(names = "--minIntensity", required = false, description = "min intensity")
	private double minIntensity = 0;

	@Option(names = "--maxIntensity", required = false, description = "max intensity")
	private double maxIntensity = 4096;

	@Option(names = "--maxEpsilon", required = true, description = "residual threshold for filter in world pixels")
	private double maxEpsilon = 50.0;

	@Option(names = "--iterations", required = false, description = "number of iterations for RANSAC (default 1000)")
	private int numIterations = 1000;

	@Option(names = "--minNumInliers", required = false, description = "minimal number of inliers for RANSAC (default 10)")
	private int minNumInliers = 10;

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException {

		final ArrayList<String> ids;
		final HashMap<String, HashMap<String, double[]>> camTransforms;
		final N5Reader n5 = new N5FSReader(n5Path);

		camTransforms = n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, double[]>>>() {}.getType());
		ids = n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());

		final SparkConf conf = new SparkConf().setAppName("SparkExtractSIFTMatches");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		final Stream<String[]> idsChannelsCams =
				ids.stream().flatMap(
						id -> camTransforms.entrySet().stream().flatMap(
								channelEntry -> channelEntry.getValue().keySet().stream().map(
										cam -> new String[] {channelEntry.getKey(), cam})).map(
												c -> new String[] {id, c[0], c[1]}));

		idsChannelsCams.forEach(idc -> {
//			if (Integer.parseInt(idc[0].replace("Pos", "")) > 47 && n5.exists(n5.groupPath(idc)))
			if (n5.exists(n5.groupPath(idc)))
				try {
					System.out.println("Extracting matches for " + n5.groupPath(idc));
					SparkExtractSIFTMatches.extractStackSIFTMatches(
							sc,
							n5Path,
							idc[0],
							idc[1],
							idc[2],
							distance,
							minIntensity,
							maxIntensity,
							maxEpsilon,
							numIterations,
							minNumInliers);
				} catch (IOException | FormatException e) {
					System.err.println("Failed to extract features for " + n5Path + " : " + n5.groupPath(idc) + " because:");
					e.printStackTrace(System.err);
				}
		});

		sc.close();

		System.out.println("Done.");

		return null;
	}

	public static final void main(final String... args) {

		System.exit(new CommandLine(new SparkExtractAllSIFTMatches()).execute(args));
	}
}
