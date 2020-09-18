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
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
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
		name = "SparkFixStackAttributes",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Fix broken JSON for all stacks")
public class SparkFixStackAttributes implements Callable<Void>, Serializable {

	private static final long serialVersionUID = -8233608726817067258L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException {

		final ArrayList<String> ids;
		final HashMap<String, HashMap<String, double[]>> camTransforms;
		{
			final N5Reader n5 = new N5FSReader(n5Path);

			camTransforms = n5.getAttribute(
					"/",
					"camTransforms",
					new TypeToken<HashMap<String, HashMap<String, double[]>>>() {}.getType());
			ids = n5.getAttribute(
					"/",
					"stacks",
					new TypeToken<ArrayList<String>>() {}.getType());
		}

		final SparkConf conf = new SparkConf().setAppName("SparkFixStackAttributes");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");


		final JavaRDD<String> rddIds = sc.parallelize(ids);

		rddIds.foreach(id -> {

			final N5FSWriter n5Writer = new N5FSWriter(n5Path);
			for (final Entry<String, HashMap<String, double[]>> channelEntry : camTransforms.entrySet()) {
				final String channel = channelEntry.getKey();
				for (final String cam : channelEntry.getValue().keySet()) {
					final String groupName = n5Writer.groupPath(id, channel, cam);
					System.out.println(groupName);
					if (n5Writer.exists(groupName)) {
						final ArrayList<Slice> slices = n5Writer.getAttribute(
								groupName,
								"slices",
								new TypeToken<ArrayList<Slice>>() {}.getType());
						n5Writer.setAttribute(groupName,
								"slices",
								slices);
					}
				}
			}
		});

		sc.close();

		System.out.println("Done.");

		return null;
	}

	public static final void main(final String... args) {

		System.exit(new CommandLine(new SparkFixStackAttributes()).execute(args));
	}
}
