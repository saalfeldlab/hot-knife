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
import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import bdv.util.Bdv;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.FloatType;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ViewAlignment {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "-i", aliases = {"--n5Group"}, required = false, usage = "N5 group, e.g. /align-0")
		private List<String> groups = new ArrayList<>();

		@Option(name = "--scaleIndex", required = true, usage = "scale index for visualization, e.g. 4 (means scale = 1.0 / 2^4)")
		private int transformScaleIndex = 0;

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
		 * @return the groups
		 */
		public List<String> getGroups() {

			return groups;
		}
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

//		new ImageJ();

		final N5Reader n5 = N5.openFSReader(options.getN5Path());

		final int showScaleIndex = options.getScaleIndex();
		final double showScale = 1.0 / (1 << showScaleIndex);

		Bdv bdv = null;

		for (final String group : options.getGroups()) {

			final String[] datasetNames = n5.getAttribute(group, "datasets", String[].class);
			final double[] boundsMin = n5.getAttribute(group, "boundsMin", double[].class);
			final double[] boundsMax = n5.getAttribute(group, "boundsMax", double[].class);

			final RealTransform[] realTransforms = new RealTransform[datasetNames.length];
			for (int i = 0; i < datasetNames.length; ++i) {
				realTransforms[i] = Transform.loadScaledTransform(
						n5,
						group + "/" + i);
			}

			final RandomAccessibleInterval<FloatType> stack = Transform.createTransformedStack(
					options.getN5Path(),
					Arrays.asList(datasetNames),
					showScaleIndex,
					Arrays.asList(realTransforms),
					new FinalInterval(
							Grid.floorScaled(boundsMin, showScale),
							Grid.ceilScaled(boundsMax, showScale)));

			bdv = Show.transformedStack(
					stack,
					bdv);

//			ImageJFunctions.show(stack, group);
		}
	}
}
