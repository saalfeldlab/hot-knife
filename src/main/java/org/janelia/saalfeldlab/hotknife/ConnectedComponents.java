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
import java.util.Arrays;

import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import ij.ImageJ;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.labeling.ConnectedComponentAnalysis;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.LongArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.logic.BoolType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ConnectedComponents {

	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(final String[] args) throws IOException {

		final N5FSReader n5 = new N5FSReader("/nrs/saalfeld/FAFB00/v14_align_tps_20170818_dmg.n5");
		final RandomAccessibleInterval<UnsignedByteType> img = N5Utils.open(n5, "/volumes/predictions/synapses_dt_reblocked/s0");

		final RandomAccessibleInterval<UnsignedByteType> crop = Views.offsetInterval(img, new long[] {100000,65000,3500}, new long[] {1024,1024,102});
		final RandomAccessibleInterval<BoolType> thresholded = Converters.convert(crop, (a, b) -> b.set(a.getInteger() > 100), new BoolType());

		final ArrayImg<UnsignedLongType, LongArray> components = ArrayImgs.unsignedLongs(Intervals.dimensionsAsLongArray(thresholded));

		ConnectedComponentAnalysis.connectedComponents(thresholded, components);

		new ImageJ();

		ImageJFunctions.show(components);

		System.out.println(Arrays.toString(Intervals.dimensionsAsLongArray(img)));
	}

}

