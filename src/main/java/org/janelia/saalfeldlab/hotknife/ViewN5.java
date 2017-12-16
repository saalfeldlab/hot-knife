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
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import net.imglib2.RandomAccessibleInterval;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ViewN5 {

	@SuppressWarnings("serial")
	public static class Options implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "--n5Dataset", required = true, usage = "N5 dataset, e.g. /Sec26")
		final String[] datasetNames = null;

		private boolean parsedSuccessfully = false;

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

		public boolean isParsedSuccessfully() {
			return parsedSuccessfully;
		}

		/**
		 * @return the n5Path
		 */
		public String getN5Path() {
			return n5Path;
		}

		/**
		 * @return the datasetName
		 */
		public String[] getDatasetNames() {
			return datasetNames;
		}

		/**
		 * @param parsedSuccessfully the parsedSuccessfully to set
		 */
		public void setParsedSuccessfully(final boolean parsedSuccessfully) {
			this.parsedSuccessfully = parsedSuccessfully;
		}
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final N5Reader n5 = N5.openFSReader(options.getN5Path());

		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.max(1, numProc / 2));

		BdvStackSource bdv = null;

		final String[] datasetNames = options.getDatasetNames();
		for (int i = 0; i < datasetNames.length; ++i) {
			@SuppressWarnings("unchecked")
			final RandomAccessibleInterval source = N5Utils.openVolatile(n5, datasetNames[i]);

			final BdvOptions bdvOptions = bdv == null ? Bdv.options() : Bdv.options().addTo(bdv);

			bdv = BdvFunctions.show(
					VolatileViews.wrapAsVolatile(
							source,
							queue),
					datasetNames[i],
					source.numDimensions() == 2 ? bdvOptions.is2D() : bdvOptions);

			bdv.setDisplayRange(0,  255);
		}
	}
}
