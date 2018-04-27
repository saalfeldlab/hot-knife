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

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.BdvFunctions;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import ij.ImageJ;
import ij.ImagePlus;
import ij.io.Opener;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ConvertTiffSeriesToN5 {

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final String n5Path = "/nrs/flyem/data/tmp/Z0115-22.n5";

//		final String urlFormat = "/groups/flyem/data/Z0115-22_Sec24/flatten2/flattened/zcorr.%5$05d-flattened.tif";
//		final String datasetName = "Sec24";
//		final int yMin = 989;
//		final int yMax = 3874;

//		final String urlFormat = "/groups/flyem/data/Z0115-22_Sec25/flatten3/flattened/zcorr.%5$05d-flattened.tif";
//		final String datasetName = "Sec25";
//		final int yMin = 1150;
//		final int yMax = 3964;

		final String urlFormat = "/nrs/flyem/data/Z0115-22_Sec26/flatten/flattened/zcorr.%5$05d-flattened.tif";
		final String datasetName = "Sec26";
		final int yMin = 2150;
		final int yMax = 4995;

		final int[] blockSize = new int[]{128, 128, 128};

		new ImageJ();

		/* width and height */
		final ImagePlus firstSlice = new Opener().openImage(String.format(urlFormat, 0, 1, 0, 0, 0));
		final int width = firstSlice.getWidth();
		final int height = firstSlice.getHeight();

//		firstSlice.show();

		/* depth */
		final File[] tiffs = new File(urlFormat).getParentFile().listFiles(
				(dir, file) -> file.endsWith(".tif"));
		final int depth = tiffs.length;

		final IJImageLoader sourceLoader = new IJImageLoader(
				width,
				height,
				depth,
				1,
				1,
				urlFormat,
				width,
				height,
				0);

		final RandomAccessibleInterval<UnsignedByteType> source = sourceLoader.getImage(0);

		final IntervalView<UnsignedByteType> crop = Views.interval(source, new long[]{0, yMin, 0}, new long[]{width - 1, yMax, depth - 1});
//		final RandomAccessibleInterval<UnsignedByteType> crop = source;

		final N5Writer n5 = new N5FSWriter(n5Path);

		final ExecutorService exec = Executors.newFixedThreadPool( 24 );

//		N5Utils.save(crop, n5, datasetName, blockSize, CompressionType.GZIP, exec);

		exec.shutdown();

		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.max(1, numProc / 2));

		BdvFunctions.show(
				VolatileViews.wrapAsVolatile(
						(RandomAccessibleInterval)N5Utils.openVolatile(n5, datasetName),
						queue),
				"crop");
	}

}
