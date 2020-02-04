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
package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.BdvFunctions;
import bdv.util.volatiles.VolatileViews;
import ij.ImageJ;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.Views;
import net.preibisch.surface.Test;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkSurfaceFit implements Callable<Void>{

	@Option(names = {"--n5Path"}, required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"-i", "--n5CostInput"}, required = true, description = "N5 input group for cost, e.g. /cost-0")
	private String inGroup = null;

	@Option(names = {"-o", "--n5SurfaceOutput"}, required = true, description = "N5 output group for , e.g. /surface-1")
	private String outGroup = null;

	@Option(names = {"-s", "--scale"}, description = "scale index, e.g. 10")
	private int scaleIndex = 0;



	@Override
	public Void call() throws IOException {

		final N5Reader n5 = new N5FSReader(n5Path);

		final SparkConf conf = new SparkConf().setAppName(getClass().getCanonicalName());
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		final String dataset = inGroup + "/s" + scaleIndex;
		final RandomAccessibleInterval<UnsignedByteType> cost = N5Utils.openVolatile(n5, inGroup + "/s" + scaleIndex);

		final double[] downsamplingFactors = n5.getAttribute(dataset, "downsamplingFactors", double[].class);
		final double dzScale = downsamplingFactors[0] / downsamplingFactors[1];

		System.out.println(dzScale);

		final Img<IntType> surface = Test.process2(Views.permute(cost, 1, 2), (int)Math.round(dzScale), 0, Integer.MAX_VALUE);
		BdvFunctions.show(VolatileViews.wrapAsVolatile(cost), "");
		ImageJFunctions.show(surface);

		sc.close();

		return null;
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		new ImageJ();

		CommandLine.call(new SparkSurfaceFit(), args);
	}
}
