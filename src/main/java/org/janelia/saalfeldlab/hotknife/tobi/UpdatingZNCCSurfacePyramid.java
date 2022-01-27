package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.viewer.SourceAndConverter;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileFloatType;

public class UpdatingZNCCSurfacePyramid {

	private final TransformedSurfaceStack<?, ?> surfaceStack0;
	private final TransformedSurfaceStack<?, ?> surfaceStack1;
	private int blockSize;

	// the source currently to display in BDV
	private final DelegatingSourceAndConverter<FloatType, VolatileFloatType> socWrapper;

	UpdatingZNCCSurfacePyramid(
			final TransformedSurfaceStack<?, ?> surfaceStack0,
			final TransformedSurfaceStack<?, ?> surfaceStack1,
			final int blockSize) {
		this.surfaceStack0 = surfaceStack0;
		this.surfaceStack1 = surfaceStack1;
		this.blockSize = blockSize;
		socWrapper = new DelegatingSourceAndConverter<>(new FloatType(), new VolatileFloatType(), "cross correlation");
		surfaceStack0.changeListeners().add(this::update);
		surfaceStack1.changeListeners().add(this::update);
		update();
	}

	private void update()
	{
		final ZNCCSurfacePyramid zncc = new ZNCCSurfacePyramid(
				surfaceStack0.getRenderedSurfacePyramid(),
				surfaceStack1.getRenderedSurfacePyramid(),
				blockSize);
		socWrapper.setDelegate(zncc.getSourceAndConverter());
	}

	public SourceAndConverter<FloatType> getSourceAndConverter() {
		return socWrapper.get();
	}
}
