package org.janelia.saalfeldlab.hotknife.brain;

import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.ConstantUtils;
import org.janelia.saalfeldlab.hotknife.FlattenTransform;

public class ExtendFlattenTransform {

	/**
	 * Transform that behaves like a {@code FlattenTransform} from {@code
	 * minHeightfield} at {@code z=minZ} to constant height {@code maxZ} at
	 * {@code z=maxZ}. For {@code z>maxZ}, the transformation is identity.
	 * For {@code z<minZ} the heightfield exponentially fades to identity at
	 * distance {@code fadeDist}, approximately.
	 */
	public static RealTransform extendFlattenTransform(
			final RealRandomAccessible<DoubleType> minHeightfield,
			final double minZ,
			final double maxZ,
			final double fadeDist) {
		final RealRandomAccessible<DoubleType> maxHeightfield = ConstantUtils.constantRealRandomAccessible(new DoubleType(maxZ), 2);
		final FlattenTransform<?> flattenTransform = new FlattenTransform<>(
				minHeightfield,
				maxHeightfield,
				minZ, maxZ);
		return new ExtendedFlattenTransform(flattenTransform.inverse(), minZ, maxZ, fadeDist);
	}

	private static class ExtendedFlattenTransform implements RealTransform {

		private final RealTransform invFlatten;
		private final double minZ;
		private final double maxZ;
		private double fadeDist;

		final RealPoint flattened = new RealPoint(3);

		ExtendedFlattenTransform(final RealTransform invFlatten, final double minZ, final double maxZ, final double fadeDist) {
			assert invFlatten.numSourceDimensions() == 3;
			assert invFlatten.numTargetDimensions() == 3;
			this.invFlatten = invFlatten;
			this.minZ = minZ;
			this.maxZ = maxZ;
			this.fadeDist = fadeDist;
		}

		@Override
		public int numSourceDimensions() {
			return 3;
		}

		@Override
		public int numTargetDimensions() {
			return 3;
		}

		@Override
		public void apply(final double[] source, final double[] target) {
			apply(RealPoint.wrap(source), RealPoint.wrap(target));
		}

		@Override
		public void apply(final RealLocalizable source, final RealPositionable target) {
			final double z = source.getDoublePosition(2);
			if (z > maxZ)
				target.setPosition(source);
			else if (z > minZ)
				invFlatten.apply(source, target);
			else {
				final double dd = (minZ - z) * 4.0 / fadeDist;
				final double s = Math.exp(dd * dd / (-2.0));
				invFlatten.apply(source, flattened);
				final double fz = flattened.getDoublePosition(2);
				target.setPosition(source.getDoublePosition(0), 0);
				target.setPosition(source.getDoublePosition(1), 1);
				target.setPosition(s * (fz - z) + z, 2);
			}
		}

		@Override
		public RealTransform copy() {
			return new Playground6.ExtendedFlattenTransform(invFlatten.copy(), minZ, maxZ, fadeDist);
		}
	}
}
