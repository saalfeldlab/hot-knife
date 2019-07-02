/**
 *
 */
package org.janelia.saalfeldlab.hotknife;

import java.util.ArrayList;
import java.util.Arrays;

import org.janelia.saalfeldlab.hotknife.ops.GradientCenter;
import org.janelia.saalfeldlab.hotknife.ops.Max;
import org.janelia.saalfeldlab.hotknife.ops.Multiply;
import org.janelia.saalfeldlab.hotknife.ops.SimpleGaussRA;
import org.janelia.saalfeldlab.hotknife.ops.TubenessCenter;
import org.janelia.saalfeldlab.hotknife.util.Lazy;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.VolatileViews;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.converter.Converters;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;

/**
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 *
 */
public class LazyBehavior {

	private static int[] blockSize = new int[] {32, 32, 32};

	private static double[][][] sigmaSeries(
			final double[] resolution,
			final int stepsPerOctave,
			final int steps) {

		final double factor = Math.pow(2, 1.0 / stepsPerOctave);

		final int n = resolution.length;
		final double[][][] series = new double[3][steps][n];
		final double minRes = Arrays.stream(resolution).min().getAsDouble();

		double targetSigma = 0.5;
		for (int i = 0; i < steps; ++i) {
			for (int d = 0; d < n; ++d) {
				series[0][i][d] = targetSigma / resolution[d] * minRes;
				series[1][i][d] = Math.max(0.5, series[0][i][d]);
			}
			targetSigma *= factor;
		}
		for (int i = 1; i < steps; ++i) {
			for (int d = 0; d < n; ++d) {
				series[2][i][d] = Math.sqrt(Math.max(0, series[1][i][d] * series[1][i][d] - series[1][i - 1][d] * series[1][i - 1][d]));
			}
		}

		return series;
	}

	/**
	 * @param args
	 */
	public static void main(final String[] args) {

		new ImageJ();

		final int scaleSteps = 8;
		final int octaveSteps = 2;
		final double[] resolution = new double[]{150,  150,  1000};

		final double[][][] sigmaSeries = sigmaSeries(resolution, octaveSteps, scaleSteps);

		for (int i = 0; i < scaleSteps; ++i) {

			System.out.println(
					i + ": " +
					Arrays.toString(sigmaSeries[0][i]) + " : " +
					Arrays.toString(sigmaSeries[1][i]) + " : " +
					Arrays.toString(sigmaSeries[2][i]));
		}

//		final ImagePlus imp = IJ.openImage("https://imagej.nih.gov/ij/images/bridge.gif");
		final ImagePlus imp = IJ.openImage("/home/saalfeld/projects/clay-white-matter/sect18b-s02-clahe.gif");
		imp.show();

		final RandomAccessibleInterval<UnsignedByteType> img = ImagePlusImgs.from(imp);
		final RandomAccessibleInterval<DoubleType> converted =
				Converters.convert(
						img,
						(a, b) -> { b.set(a.getRealDouble()); },
						new DoubleType());

		ExtendedRandomAccessibleInterval<DoubleType, RandomAccessibleInterval<DoubleType>> source =
				Views.extendMirrorSingle(converted);

		/* scale space */
		final ArrayList<RandomAccessibleInterval<DoubleType>> scales = new ArrayList<>();
		for (int i = 0; i < scaleSteps; ++i) {
			final SimpleGaussRA<DoubleType> op = new SimpleGaussRA<>(sigmaSeries[2][i]);
			op.setInput(source);
			final RandomAccessibleInterval<DoubleType> smoothed = Lazy.process(
					img,
					blockSize,
					new DoubleType(),
					AccessFlags.setOf(),
					op);
			source = Views.extendMirrorSingle(smoothed);
			scales.add(smoothed);
		}

		/* scale space of tubeness */
		final ArrayList<RandomAccessibleInterval<DoubleType>> results = new ArrayList<>();
		for (int i = 1; i < scaleSteps; ++i) {

			/* gradients */
			final RandomAccessible[] gradients = new RandomAccessible[img.numDimensions()];
			for (int d = 0; d < img.numDimensions(); ++d) {
				final GradientCenter<DoubleType> gradientOp =
						new GradientCenter<>(
								Views.extendBorder(scales.get(i)),
								d,
								sigmaSeries[0][i][d]);
				final RandomAccessibleInterval<DoubleType> gradient = Lazy.process(img, blockSize, new DoubleType(), AccessFlags.setOf(), gradientOp);
				gradients[d] = Views.extendZero(gradient);
			}

			/* tubeness */
			final TubenessCenter<DoubleType> tubenessOp = new TubenessCenter<>(gradients, sigmaSeries[0][i]);
			final RandomAccessibleInterval<DoubleType> tubeness = Lazy.process(img, blockSize, new DoubleType(), AccessFlags.setOf(AccessFlags.VOLATILE), tubenessOp);
			results.add(tubeness); // works without extension because the size is the same as max output
		}

		/* max project scale space of tubeness */
		final Max<DoubleType> maxOp = new Max<>(results);
		final RandomAccessibleInterval<DoubleType> scaleTubeness = Lazy.process(img, blockSize, new DoubleType(), AccessFlags.setOf(AccessFlags.VOLATILE), maxOp);

		/* multiply with intensities */
		final Multiply<DoubleType> mulOp = new Multiply<>(scaleTubeness, source);
		final RandomAccessibleInterval<DoubleType> multipliedTubeness = Lazy.process(scaleTubeness, blockSize, new DoubleType(), AccessFlags.setOf(AccessFlags.VOLATILE), mulOp);



		final AffineTransform3D transform = new AffineTransform3D();
		transform.set(1.0, 0, 0);
		transform.set(1.0, 1, 1);
		transform.set(4.0, 2, 2);

		BdvOptions options = BdvOptions.options();
		for (final RandomAccessibleInterval<DoubleType> result : results) {
			final BdvStackSource<Volatile<DoubleType>> stackSource =
					BdvFunctions.show(
							VolatileViews.wrapAsVolatile(result),
							"",
							options.sourceTransform(150, 150, 1000));
			stackSource.setDisplayRange(-1, 1);
			options = options.addTo(stackSource);
		}

		BdvStackSource<Volatile<DoubleType>> stackSource =
				BdvFunctions.show(
						VolatileViews.wrapAsVolatile(scaleTubeness),
						"max",
						options.sourceTransform(150, 150, 1000));
		stackSource.setDisplayRange(-1, 1);
		stackSource =
				BdvFunctions.show(
						VolatileViews.wrapAsVolatile(multipliedTubeness),
						"multitplied",
						options.sourceTransform(150, 150, 1000));
		stackSource.setDisplayRange(-1, 1);

	}
}
