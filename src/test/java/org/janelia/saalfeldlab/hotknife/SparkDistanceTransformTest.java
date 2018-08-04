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

import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import ij.ImageJ;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.NativeImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkDistanceTransformTest {

	public final static void main(final String... args) throws IOException {

		new ImageJ();

		final N5FSReader n5 = new N5FSReader("/nrs/saalfeld/lauritzen/02/workspace.n5");

		final RandomAccessibleInterval dataset = N5Utils.open(n5, "/filtered/segmentation/multicut_more_features");

		final IntervalView block = Views.offsetInterval(dataset, new long[]{2000, 2000, 200}, new long[] {512, 512, 50});

		ImageJFunctions.show(block);

		final NativeImg boundaries = SparkDistanceTransform.createBoundaries(block, 100, new UnsignedIntType(0));

		ImageJFunctions.show(boundaries);

//		fail("Not yet implemented");
	}
}
