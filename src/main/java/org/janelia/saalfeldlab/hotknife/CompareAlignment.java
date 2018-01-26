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
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class CompareAlignment {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "-i1", aliases = {"--n5Group1"}, required = false, usage = "N5 group, e.g. /align-0")
		private String group1 = null;

		@Option(name = "-i2", aliases = {"--n5Group2"}, required = false, usage = "N5 group, e.g. /align-1")
		private String group2 = null;

		@Option(name = "--scaleIndex", required = true, usage = "scale index for visualization, e.g. 4 (means scale = 1.0 / 2^4)")
		private int transformScaleIndex = 0;

		@Option(name = "--max", required = false, usage = "max radius for color intensity visualization")
		private double max = 1;

		@Option(name = "--lambda", required = false, usage = "lambda for color intensity visualization")
		private double lambda = 1;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);

				parsedSuccessfully = true;

			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		/**
		 * @return the n5Path
		 */
		public String getN5Path() {

			return n5Path;
		}

		/**
		 * @return the scaleIndex
		 */
		public int getScaleIndex() {

			return transformScaleIndex;
		}

		/**
		 * @return the max
		 */
		public double getMax() {

			return max;
		}

		/**
		 * @return the max
		 */
		public double getLambda() {

			return lambda;
		}

		/**
		 * @return the group1
		 */
		public String getGroup1() {

			return group1;
		}

		/**
		 * @return the group2
		 */
		public String getGroup2() {

			return group2;
		}
	}


	private static List<RealTransform> loadRealTransforms(
			final N5Reader n5,
			final List<String> datasetNames,
			final String group) throws IOException {

		final String[] transformDatasetNames = n5.getAttribute(group, "transforms", String[].class);

		final RealTransform[] realTransforms = new RealTransform[datasetNames.size()];
		for (int i = 0; i < datasetNames.size(); ++i) {
			realTransforms[i] = Transform.loadScaledTransform(
					n5,
					group + "/" + transformDatasetNames[i]);
		}

		return Arrays.asList(realTransforms);
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		new ImageJ();

		final N5Reader n5 = new N5FSReader(options.getN5Path());

		final int showScaleIndex = options.getScaleIndex();
		final double showScale = 1.0 / (1 << showScaleIndex);

		Bdv bdv = null;

		final List<String> datasetNames1 = Arrays.asList(n5.getAttribute(options.getGroup1(), "datasets", String[].class));
		final double[] boundsMin1 = n5.getAttribute(options.getGroup1(), "boundsMin", double[].class);
		final double[] boundsMax1 = n5.getAttribute(options.getGroup1(), "boundsMax", double[].class);
		final List<RealTransform> realTransforms1 = loadRealTransforms(n5, datasetNames1, options.getGroup1());
		final FinalInterval interval1 = new FinalInterval(Grid.floorScaled(boundsMin1, showScale), Grid.ceilScaled(boundsMax1, showScale));
		final RandomAccessibleInterval<FloatType> stack1 = Transform.createTransformedStack(
				options.getN5Path(),
				datasetNames1,
				showScaleIndex,
				realTransforms1,
				interval1);

		final List<String> datasetNames2 = Arrays.asList(n5.getAttribute(options.getGroup2(), "datasets", String[].class));
		final double[] boundsMin2 = n5.getAttribute(options.getGroup2(), "boundsMin", double[].class);
		final double[] boundsMax2 = n5.getAttribute(options.getGroup2(), "boundsMax", double[].class);
		final List<RealTransform> realTransforms2 = loadRealTransforms(n5, datasetNames2, options.getGroup2());
		final FinalInterval interval2 = new FinalInterval(Grid.floorScaled(boundsMin2, showScale), Grid.ceilScaled(boundsMax2, showScale));
		final RandomAccessibleInterval<FloatType> stack2 = Transform.createTransformedStack(
				options.getN5Path(),
				datasetNames2,
				showScaleIndex,
				realTransforms2,
				interval2);

		final ArrayList<RandomAccessibleInterval<FloatType>> stack2DiffSlices = new ArrayList<>();
		for (int i = 1; i < stack1.dimension(2); ++i) {
			final RandomAccessibleInterval<FloatType> slice = Views.stack(
					Arrays.asList((RandomAccessibleInterval<FloatType>[])new RandomAccessibleInterval[]{
							Views.hyperSlice(stack2, 2, i), Views.hyperSlice(stack2, 2, i - 1)}));
			stack2DiffSlices.add(Converters.convert(
					Views.collapse(slice),
					(a, b) -> b.set(a.get(0).get() - a.get(1).get()),
					new FloatType()));
		}
		final RandomAccessibleInterval<FloatType> stack2Diff = Views.stack(stack2DiffSlices);


		final RandomAccessibleInterval<ARGBType> comparisonStack = Show.compareTransformStack(
				realTransforms1,
				realTransforms2,
				Intervals.union(interval1, interval2),
				options.getMax(),
				options.getLambda(),
				showScaleIndex);


		bdv = Show.transformedStack(stack1, bdv);
		bdv = Show.transformedStack(stack2, bdv);

		bdv = Show.transformedStack(stack2Diff, bdv);

		final BdvStackSource<ARGBType> stackSource = BdvFunctions.show(comparisonStack, "comparison", Bdv.options().addTo(bdv));
		stackSource.setDisplayRange(0, 255);

		ImagePlus imp = ImageJFunctions.show(comparisonStack, "comparison");
		ImageStack stack = new ImageStack(imp.getWidth(), imp.getHeight());
		for (int i = 1; i <= imp.getStack().getSize(); ++i)
			stack.addSlice(imp.getStack().getProcessor(i).duplicate());
		new ImagePlus("copy", stack).show();

		imp.close();


		imp = ImageJFunctions.show(stack1, "stack1");
		stack = new ImageStack(imp.getWidth(), imp.getHeight());
		for (int i = 1; i <= imp.getStack().getSize(); ++i)
			stack.addSlice(imp.getStack().getProcessor(i).duplicate());
		new ImagePlus("copy stack 1", stack).show();

		imp.close();


		imp = ImageJFunctions.show(stack2, "stack2");
		stack = new ImageStack(imp.getWidth(), imp.getHeight());
		for (int i = 1; i <= imp.getStack().getSize(); ++i)
			stack.addSlice(imp.getStack().getProcessor(i).duplicate());
		new ImagePlus("copy stack 2", stack).show();

		imp.close();


		imp = ImageJFunctions.show(stack2Diff, "stack2 diff");
		stack = new ImageStack(imp.getWidth(), imp.getHeight());
		for (int i = 1; i <= imp.getStack().getSize(); ++i)
			stack.addSlice(imp.getStack().getProcessor(i).duplicate());
		new ImagePlus("copy stack 2 diff", stack).show();

		imp.close();

//			ImageJFunctions.show(stack, group);
	}
}
