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

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Re-save the height field to a new location for use in the wafer_53 multi-SEM pipeline.
 * For min surfaces: check if the height field is 0 everywhere (issue a warning if not).
 * For max surfaces: smooth with gaussian filter.
 *
 * @author Michael Innerberger
 */
@Command(name = "resave-heightfield")
public class ResaveMultiSemHeightField implements Callable<Void>, Serializable {

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

	public ResaveMultiSemHeightField() {
	}

	public ResaveMultiSemHeightField(final String n5Path,
									 final String n5Out,
									 final String fieldGroup,
									 final String fieldGroupOut,
									 final int[] downsamplingFactors) {
		this.n5Path = n5Path;
		this.n5Out = n5Out;
		this.fieldGroup = fieldGroup;
		this.fieldGroupOut = fieldGroupOut;
		this.downsamplingFactors = downsamplingFactors;
	}

	private static final double SIGMA = 2.0;

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new ResaveMultiSemHeightField()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {
		final ExecutorService service = Executors.newCachedThreadPool();
		saveSmoothedHeightField(this, service);
		service.shutdown();
		return null;
	}

	public static void saveSmoothedHeightField(final ResaveMultiSemHeightField parameters,
											   final ExecutorService optionalExecService)
			throws IOException, ExecutionException, InterruptedException {

		final N5Reader sourceN5 = new N5FSReader(parameters.n5Path);
		final String n5OutputPath = (parameters.n5Out == null) ? parameters.n5Path : parameters.n5Out;
		final N5Writer targetN5 = new N5FSWriter(n5OutputPath);

		// load the min height field and simply resave (check that everything is 0)
		final String minHeightField = parameters.fieldGroup + "/min";
		final String minHeightFieldOut = parameters.fieldGroupOut + "/min";

		confirmGroupExists(sourceN5, minHeightField);
		confirmGroupDoesntExist(sourceN5, minHeightFieldOut);

		System.out.println("LOADING height field " + buildN5Path(parameters.n5Path, minHeightField));
		RandomAccessibleInterval<FloatType> heightFieldSource = N5Utils.open(sourceN5, minHeightField);

		for (final FloatType t : Views.iterable(heightFieldSource)) {
			if (t.get() != 0.0f) {
				System.out.println("WARNING: heightfield is not 0 everywhere.");
				break;
			}
		}

		System.out.println("SAVING height field " + buildN5Path(n5OutputPath, minHeightFieldOut));
		DatasetAttributes attributes = sourceN5.getDatasetAttributes(minHeightField);
		if (optionalExecService == null) {
			N5Utils.save(heightFieldSource, targetN5, minHeightFieldOut, attributes.getBlockSize(), attributes.getCompression());
		} else {
			N5Utils.save(heightFieldSource, targetN5, minHeightFieldOut, attributes.getBlockSize(), attributes.getCompression(), optionalExecService);
		}

		final double minAvg = sourceN5.getAttribute(minHeightField, "avg", double.class);
		System.out.println("Setting min attributes avg=" + minAvg + ", downsamplingFactors=" + Arrays.toString(parameters.downsamplingFactors));
		targetN5.setAttribute(minHeightFieldOut, "avg", minAvg);
		targetN5.setAttribute(minHeightFieldOut, "downsamplingFactors", parameters.downsamplingFactors);

		// load the max height field, smooth, and resave
		final String maxHeightField = parameters.fieldGroup + "/max";
		final String maxHeightFieldOut = parameters.fieldGroupOut + "/max";

		confirmGroupExists(sourceN5, maxHeightField);
		confirmGroupDoesntExist(sourceN5, maxHeightFieldOut);

		System.out.println("LOADING height field " + buildN5Path(parameters.n5Path, maxHeightField));
		heightFieldSource = N5Utils.open(sourceN5, maxHeightField);

		final Img<FloatType> heightField = new ArrayImgFactory<>(new FloatType()).create(heightFieldSource);
		if (optionalExecService == null) {
			Util.copy(heightFieldSource, heightField);
		} else {
			Util.copy(heightFieldSource, heightField, optionalExecService, true);
		}

		System.out.println("SMOOTHING heightfield");
		Gauss3.gauss(SIGMA, Views.extendBorder(heightField), heightField);

		System.out.println("SAVING height field " + buildN5Path(n5OutputPath, maxHeightFieldOut));
		final double maxAvg = sourceN5.getAttribute(maxHeightField, "avg", double.class);
		attributes = sourceN5.getDatasetAttributes(maxHeightField);

		if (optionalExecService == null) {
			N5Utils.save(heightField, targetN5, maxHeightFieldOut, attributes.getBlockSize(), attributes.getCompression());
		} else {
			N5Utils.save(heightField, targetN5, maxHeightFieldOut, attributes.getBlockSize(), attributes.getCompression(), optionalExecService);
		}

		System.out.println( "Setting max attributes avg=" + maxAvg + ", downsamplingFactors=" + Arrays.toString(parameters.downsamplingFactors));
		targetN5.setAttribute(maxHeightFieldOut, "avg", maxAvg);
		targetN5.setAttribute(maxHeightFieldOut, "downsamplingFactors", parameters.downsamplingFactors);

		sourceN5.close();
		targetN5.close();
	}

	private static void confirmGroupExists(final N5Reader n5, final String dataset)
			throws IOException {
		if (!n5.exists(dataset)) {
			throw new IOException("heightfield dataset does not exist: " + buildN5Path(n5.getURI().getPath(), dataset));
		}
	}

	private static void confirmGroupDoesntExist(final N5Reader n5, final String dataset)
			throws IOException {
		if (n5.exists(dataset)) {
			throw new IOException("heightfield dataset already exists: " + buildN5Path(n5.getURI().getPath(), dataset));
		}
	}

	private static String buildN5Path(final String n5Path,
									  final String group) {
		return n5Path + (group.startsWith("/") ? "" : "/") + group;
	}
}
