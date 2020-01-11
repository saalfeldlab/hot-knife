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
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;

/**
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 *
 */
public class FlattenTransform implements InvertibleRealTransform {

	private final int n;
	private final RealRandomAccessible<DoubleType> minAccessible;
	private final RealRandomAccessible<DoubleType> maxAccessible;
	private final RealRandomAccess<DoubleType> minAccess;
	private final RealRandomAccess<DoubleType> maxAccess;
	private final double min;
	private final double norm;


	public FlattenTransform(
			final RealRandomAccessible<DoubleType> min,
			final RealRandomAccessible<DoubleType> max,
			final double minPosition,
			final double maxPosition) {

		assert min.numDimensions() == max.numDimensions() : "Numbers of dimensions do not match.";

		n = min.numDimensions();
		this.minAccessible = min;
		this.maxAccessible = max;
		this.minAccess = min.realRandomAccess();
		this.maxAccess = max.realRandomAccess();
		this.min = minPosition;
		norm = maxPosition - minPosition;

	}

	public FlattenTransform(
			final RandomAccessibleInterval<DoubleType> min,
			final RandomAccessibleInterval<DoubleType> max,
			final double minPosition,
			final double maxPosition,
			final InterpolatorFactory<DoubleType, RandomAccessible<DoubleType>> interpolatorFactory,
			final OutOfBoundsFactory<DoubleType, RandomAccessibleInterval<DoubleType>> outOfBoundsFactory) {

		this(
				Views.extend(min, outOfBoundsFactory),
				Views.extend(max, outOfBoundsFactory),
				minPosition,
				maxPosition,
				interpolatorFactory);
	}

	public FlattenTransform(
			final RandomAccessible<DoubleType> min,
			final RandomAccessible<DoubleType> max,
			final double minPosition,
			final double maxPosition,
			final InterpolatorFactory<DoubleType, RandomAccessible<DoubleType>> interpolatorFactory) {

		this(
				Views.interpolate(min, interpolatorFactory),
				Views.interpolate(max, interpolatorFactory),
				minPosition,
				maxPosition);
	}

	public FlattenTransform(
			final RandomAccessibleInterval<DoubleType> min,
			final RandomAccessibleInterval<DoubleType> max,
			final double minPosition,
			final double maxPosition) {

		this(
				min,
				max,
				minPosition,
				maxPosition,
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

		minAccess.setPosition(source);
		maxAccess.setPosition(source);
		final double minPosition = minAccess.get().get();
		final double maxPosition = maxAccess.get().get();
		final double scale = maxPosition - minPosition;

		target[n] = (source[n] - minPosition) / scale * norm + min;
	}

	@Override
	public void apply(final RealLocalizable source, final RealPositionable target) {

		assert source.numDimensions() <= target.numDimensions() : "Target vector is too small.";

		target.setPosition(source);
		minAccess.setPosition(source);
		maxAccess.setPosition(source);
		final double minPosition = minAccess.get().get();
		final double maxPosition = maxAccess.get().get();
		final double scale = maxPosition - minPosition;

		target.setPosition((source.getDoublePosition(n) - minPosition) / scale * norm + min, n);
	}

	@Override
	public void applyInverse(final double[] source, final double[] target) {

		assert source.length <= target.length : "Target vector is too small.";

		System.arraycopy(target, 0, source, 0, target.length);

		minAccess.setPosition(target);
		maxAccess.setPosition(target);
		final double minPosition = minAccess.get().get();
		final double maxPosition = maxAccess.get().get();
		final double scale = maxPosition - minPosition;

		source[n] = (target[n] - min) / norm * scale + minPosition;
	}

	@Override
	public void applyInverse(final RealPositionable source, final RealLocalizable target) {

		assert source.numDimensions() <= target.numDimensions() : "Target vector is too small.";

		source.setPosition(target);

		minAccess.setPosition(target);
		maxAccess.setPosition(target);
		final double minPosition = minAccess.get().get();
		final double maxPosition = maxAccess.get().get();
		final double scale = maxPosition - minPosition;

		source.setPosition((target.getDoublePosition(n) - min) / norm * scale + minPosition, n);
	}

	@Override
	public InverseRealTransform inverse() {

		return new InverseRealTransform(this);
	}

	@Override
	public FlattenTransform copy() {

		return new FlattenTransform(minAccessible, maxAccessible, min, min + norm);
	}
}
