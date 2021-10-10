package org.janelia.saalfeldlab.hotknife.tobi;

import java.io.IOException;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

public class PositionField {

	final RandomAccessibleInterval<DoubleType> positionField;
	final RealTransform positionFieldLookup;
	final long[] offset;
	final double scale;

	public PositionField(final N5Reader n5, final String datasetName)
			throws IOException {
		scale = n5.getAttribute(datasetName, "scale", double.class);
		final double[] boundsMin = n5.getAttribute(datasetName, "boundsMin", double[].class);
		offset = Grid.floorScaled(boundsMin, scale);
		positionField = N5Utils.open(n5, datasetName);
		positionFieldLookup = Transform.createPositionFieldTransform(positionField);
	}

	public PositionField(final RandomAccessibleInterval<DoubleType> positionField, final long[] offset, final double scale)
	{
		this.positionField = positionField;
		this.offset = offset.clone();
		this.scale = scale;
		positionFieldLookup = Transform.createPositionFieldTransform(positionField);
	}

	public RealTransform getTransform(final int level) {
		return getTransform(level, IdentityTransform.get());
	}

	public RealTransform getTransform(final int level, final RealTransform movingTransform) {
		return new CombinedTransform(positionFieldLookup, scale, offset[0], offset[1], level, movingTransform);
	}

	public RandomAccessibleInterval<DoubleType> getPositionFieldRAI() {
		return positionField;
	}

	public long[] getOffset() {
		return offset;
	}

	public long getOffset(final int d) {
		return offset[d];
	}

	public double getScale() {
		return scale;
	}

	private static class CombinedTransform implements RealTransform {

		private final RealTransform positionFieldLookup;
		private final double scale;
		private final double offsetX;
		private final double offsetY;

		// scale level that this Transform applies to
		private int level;

		// manual transform defined on scale level 0
		private final RealTransform movingTransform;

		// 1 << level
		private final double levelscale;

		// 1 / (scale * (1 << level))
		private final double invlevelscale;

		CombinedTransform(final RealTransform positionFieldTransform,
				final double scale,
				final double offsetX,
				final double offsetY,
				final int level,
				final RealTransform movingTransform) {
			this.positionFieldLookup = positionFieldTransform.copy();
			this.movingTransform = movingTransform.copy();

			levelscale = 1 << level;
			invlevelscale =  1.0 / (scale * (1 << level));

			this.scale = scale;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
			this.level = level;
		}

		@Override
		public int numSourceDimensions() {
			return 2;
		}

		@Override
		public int numTargetDimensions() {
			return 2;
		}

		@Override
		public void apply(final double[] source, final double[] target) {
			apply(RealPoint.wrap(source), RealPoint.wrap(target));
		}

		private final double[] tmp1 = new double[2];
		private final double[] tmp2 = new double[2];
		private final double[] tmp3 = new double[2];
		private final double[] tmp4 = new double[2];
		private final RealPoint tmp1p = RealPoint.wrap(tmp1);
		private final RealPoint tmp2p = RealPoint.wrap(tmp2);
		private final RealPoint tmp3p = RealPoint.wrap(tmp3);
		private final RealPoint tmp4p = RealPoint.wrap(tmp4);

		@Override
		public void apply(final RealLocalizable source, final RealPositionable target) {
			final double x = source.getDoublePosition(0);
			final double y = source.getDoublePosition(1);
			// scale by (1<<level)
			tmp1[ 0 ] = x * levelscale;
			tmp1[ 1 ] = y * levelscale;
			// apply manualTransform
			movingTransform.apply(tmp1p, tmp2p);
			// scale by transformScale and subtract offset
			tmp3[0] = scale * tmp2[0] - offsetX;
			tmp3[1] = scale * tmp2[1] - offsetY;
			// apply positionField lookup
			positionFieldLookup.apply(tmp3p, tmp4p);
			// scale by 1 / (transformScale * (1 << level))
			target.setPosition(tmp4[0] * invlevelscale, 0);
			target.setPosition(tmp4[1] * invlevelscale, 1);
		}

		@Override
		public RealTransform copy() {
			return new CombinedTransform(positionFieldLookup, scale, offsetX, offsetY, level, movingTransform);
		}
	}
}
