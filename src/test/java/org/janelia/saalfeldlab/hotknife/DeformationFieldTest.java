/**
 *
 */
package org.janelia.saalfeldlab.hotknife;

import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;

/**
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 *
 */
public class DeformationFieldTest {

	private final int width = 20;
	private final int height = 50;
	private final int depth = 30;

	private final Random rnd = new Random();
	private double[] xField = new double[width * height * depth];
	private double[] yField = new double[width * height * depth];
	private double[] zField = new double[width * height * depth];

	private DeformationFieldTransform<DoubleType> saalfeldTransform2;
	private net.imglib2.realtransform.DeformationFieldTransform<DoubleType> bogovicTransform2;
	private DeformationFieldTransform<DoubleType> saalfeldTransform3;
	private net.imglib2.realtransform.DeformationFieldTransform<DoubleType> bogovicTransform3;

	{
		Arrays.setAll(xField, i -> rnd.nextGaussian());
		Arrays.setAll(yField, i -> rnd.nextGaussian());
		Arrays.setAll(zField, i -> rnd.nextGaussian());

		final ArrayImg<DoubleType, DoubleArray> xFieldImg2 = ArrayImgs.doubles(xField, width * height, depth);
		final ArrayImg<DoubleType, DoubleArray> yFieldImg2 = ArrayImgs.doubles(yField, width * height, depth);

		final ArrayImg<DoubleType, DoubleArray> xFieldImg3 = ArrayImgs.doubles(xField, width, height, depth);
		final ArrayImg<DoubleType, DoubleArray> yFieldImg3 = ArrayImgs.doubles(yField, width, height, depth);
		final ArrayImg<DoubleType, DoubleArray> zFieldImg3 = ArrayImgs.doubles(zField, width, height, depth);

		bogovicTransform2 =
				new net.imglib2.realtransform.DeformationFieldTransform<>(
						Views.stack(xFieldImg2, yFieldImg2));

		bogovicTransform3 =
				new net.imglib2.realtransform.DeformationFieldTransform<>(
						Views.stack(xFieldImg3, yFieldImg3, zFieldImg3));

		final RealRandomAccessible<DoubleType> xFieldReal2 = Views.interpolate(
				Views.extendZero(xFieldImg2),
				new NLinearInterpolatorFactory<>());
		final RealRandomAccessible<DoubleType> yFieldReal2 = Views.interpolate(
				Views.extendZero(yFieldImg2),
				new NLinearInterpolatorFactory<>());

		final RealRandomAccessible<DoubleType> xFieldReal3 = Views.interpolate(
				Views.extendZero(xFieldImg3),
				new NLinearInterpolatorFactory<>());
		final RealRandomAccessible<DoubleType> yFieldReal3 = Views.interpolate(
				Views.extendZero(yFieldImg3),
				new NLinearInterpolatorFactory<>());
		final RealRandomAccessible<DoubleType> zFieldReal3 = Views.interpolate(
				Views.extendZero(zFieldImg3),
				new NLinearInterpolatorFactory<>());

		saalfeldTransform2 = new DeformationFieldTransform<>(xFieldReal2, yFieldReal2);
		saalfeldTransform3 = new DeformationFieldTransform<>(xFieldReal3, yFieldReal3, zFieldReal3);
	}

	@Test
	public void testDeformationField2() {

		final double[] src = new double[2];
		final double[] tgt1 = new double[2];
		final double[] tgt2 = new double[2];
		for (src[1] = 0; src[1] < depth; src[1] += 0.3 + 0.3 * rnd.nextGaussian()) {
			for (src[0] = 0; src[0] < width * height; src[0] += 0.3 + 0.3 * rnd.nextGaussian()) {
				saalfeldTransform2.apply(src, tgt2);
				bogovicTransform2.apply(src, tgt1);
				assertArrayEquals(tgt1, tgt2, 0.001);
			}
		}
	}

	@Test
	public void testDeformationField3() {

		final double[] src = new double[3];
		final double[] tgt1 = new double[3];
		final double[] tgt2 = new double[3];
		for (src[2] = 0; src[2] < depth; src[2] += 0.3 + 0.3 * rnd.nextGaussian()) {
			for (src[1] = 0; src[1] < height; src[1] += 0.3 + 0.3 * rnd.nextGaussian()) {
				for (src[0] = 0; src[0] < width; src[0] += 0.3 + 0.3 * rnd.nextGaussian()) {
					saalfeldTransform3.apply(src, tgt2);
					bogovicTransform3.apply(src, tgt1);
					assertArrayEquals(tgt1, tgt2, 0.001);
				}
			}
		}
	}

	public void testDeformation2(
			final RealTransform deformation,
			final int n) {

		for (int i = 0; i < n; ++i) {
			final double[] src = new double[2];
			final double[] tgt = new double[2];
			for (src[1] = 0; src[1] < depth; src[1] += 0.3) {
				for (src[0] = 0; src[0] < width * height; src[0] += 0.3) {
					deformation.apply(src, tgt);
				}
			}
		}
	}

	public void testDeformation3(
			final RealTransform deformation,
			final int n) {

		for (int i = 0; i < n; ++i) {
			final double[] src = new double[3];
			final double[] tgt = new double[3];
			for (src[2] = 0; src[2] < depth; src[2] += 0.3) {
				for (src[1] = 0; src[1] < height; src[1] += 0.3) {
					for (src[0] = 0; src[0] < width; src[0] += 0.3) {
						deformation.apply(src, tgt);
					}
				}
			}
		}
	}

	@Test
	public void testBogovicDeformationField2() {

		testDeformation2(bogovicTransform2, 5);
	}


	@Test
	public void testSaalfeldDeformationField2() {

		testDeformation2(saalfeldTransform2, 5);
	}

	@Test
	public void testBogovicDeformationField3() {

		testDeformation3(bogovicTransform3, 5);
	}


	@Test
	public void testSaalfeldDeformationField3() {

		testDeformation3(saalfeldTransform3, 5);
	}
}
