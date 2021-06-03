/**
 *
 */
package org.janelia.saalfeldlab.hotknife;

import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPositionable;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.outofbounds.OutOfBoundsBorderFactory;
import net.imglib2.outofbounds.OutOfBoundsFactory;
import net.imglib2.realtransform.InverseRealTransform;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

/**
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 *
 */
public class HeightFieldTransform<T extends RealType<T>> implements InvertibleRealTransform {

	private final int n;
	private final RealRandomAccessible<T> heightFieldAccessible;
	private final RealRandomAccess<T> heightFieldAccess;
	private final double min;


	public HeightFieldTransform(
			final RealRandomAccessible<T> heightField,
			final double offset) {

		n = heightField.numDimensions();
		this.heightFieldAccessible = heightField;
		this.heightFieldAccess = heightField.realRandomAccess();
		this.min = offset;
	}

	public HeightFieldTransform(
			final RandomAccessibleInterval<T> heightField,
			final double offset,
			final InterpolatorFactory<T, RandomAccessible<T>> interpolatorFactory,
			final OutOfBoundsFactory<T, RandomAccessibleInterval<T>> outOfBoundsFactory) {

		this(
				Views.extend(heightField, outOfBoundsFactory),
				offset,
				interpolatorFactory);
	}

	public HeightFieldTransform(
			final RandomAccessible<T> heightField,
			final double offset,
			final InterpolatorFactory<T, RandomAccessible<T>> interpolatorFactory) {

		this(
				Views.interpolate(heightField, interpolatorFactory),
				offset);
	}

	public HeightFieldTransform(
			final RandomAccessibleInterval<T> heightField,
			final double offset) {

		this(
				heightField,
				offset,
				new NLinearInterpolatorFactory<>(),
				new OutOfBoundsBorderFactory<>());
	}



	@Override
	public int numSourceDimensions() {

		return n + 1;
	}

	@Override
	public int numTargetDimensions() {

		return n + 1;
	}

	@Override
	public void apply(final double[] source, final double[] target) {

		assert source.length <= target.length : "Target vector is too small.";

		System.arraycopy(source, 0, target, 0, source.length);
		heightFieldAccess.setPosition(source);
		final double minPosition = heightFieldAccess.get().getRealDouble();

		target[n] = source[n] - minPosition + min;
	}

	@Override
	public void apply(final RealLocalizable source, final RealPositionable target) {

		assert source.numDimensions() <= target.numDimensions() : "Target vector is too small.";

		target.setPosition(source);
		heightFieldAccess.setPosition(source);
		final double minPosition = heightFieldAccess.get().getRealDouble();

		target.setPosition(source.getDoublePosition(n) - minPosition + min, n);
	}

	@Override
	public void applyInverse(final double[] source, final double[] target) {

		assert source.length <= target.length : "Target vector is too small.";

		System.arraycopy(target, 0, source, 0, target.length);
		heightFieldAccess.setPosition(target);
		final double minPosition = heightFieldAccess.get().getRealDouble();
		source[n] = target[n] - min + minPosition;
	}

	@Override
	public void applyInverse(final RealPositionable source, final RealLocalizable target) {

		assert source.numDimensions() <= target.numDimensions() : "Target vector is too small.";

		source.setPosition(target);

		heightFieldAccess.setPosition(target);
		final double minPosition = heightFieldAccess.get().getRealDouble();
		source.setPosition(target.getDoublePosition(n) - min + minPosition, n);
	}

	@Override
	public InverseRealTransform inverse() {

		return new InverseRealTransform(this);
	}

	@Override
	public HeightFieldTransform<T> copy() {

		return new HeightFieldTransform<>(heightFieldAccessible, min);
	}
}
