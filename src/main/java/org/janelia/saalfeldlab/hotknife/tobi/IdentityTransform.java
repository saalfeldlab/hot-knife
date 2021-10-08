package org.janelia.saalfeldlab.hotknife.tobi;

import net.imglib2.RealLocalizable;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.RealTransform;

public class IdentityTransform {

	public static RealTransform get() {
		return transform;
	}

	private static final RealTransform transform = new RealTransform() {

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
			target[0] = source[0];
			target[1] = source[1];
		}

		@Override
		public void apply(final RealLocalizable source, final RealPositionable target) {
			target.setPosition(source.getDoublePosition(0),0);
			target.setPosition(source.getDoublePosition(1),1);
		}

		@Override
		public RealTransform copy() {
			return this;
		}
	};
}
