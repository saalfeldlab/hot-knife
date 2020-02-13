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

import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ViewFlattenedSlab2 implements Callable<Void>{

	@Option(names = {"--n5Path"}, required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"--n5FieldPath"}, required = true, description = "N5 output path for height fields, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5FieldPath = null;

	@Option(names = {"--n5Raw"}, required = true, description = "N5 input group for raw, e.g. /raw")
	private String rawGroup = null;

	@Option(names = {"--n5Field"}, required = true, description = "N5 fields group, e.g. /surface-1")
	private String fieldGroup = null;

	@Option(names = {"--scale"}, required = true, description = "initial scale index, e.g. 1")
	private int[] scaleIndices = null;

	private boolean useVolatile = true;

	private int padding = 1000;

	FinalVoxelDimensions voxelDimensions = new FinalVoxelDimensions("px", new double[]{1, 1, 1});

	public static final FunctionRandomAccessible<DoubleType> zPlane(
			final double offset,
			final double scale,
			final double stretch) {

		return new FunctionRandomAccessible<>(
				3,
				(location, value) -> {
					final double z = location.getDoublePosition(2);
					value.set(scale / (stretch * Math.abs(z - offset) + 1));
				},
				DoubleType::new);
	}

	public static final FunctionRandomAccessible<DoubleType> zRange(
			final double min,
			final double max,
			final double scale,
			final double stretch) {

		return new FunctionRandomAccessible<>(
				3,
				(location, value) -> {
					final double z = location.getDoublePosition(2);
					value.set(
							scale / (stretch * Math.abs(z - min) + 1) +
							scale / (stretch * Math.abs(z - max) + 1));
				},
				DoubleType::new);
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new ViewFlattenedSlab2(), args);
	}


	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final N5Reader n5 = new N5FSReader(n5Path);
		final N5FSReader n5Field = new N5FSReader(n5FieldPath);

		/* visualization */

		/*
		 * raw data
		 */
		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.min(12, Math.max(1, numProc - 2)));

		final int numScales = n5.list(rawGroup).length;
		final double[][] scales = new double[numScales][];
		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<UnsignedByteType>[] rawMipmaps = new RandomAccessibleInterval[numScales];
		for (int s = 0; s < numScales; ++s) {

			final String mipmapName = rawGroup + "/s" + s;
			rawMipmaps[s] = N5Utils.openVolatile(n5, mipmapName);
			double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
			if (scale == null)
				scale = new double[] {1, 1, 1};

			scales[s] = scale;
		}

		final RandomAccessibleIntervalMipmapSource<UnsignedByteType> rawMipmapSource = new RandomAccessibleIntervalMipmapSource<>(
				rawMipmaps,
				new UnsignedByteType(),
				scales,
				voxelDimensions,
				"raw");

		final BdvOptions options = BdvOptions.options().screenScales(new double[] {0.5}).numRenderingThreads(10);

		BdvStackSource<?> bdv = null;

		bdv = Show.mipmapSource(useVolatile ? rawMipmapSource.asVolatile(queue) : rawMipmapSource, bdv, options.addTo(bdv));


		for (final int s : scaleIndices) {

			/* raw */
			final String groupName = fieldGroup + "/s" + s;
			final String minFieldName = groupName + "/min";
			final String maxFieldName = groupName + "/max";

			final RandomAccessibleInterval<FloatType> minField = N5Utils.open(n5Field, minFieldName);
			final RandomAccessibleInterval<FloatType> maxField = N5Utils.open(n5Field, maxFieldName);

			final double[] downsamplingFactors = n5Field.getAttribute(groupName, "downsamplingFactors", double[].class);
			final double minAvg = n5Field.getAttribute(minFieldName, "avg", double.class);
			final double maxAvg = n5Field.getAttribute(maxFieldName, "avg", double.class);

			final double min = (minAvg + 0.5) * downsamplingFactors[2] - 0.5;
			final double max = (maxAvg + 0.5) * downsamplingFactors[2] - 0.5;

			final FlattenTransform<DoubleType> flattenTransform = new FlattenTransform<>(
					Transform.scaleAndShiftHeightFieldAndValues(minField, downsamplingFactors),
					Transform.scaleAndShiftHeightFieldAndValues(maxField, downsamplingFactors),
					min,
					max);
			final AffineTransform3D permutation = new AffineTransform3D();
			permutation.set(
					1, 0, 0, 0,
					0, 0, 1, 0,
					0, 1, 0, 0);
			final RealTransformSequence tfs = new RealTransformSequence();
			tfs.add(permutation);
			tfs.add(flattenTransform.inverse());

			final RandomAccessibleIntervalMipmapSource<UnsignedByteType> mipmapSource = Show.createTransformedMipmapSource(tfs, rawMipmaps, scales, voxelDimensions, "flattened raw " + s);

			final Source<?> volatileMipmapSource;
			if (useVolatile)
				volatileMipmapSource = mipmapSource.asVolatile(queue);
			else
				volatileMipmapSource = mipmapSource;

			bdv = Show.mipmapSource(volatileMipmapSource, bdv, options.addTo(bdv));


			/* surfaces */
			final IntervalView<DoubleType> zRange = Views.interval(
					zRange(minAvg, maxAvg, 255, 1),
					new long[] {0, 0, Math.round(min) - padding},
					new long[] {rawMipmaps[0].dimension(0), rawMipmaps[0].dimension(2), Math.round(max) + padding});

			final RandomAccessibleInterval<DoubleType> transformedSource =
					Transform.createTransformedInterval(
							zRange,
							zRange,
							flattenTransform,
							new DoubleType(0));

			bdv = BdvFunctions.show(Views.permute(transformedSource, 1, 2), "flat field " + s, BdvOptions.options().addTo(bdv));
			bdv.setDisplayRangeBounds(0, 255);
			bdv.setDisplayRange(0, 255);
			bdv.setColor(new ARGBType(0xff00ff00));
		}

		return null;
	}
}
