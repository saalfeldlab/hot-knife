/*
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
package org.janelia.saalfeldlab.hotknife.tools;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Re-save the height field to a new location for use in the wafer_53 multi-SEM pipeline.
 * For min surfaces: check if the height field is 0 everywhere (issue a warning if not).
 * For max surfaces: smooth with gaussian filter.
 *
 * @author Michael Innerberger
 */
@Command(name = "resave-heightfield")
public class ResaveMultiSemHeightField implements Callable<Void>{

	@Option(names = {"-i", "--n5"}, required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"-j", "--n5Out"}, description = "N5 output path for height field, e.g. /nrs/flyem/data/tmp/Z0115-22.n5 (default: same as input)")
	private String n5Out = null;

	@Option(names = {"-f", "--fieldGroup"}, required = true, description = "N5 group containing min and max field datasets, e.g. /surface/s1")
	private String fieldGroup = null;

	@Option(names = {"-g", "--fieldGroupOut"}, required = true, description = "N5 group to save, overrides if the same as input, e.g. /surface/s1")
	private String fieldGroupOut = null;

	@Option(names = {"-s", "--scale"}, required = true,  split=",", description = "downsampling factors, e.g. 6,6,1")
	private int[] downsamplingFactors = null;

	private static final double SIGMA = 2.0;

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		CommandLine.call(new ResaveMultiSemHeightField(), args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final N5Reader sourceN5 = new N5FSReader(n5Path);
		final String n5OutputPath = (n5Out == null) ? n5Path : n5Out;
		final N5Writer targetN5 = new N5FSWriter(n5OutputPath);

		// load the min height field and simply resave (check that everything is 0)
		final String minHeightField = fieldGroup + "/min";
		final String minHeightFieldOut = fieldGroupOut + "/min";

		checkIfGroupExists(sourceN5, minHeightField);
		checkIfGroupDoesntExist(sourceN5, minHeightFieldOut);

		System.out.println("LOADING height field " + n5Path + ":/" + minHeightField);
		RandomAccessibleInterval<FloatType> heightFieldSource = N5Utils.open(sourceN5, minHeightField);

		for (final FloatType t : Views.iterable(heightFieldSource)) {
			if (t.get() != 0.0f) {
				System.out.println("WARNING: heightfield is not 0 everywhere.");
				break;
			}
		}

		System.out.println("SAVING height field " + n5OutputPath + ":/" + minHeightFieldOut);
		DatasetAttributes attributes = sourceN5.getDatasetAttributes(minHeightField);
		N5Utils.save(heightFieldSource, targetN5, minHeightFieldOut, attributes.getBlockSize(), attributes.getCompression());

		// load the max height field, smooth, and resave
		final String maxHeightField = fieldGroup + "/max";
		final String maxHeightFieldOut = fieldGroupOut + "/max";

		checkIfGroupExists(sourceN5, maxHeightField);
		checkIfGroupDoesntExist(sourceN5, maxHeightFieldOut);

		System.out.println("LOADING height field " + n5Path + ":/" + maxHeightField);
		heightFieldSource = N5Utils.open(sourceN5, maxHeightField);

		final ExecutorService service = Executors.newCachedThreadPool();
		final Img<FloatType> heightField = new ArrayImgFactory<>(new FloatType()).create(heightFieldSource);
		Util.copy(heightFieldSource, heightField, service);

		System.out.println("SMOOTHING heightfield");
		Gauss3.gauss(SIGMA, Views.extendBorder(heightField), heightField);

		final ExecutorService exec = Executors.newFixedThreadPool(4);

		System.out.println("SAVING height field " + n5OutputPath + ":/" + maxHeightFieldOut);
		final double avg = sourceN5.getAttribute(maxHeightField, "avg", double.class);
		attributes = sourceN5.getDatasetAttributes(maxHeightField);

		//N5Utils.save(heightField, targetN5, maxHeightFieldOut, attributes.getBlockSize(), attributes.getCompression(), exec);

		N5Utils
				.save(
						heightField,
						targetN5,
						maxHeightFieldOut,
						new int[]{1024, 1024},
						new GzipCompression(),
						exec);

		targetN5.setAttribute(maxHeightFieldOut, "avg", avg);
		targetN5.setAttribute(maxHeightFieldOut, "downsamplingFactors", downsamplingFactors);

		exec.shutdown();
		service.shutdown();
		sourceN5.close();
		return null;
	}

	private static void checkIfGroupExists(final N5Reader n5, final String dataset) {
		if (!n5.exists(dataset)) {
			System.out.println("heightfield dataset does not exist: " + n5 + "/" + dataset);
			System.exit(0);
		}
	}

	private static void checkIfGroupDoesntExist(final N5Reader n5, final String dataset) {
		if (n5.exists(dataset)) {
			System.out.println("heightfield dataset already exists: " + n5 + "/" + dataset);
			System.exit(0);
		}
	}
}
