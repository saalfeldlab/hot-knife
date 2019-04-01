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

import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.apache.commons.math.util.MathUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedIntType;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkRandomSubsampleN5Behavior {

//	private static long[] dimensions = new long[] {2, 2, 2};
	private static long[] dimensions = new long[] {4, 4};
	private static int[] array;

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {

		array = IntStream.range(0, (int)Arrays.stream(dimensions).reduce(1, (a,b) -> a * b)).toArray();

	}

	/**
	 * @throws java.lang.Exception
	 */
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}

//	@Test
	public static final void main(final String... args) throws Exception {

		setUpBeforeClass();

		new ImageJ();

		final ArrayImg<UnsignedIntType, IntArray> img = ArrayImgs.unsignedInts(array, dimensions);

		ImageJFunctions.show(img);

		final ArrayImg<UnsignedIntType, ?>[] subsamples = SparkRandomSubsampleN5.subsample(
				MathUtils.pow(2, dimensions.length),
				new UnsignedIntType(),
				LongStream.of(dimensions).map(x -> x / 2).toArray(),
				(Cursor<UnsignedIntType>[])SparkRandomSubsampleN5.createSources(
						img,
						new long[dimensions.length],
						LongStream.of(dimensions).map(x -> x / 2).toArray()));

		Stream.of(subsamples).forEach(sample -> ImageJFunctions.show(sample));

		Thread.sleep(2000);
	}
}
