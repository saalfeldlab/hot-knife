/**
 *
 */
package org.janelia.saalfeldlab.hotknife;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;

import org.junit.Test;

import net.imglib2.RealPoint;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.type.numeric.real.DoubleType;


/**
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 *
 */
public class FlattenTransformTest {

	private static double[] min = new double[] {
			1, 2, 3,
			4, 5, 6
	};

	private static double[] max = new double[] {
			11, 22, 33,
			44, 55, 66
	};

	private static double[][] testVectors = new double[][] {
		{0, 0, 1},
		{0, 0, 6},
		{0, 0, 11},

		{1, 0, 2},
		{1, 0, 22},

		{2, 0, 3},
		{2, 0, 18},
		{2, 0, 33}
	};

	private static double minPosition = 100;
	private static double maxPosition = 200;

	private static double[][] expected = new double[][] {
		{0, 0, 100},
		{0, 0, 150},
		{0, 0, 200},

		{1, 0, 100},
		{1, 0, 200},

		{2, 0, 100},
		{2, 0, 150},
		{2, 0, 200}
	};


	@Test
	public void test() {

		final ArrayImg<DoubleType, DoubleArray> topImg = ArrayImgs.doubles(min, 3, 2);
		final ArrayImg<DoubleType, DoubleArray> botImg = ArrayImgs.doubles(max, 3, 2);

		final FlattenTransform ft = new FlattenTransform(topImg, botImg, 100, 200);

		final double[] source = new double[3];
		final double[] target = new double[3];

		final RealPoint sourcePoint = RealPoint.wrap(source);
		final RealPoint targetPoint = RealPoint.wrap(target);

		for (int i = 0; i < testVectors.length; ++i) {

			Arrays.fill(target, 0);
			ft.apply(testVectors[i], target);
			assertArrayEquals(expected[i], target, 0.01);

			Arrays.fill(source, 0);
			ft.applyInverse(source, target);
			assertArrayEquals(testVectors[i], source, 0.01);

			Arrays.fill(target, 0);
			ft.apply(sourcePoint, targetPoint);
			assertArrayEquals(expected[i], target, 0.01);

			Arrays.fill(source, 0);
			ft.applyInverse(sourcePoint, targetPoint);
			assertArrayEquals(testVectors[i], source, 0.01);
		}
	}
}
