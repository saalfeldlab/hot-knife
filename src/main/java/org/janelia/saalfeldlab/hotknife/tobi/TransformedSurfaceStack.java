package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.viewer.SourceAndConverter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.imglib2.Volatile;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import org.janelia.saalfeldlab.n5.N5Reader;

import static org.janelia.saalfeldlab.hotknife.tobi.PositionFieldPyramid.createFullPyramid;

/**
 * Undo/redo stack of PositionFieldPyramid with baked incremental
 * transforms. Provides a {@link SourceAndConverter} of a SurfacePyarmid
 * transformed with the current PositionFieldPyramid.
 *
 * @param <T>
 * 		pixel type
 * @param <V>
 * 		volatile pixel type
 */
public class TransformedSurfaceStack<
		T extends NativeType<T> & NumericType<T>,
		V extends Volatile<T> & NativeType<V> & NumericType<V>> {

	private final SurfacePyramid<T, V> n5surfacePyramid;

	private final T type;
	private final V volatileType;

	private final int blockWidth;
	private final int minLevel;
	private final int maxLevel;

	// undo stack
	// positionFieldPyramids[0] is the one created from the N5 transform
	private final List<PositionFieldPyramid> positionFieldPyramids = new ArrayList<>();

	// current position in undo/redo stack (index into positionFieldPyramids)
	private int current;

	// n5surfacePyramid rendered through positionFieldPyramids[current]
	private SurfacePyramid<T, V> renderedSurfacePyramid;

	// the source currently to display in BDV
	private final DelegatingSourceAndConverter<T, V> socWrapper;

	public TransformedSurfaceStack(
			final N5Reader n5,
			final String dataset,
			final String transform,
			final int blockWidth,
			final String name) throws IOException {

		n5surfacePyramid = new N5SurfacePyramid<>(n5, dataset);
		type = n5surfacePyramid.getType();
		volatileType = n5surfacePyramid.getVolatileType();

		final PositionField n5positionField = new PositionField(n5, transform);

		this.blockWidth = blockWidth;
		minLevel = n5positionField.getLevel();
		maxLevel = n5surfacePyramid.getNumMipmapLevels() - 1;

		final PositionFieldPyramid fullPyramid = createFullPyramid(n5positionField, blockWidth, minLevel, maxLevel);
		positionFieldPyramids.add(fullPyramid);
		renderedSurfacePyramid = new RenderedSurfacePyramid<>(n5surfacePyramid, fullPyramid, blockWidth);

		socWrapper = new DelegatingSourceAndConverter<>(type, volatileType, name);
		socWrapper.setDelegate(renderedSurfacePyramid.getSourceAndConverter());
	}

	public SourceAndConverter<T> getSourceAndConverter() {
		return socWrapper.get();
	}

	public SourceAndConverter<T> createSocWrapper() {
		final DelegatingSourceAndConverter<T, V> soc = new DelegatingSourceAndConverter<>(type, volatileType,
				getSourceAndConverter().getSpimSource().getName());
		soc.setDelegate(getSourceAndConverter());
		return soc.get();
	}

	public PositionFieldPyramid getPositionFieldPyramid() {
		return positionFieldPyramids.get(current);
	}

	public void setIncrementalTransform(final RealTransform transform) {
		if (transform != null) {
			final SurfacePyramid<T, V> tsp = new TransformedSurfacePyramid<>(renderedSurfacePyramid, transform);
			socWrapper.setDelegate(tsp.getSourceAndConverter());
		} else {
			socWrapper.setDelegate(renderedSurfacePyramid.getSourceAndConverter());
		}
	}

	public void bakeIncrementalTransform(final RealTransform transform) {
		// We will put a new PositionFieldPyramid on the stack at index current+1.
		// Remove previous entries above index current.
		while (positionFieldPyramids.size() > current + 1)
			positionFieldPyramids.remove(positionFieldPyramids.size() - 1);

		final PositionFieldPyramid pfp = Bake.bakePositionFieldPyramid(
				positionFieldPyramids.get(current), transform,
				blockWidth, minLevel, maxLevel);
		positionFieldPyramids.add(pfp);

		prevCurrent = -1;
		updateRenderedSurfacePyramid(current + 1);
	}

	public void undo() {
		if (current == 0)
			return; // nothing to undo.

		updateRenderedSurfacePyramid(current - 1);
	}

	private int prevCurrent = -1;
	private SurfacePyramid<T, V> prevRenderedSurfacePyramid;

	private void updateRenderedSurfacePyramid(final int nextCurrent) {
		if (nextCurrent == prevCurrent) {
			final SurfacePyramid<T, V> tmp = prevRenderedSurfacePyramid;
			prevCurrent = current;
			prevRenderedSurfacePyramid = renderedSurfacePyramid;
			current = nextCurrent;
			renderedSurfacePyramid = tmp;
		} else {
			prevCurrent = current;
			prevRenderedSurfacePyramid = renderedSurfacePyramid;

			current = nextCurrent;
			final PositionFieldPyramid pfp = positionFieldPyramids.get(current);
			renderedSurfacePyramid = new RenderedSurfacePyramid<>(n5surfacePyramid, pfp, blockWidth);
		}
		socWrapper.setDelegate(renderedSurfacePyramid.getSourceAndConverter());
	}

	public void redo() {
		if (current == positionFieldPyramids.size() - 1)
			return; // nothing to redo.

		updateRenderedSurfacePyramid(current + 1);
	}
}
