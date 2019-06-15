/**
 *
 */
package org.janelia.saalfeldlab.hotknife;

import org.janelia.saalfeldlab.hotknife.ops.GradientCenter;
import org.janelia.saalfeldlab.hotknife.ops.TubenessCenter;
import org.janelia.saalfeldlab.hotknife.ops.SimpleGaussRA;
import org.janelia.saalfeldlab.hotknife.util.Lazy;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;

/**
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 *
 */
public class LazyBehavior {

	private static int[] blockSize = new int[] {16, 16, 16};

	/**
	 * @param args
	 */
	public static void main(final String[] args) {

		new ImageJ();
//		final ImagePlus imp = IJ.openImage("https://imagej.nih.gov/ij/images/bridge.gif");
		final ImagePlus imp = IJ.openImage("/home/saalfeld/projects/clay-white-matter/sect18b-s02-clahe.gif");
		imp.show();

		final RandomAccessibleInterval<UnsignedByteType> img = ImagePlusImgs.from(imp);
		final RandomAccessibleInterval<DoubleType> converted =
				Converters.convert(
						img,
						(a, b) -> { b.set(a.getRealDouble()); },
						new DoubleType());

		final ExtendedRandomAccessibleInterval<DoubleType, RandomAccessibleInterval<DoubleType>> source =
//				Views.extendMirrorSingle(converted);
				Views.extendBorder(converted);

		final SimpleGaussRA<DoubleType> op = new SimpleGaussRA<>(new double[] {2, 2, 0});
//		final RandomAccessibleInterval<DoubleType> smoothed = Lazy.process(
//				source,
//				img,
//				new int[] {16, 16},
//				new DoubleType(),
//				AccessFlags.setOf(),
//				op);
//
		op.setInput(source);
		final RandomAccessibleInterval<DoubleType> smoothed = Lazy.process(
				img,
				blockSize,
				new DoubleType(),
				AccessFlags.setOf(),
				op);

		ImageJFunctions.show(smoothed);

		final RandomAccessible[] gradients = new RandomAccessible[img.numDimensions()];
		for (int d = 0; d < img.numDimensions(); ++d) {
			final GradientCenter<DoubleType> gradientOp = new GradientCenter<>(Views.extendBorder(smoothed), d);
			final RandomAccessibleInterval<DoubleType> gradient = Lazy.process(img, blockSize, new DoubleType(), AccessFlags.setOf(), gradientOp);
			gradients[d] = Views.extendZero(gradient);
		}

		final TubenessCenter<DoubleType> hessianOp = new TubenessCenter<>(gradients);
		final RandomAccessibleInterval<DoubleType> hessian = Lazy.process(img, blockSize, new DoubleType(), AccessFlags.setOf(), hessianOp);

		ImageJFunctions.show(hessian);
	}

}
