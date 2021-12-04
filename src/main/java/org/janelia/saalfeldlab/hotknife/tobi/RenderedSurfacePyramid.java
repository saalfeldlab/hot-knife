package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Lazy;

import static bdv.BigDataViewer.createConverterToARGB;
import static net.imglib2.img.basictypeaccess.AccessFlags.VOLATILE;

/**
 * A SurfacePyramid that is obtained by applying a PositionFieldPyramid to a
 * source SurfacePyramid.
 * <p>
 * Each resolution is available as volatile ({@link #getVolatileImg(int)}) and
 * non-volatile ({@link #getImg(int)}) {@code RandomAccessibleInterval} (both
 * lazily loaded).
 * <p>
 * The whole pyramid is packaged as a {@link #getSourceAndConverter()
 * SourceAndConverter} for displaying in BDV.
 *
 * @param <T>
 * 		pixel type
 * @param <V>
 * 		volatile pixel type
 */
public class RenderedSurfacePyramid<T extends NativeType<T> & NumericType<T>, V extends Volatile<T> & NativeType<V> & NumericType<V>>
		implements SurfacePyramid<T, V> {

	private final T type;
	private final V volatileType;

	private final RandomAccessibleInterval<T>[] imgs;
	private final RandomAccessibleInterval<V>[] vimgs;
	private final SourceAndConverter<T> sourceAndConverter;
	private final SharedQueue queue;
	private final double[] boundsMin;

	@Override
	public SourceAndConverter<T> getSourceAndConverter() {
		return sourceAndConverter;
	}

	@Override
	public int getNumMipmapLevels() {
		return imgs.length;
	}

	@Override
	public RandomAccessibleInterval<T> getImg(final int level) {
		return imgs[level];
	}

	@Override
	public RandomAccessibleInterval<V> getVolatileImg(final int level) {
		return vimgs[level];
	}

	@Override
	public T getType() {
		return type;
	}

	@Override
	public V getVolatileType() {
		return volatileType;
	}

	@Override
	public double[] getBoundsMin() {
		return boundsMin;
	}

//	@Override
//	public void close() {
//		// TODO: if passed queue was given as constructor argument, don't shut it down
//		queue.shutdown();
//	}

	public RenderedSurfacePyramid(
			final SurfacePyramid<T, V> surfacePyramid,
			final PositionFieldPyramid positionFieldPyramid,
			final int blockSize) {

		this.type = surfacePyramid.getType();
		this.volatileType = surfacePyramid.getVolatileType();
		final int numScales = surfacePyramid.getNumMipmapLevels();

		final int numFetcherThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
		queue = new SharedQueue(numFetcherThreads, numScales);
		// TODO: Optionally pass queue as constructor argument
		//   	 Does queue clamp priorities?

		final int timepointId = 0; // TODO: what can this be re-purposed for ???
		final int setupId = 0; // TODO: what can this be re-purposed for ???

		imgs = new RandomAccessibleInterval[numScales];
		vimgs = new RandomAccessibleInterval[numScales];
		boundsMin = positionFieldPyramid.getBoundsMin();
		final double[] boundsMax = positionFieldPyramid.getBoundsMax();
		for (int level = 0; level < numScales; ++level) {

			final double scale = 1.0 / (1 << level);
			final long[] min = Grid.floorScaled(boundsMin, scale);
			final long[] max = Grid.ceilScaled(boundsMax, scale);
			final Dimensions dims = new FinalInterval(min, max);
			final int[] blockDims = {blockSize, blockSize};
			CellLoader<T> loader = new BakedTransformedSurfaceLoader<>(positionFieldPyramid, surfacePyramid, level);
			imgs[level] = Lazy.createImg(dims, blockDims, type, AccessFlags.setOf(VOLATILE), loader);

			final int priority = numScales - 1 - level;
			final CacheHints hints = new CacheHints(LoadingStrategy.VOLATILE, priority, false);
			vimgs[level] = VolatileViews.wrapAsVolatile(imgs[level], queue, hints);
		}

		final Source<V> vs = new SurfaceSource<>(volatileType, vimgs, boundsMin, "baked");
		final SourceAndConverter<V> vsoc = new SourceAndConverter<>(vs, createConverterToARGB(volatileType));
		final Source<T> s = new SurfaceSource<>(type, imgs, boundsMin, "baked");
		sourceAndConverter = new SourceAndConverter<>(s, createConverterToARGB(type), vsoc);
	}


	static class BakedTransformedSurfaceLoader<T extends NativeType<T> & NumericType<T>> implements CellLoader<T> {

		private final PositionField positionField;
		private final SurfacePyramid<T, ?> pyramid;
		private final int level;

		public BakedTransformedSurfaceLoader(
				final PositionFieldPyramid positionFieldPyramid,
				final SurfacePyramid<T, ?> pyramid,
				final int level) {
			this.positionField = positionFieldPyramid.getPositionField(level);
			this.pyramid = pyramid;
			this.level = level;
		}

		@Override
		public void load(final SingleCellArrayImg<T, ?> cell) throws Exception {
			final int coX = (int) cell.min(0);
			final int csX = (int) cell.dimension(0);
			final int csY = (int) cell.dimension(1);

			final double scale = 1.0 / (1 << level);
			final long[] min = Grid.floorScaled(positionField.getBoundsMin(), scale);

			final RandomAccessibleInterval<T> img = pyramid.getImg(level);
			final RealTransformRandomAccessible<T, ?> timg = new RealTransformRandomAccessible<>(
					Views.interpolate(
							Views.extendZero(img),
							new ClampingNLinearInterpolatorFactory<>()),
					positionField.getTransform(level));

			// TODO: pyramid.getMinBounds() should have an influence on how things shift around
			//       (additional translation RealTransform in positionField.getTransform(level, translation) or somthing like that?)
			//       Or we just make it clear that pyramid is not yet position-field-transformed.

			final RandomAccess<T> in = Views.translateInverse(timg, min).randomAccess();
			final RandomAccess<T> out = cell.randomAccess();
			cell.min(out);
			cell.min(in);
			for (int y = 0; y < csY; ++y) {
				for (int x = 0; x < csX; ++x) {
					out.get().set(in.get());
					out.fwd(0);
					in.fwd(0);
				}
				out.setPosition(coX, 0);
				out.fwd(1);
				in.setPosition(coX, 0);
				in.fwd(1);
			}
		}
	}
}
