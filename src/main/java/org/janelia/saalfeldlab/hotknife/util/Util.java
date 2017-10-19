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
package org.janelia.saalfeldlab.hotknife.util;

import java.util.Arrays;

import ij.process.FloatProcessor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Util {

	private Util() {}

	public static final <T extends Type<T>> void copy(
			final RandomAccessible<? extends T> source,
			final RandomAccessibleInterval<T> target) {

		Views.flatIterable(Views.interval(Views.pair(source, target), target)).forEach(
				pair -> pair.getB().set(pair.getA()));
	}

	public static final FloatProcessor materialize(final RandomAccessibleInterval<FloatType> source) {
		final FloatProcessor target = new FloatProcessor((int) source.dimension(0), (int) source.dimension(1));
		Util.copy(
				Views.zeroMin(source),
				ArrayImgs.floats(
						(float[]) target.getPixels(),
						target.getWidth(),
						target.getHeight()));
		return target;
	}

	static public void scaleArray(
			final double[] array,
			final double scale) {

		Arrays.setAll(
				array,
				i -> array[i] * scale);
	}

	/**
	 * Flatten a group name.
	 *
	 * Removes optional leading <code>separator</code> and replaces all others by <code>replacement</code>.
	 *
	 * @param groupName
	 * @param separator
	 * @param replacement
	 * @return
	 */
	public static String flattenGroupName(final String groupName, final String separator, final String replacement) {

		return groupName.replaceAll("^" + separator, "").replaceAll(separator, replacement);
	}

	public static String flattenGroupName(final String groupName) {

		return flattenGroupName(groupName, "/", ".");
	}
}
