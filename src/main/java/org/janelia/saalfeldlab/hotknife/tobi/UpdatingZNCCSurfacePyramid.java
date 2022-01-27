package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.viewer.SourceAndConverter;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileFloatType;
import org.scijava.listeners.Listeners;

public class UpdatingZNCCSurfacePyramid {

	private final TransformedSurfaceStack<?, ?> surfaceStack0;
	private final TransformedSurfaceStack<?, ?> surfaceStack1;
	private final int blockWidth;
	private int windowWidth = 15;

	// the source currently to display in BDV
	private final DelegatingSourceAndConverter<FloatType, VolatileFloatType> socWrapper;

	private final Listeners.List<Runnable> changeListeners = new Listeners.SynchronizedList<>();

	UpdatingZNCCSurfacePyramid(
			final TransformedSurfaceStack<?, ?> surfaceStack0,
			final TransformedSurfaceStack<?, ?> surfaceStack1,
			final int blockWidth) {
		this.surfaceStack0 = surfaceStack0;
		this.surfaceStack1 = surfaceStack1;
		this.blockWidth = blockWidth;
		socWrapper = new DelegatingSourceAndConverter<>(new FloatType(), new VolatileFloatType(), "cross correlation");
		surfaceStack0.changeListeners().add(this::update);
		surfaceStack1.changeListeners().add(this::update);
		update();
	}

	public Listeners<Runnable> changeListeners() {return changeListeners;}

	private void update()
	{
		final ZNCCSurfacePyramid zncc = new ZNCCSurfacePyramid(
				surfaceStack0.getRenderedSurfacePyramid(),
				surfaceStack1.getRenderedSurfacePyramid(),
				blockWidth,
				windowWidth);
		socWrapper.setDelegate(zncc.getSourceAndConverter());
		changeListeners.list.forEach(Runnable::run);
	}

	public int getCorrelationWindowWidth() {
		return windowWidth;
	}

	public void setCorrelationWindowWidth(int size) {
		if (size % 2 == 0)
			++size;
		if (size < 5)
			size = 5;
		if (size != windowWidth) {
			windowWidth = size;
			update();
		}
	}

	public SourceAndConverter<FloatType> getSourceAndConverter() {
		return socWrapper.get();
	}
}
