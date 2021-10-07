package org.janelia.saalfeldlab.hotknife.tobi;

import java.io.IOException;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

public class PositionField {

	final RandomAccessibleInterval<DoubleType> positionField;
	final RealTransform transform;
	final long[] translation;
	final double transformScale;

	public PositionField(final N5Reader n5, final String datasetName)
			throws IOException {
		transformScale = n5.getAttribute(datasetName, "scale", double.class);
		final double[] boundsMin = n5.getAttribute(datasetName, "boundsMin", double[].class);
		translation = Grid.floorScaled(boundsMin, transformScale);

		positionField = N5Utils.open(n5, datasetName);
		transform = org.janelia.saalfeldlab.hotknife.util.Transform.createPositionFieldTransform(positionField);
	}

	public RealTransform getTransform(final int level) {
		return new Transform(transform, transformScale * (1 << level), translation[0], translation[1]);
	}

	private static class Transform implements RealTransform {

		private final RealTransform pft;
		private final double scale;
		private final double offsetX;
		private final double offsetY;

		Transform(final RealTransform positionFieldTransform,
				final double scale,
				final double offsetX,
				final double offsetY) {
			pft = positionFieldTransform.copy();
			this.scale = scale;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
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
			apply(RealPoint.wrap(source), RealPoint.wrap(target)); // TODO
		}

		private final double[] pftSource = new double[2];
		private final RealPoint pftSourcePoint = RealPoint.wrap(pftSource);

		private final double[] pftTarget = new double[2];
		private final RealPoint pftTargetPoint = RealPoint.wrap(pftTarget);

		@Override
		public void apply(final RealLocalizable source, final RealPositionable target) {
			double x = source.getDoublePosition(0);
			double y = source.getDoublePosition(1);
			pftSource[0] = scale * x - offsetX;
			pftSource[1] = scale * y - offsetY;
			pft.apply(pftSourcePoint, pftTargetPoint);
			target.setPosition(pftTarget[0] / scale, 0);
			target.setPosition(pftTarget[1] / scale, 1);
		}

		@Override
		public RealTransform copy() {
			return new Transform(pft, scale, offsetX, offsetY);
		}
	}
}
