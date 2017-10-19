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
package org.janelia.saalfeldlab.hotknife.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Writer;

import net.imglib2.realtransform.RealTransform;
import scala.Tuple2;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Spark {

	private Spark() {}

	/**
	 * Copies a list of transforms.
	 *
	 * TODO Parallelizes over the elements of the list which is not very useful
	 * for short lists.  Parallelize over blocks instead.
	 *
	 * @param sc
	 * @param n5Path
	 * @param inDatasetNames
	 * @param outDatasetNames
	 */
	public static void copyTransforms(
			final JavaSparkContext sc,
			final String n5Path,
			final List<String> inDatasetNames,
			final List<String> outDatasetNames) {

		final ArrayList<Tuple2<String, String>> datasetNames = new ArrayList<>();
		for (int i = 0; i < inDatasetNames.size(); ++i)
			datasetNames.add(new Tuple2<String, String>(inDatasetNames.get(i), outDatasetNames.get(i)));

		final JavaPairRDD<String, String> rddDatasetNames = sc.parallelizePairs(datasetNames);

		rddDatasetNames.foreach(
				tuple -> {
					final N5Writer n5 = N5.openFSWriter(n5Path);
					final RealTransform transform = Transform.loadScaledTransform(n5, tuple._1());
					final double[] boundsMin = n5.getAttribute(tuple._1(), "boundsMin", double[].class);
					final double[] boundsMax = n5.getAttribute(tuple._1(), "boundsMax", double[].class);
					final double scale = n5.getAttribute(tuple._1(), "scale", double.class);
					Transform.saveScaledTransform(
							n5,
							tuple._2(),
							transform,
							scale,
							boundsMin,
							boundsMax);
				});
	}
}
