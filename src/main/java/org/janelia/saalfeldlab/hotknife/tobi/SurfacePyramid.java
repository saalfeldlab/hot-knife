package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.img.cache.SimpleCacheArrayLoader;
import bdv.img.cache.VolatileGlobalCellCache;
import bdv.util.volatiles.VolatileTypeMatcher;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;
import mpicbg.spim.data.sequence.DefaultVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.cache.queue.BlockingFetchQueues;
import net.imglib2.cache.queue.FetcherThreads;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileByteArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileDoubleArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileFloatArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileIntArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileLongArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileShortArray;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import static bdv.BigDataViewer.createConverterToARGB;

/**
 * Surface pyramid is a 2D image read from N5 datasets named "s0", "s1", etc for
 * resolution levels. The pyramid levels are expected to be down-sampled on
 * pixel centers (no 0.5 shift).
 * <p>
 * Each resolution is available as volatile ({@link #getVolatileImg(int)}) and
 * non-volatile ({@link #getImg(int)}) {@code RandomAccessibleInterval} (both
 * lazily loaded from the N5).
 * <p>
 * The whole pyramid is packaged as a {@link #getSourceAndConverter()
 * SourceAndConverter} for displaying in BDV.
 *
 * @param <T> pixel type
 * @param <V> volatile pixel type
 */
public class SurfacePyramid<T extends NativeType<T> & NumericType<T>, V extends Volatile<T> & NativeType<V> & NumericType<V>> {

	private final T type;
	private final V volatileType;

	private final FetcherThreads fetchers;
	private final VolatileGlobalCellCache cache;
	private final RandomAccessibleInterval<T>[] imgs;
	private final RandomAccessibleInterval<V>[] vimgs;
	private final SourceAndConverter<T> sourceAndConverter;

	public SurfacePyramid(final N5Reader n5, final String group) throws IOException {
		this(n5, group, new Types<>(n5, group));
	}

	public SourceAndConverter<T> getSourceAndConverter() {
		return sourceAndConverter;
	}

	public int getNumMipmapLevels() {
		return imgs.length;
	}

	public RandomAccessibleInterval<T> getImg(final int level) {
		return imgs[level];
	}

	public RandomAccessibleInterval<V> getVolatileImg(final int level) {
		return vimgs[level];
	}

	public void close() {
		fetchers.shutdown();
		cache.clearCache();
	}

	public T getType() {
		return type;
	}

	public V getVolatileType() {
		return volatileType;
	}

	private static class Types<T extends NativeType<T>, V> {

		final T type;
		final V volatileType;

		Types(final N5Reader n5, final String group) throws IOException {
			final DatasetAttributes attributes = n5.getDatasetAttributes(group + "/s0");
			type = N5Utils.type(attributes.getDataType());
			volatileType = Cast.unchecked(VolatileTypeMatcher.getVolatileTypeForType(type));
		}
	}

	private SurfacePyramid(final N5Reader n5, final String group, Types<T, V> types) throws IOException {
		this(n5, group, types.type, types.volatileType);
	}

	private SurfacePyramid(final N5Reader n5, final String group, final T type, final V volatileType) throws IOException {
		final int numScales = n5.list(group).length;

		this.type = type;
		this.volatileType = volatileType;

		final int numFetcherThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
		final BlockingFetchQueues<Callable<?>> queue = new BlockingFetchQueues<>(numScales, numFetcherThreads);
		fetchers = new FetcherThreads(queue, numFetcherThreads);
		cache = new VolatileGlobalCellCache(queue);

		final int timepointId = 0; // TODO: what can this be re-purposed for ???
		final int setupId = 0; // TODO: what can this be re-purposed for ???

		imgs = new RandomAccessibleInterval[numScales];
		vimgs = new RandomAccessibleInterval[numScales];
		for (int level = 0; level < numScales; ++level) {
			final String pathName = group + "/s" + level;
			final SimpleCacheArrayLoader<?> loader = createCacheArrayLoader(n5, pathName);
			final DatasetAttributes attributes = n5.getDatasetAttributes(pathName);
			final long[] dimensions = attributes.getDimensions();
			final int[] cellDimensions = attributes.getBlockSize();
			final CellGrid grid = new CellGrid(dimensions, cellDimensions);
			final int priority = numScales - 1 - level;
			final CacheHints cacheHintsT = new CacheHints(LoadingStrategy.BLOCKING, priority, false);
			imgs[level] = cache.createImg(grid, timepointId, setupId, level, cacheHintsT, loader, type);
			final CacheHints cacheHintsV = new CacheHints(LoadingStrategy.BUDGETED, priority, false);
			vimgs[level] = cache.createImg(grid, timepointId, setupId, level, cacheHintsV, loader, volatileType);
		}

		final Source<V> vs = new SurfaceSource<>(volatileType, vimgs, "flat");
		final SourceAndConverter<V> vsoc = new SourceAndConverter<>(vs, createConverterToARGB(volatileType));
		final Source<T> s = new SurfaceSource<>(type, imgs, "flat");
		sourceAndConverter = new SourceAndConverter<>(s, createConverterToARGB(type), vsoc);
	}


	private static class SurfaceSource<T extends NumericType<T>> implements Source<T> {

		private final T type;
		private final String name;
		private final RandomAccessibleInterval<T>[] imgs;
		private final RealRandomAccessible<T>[][] interpolatedImgs;
		private final DefaultVoxelDimensions voxelDimensions = new DefaultVoxelDimensions(3);

		SurfaceSource(final T type, final RandomAccessibleInterval<T>[] imgs, final String name) {
			this.type = type;
			this.name = name;
			this.imgs = imgs;

			interpolatedImgs = new RealRandomAccessible[imgs.length][];
			final T zero = type.createVariable();
			zero.setZero();
			final InterpolatorFactory<T, RandomAccessible<T>> nearestNeighbor = new NearestNeighborInterpolatorFactory<>();
			final InterpolatorFactory<T, RandomAccessible<T>> nLinear = new ClampingNLinearInterpolatorFactory<>();
			for (int i = 0; i < imgs.length; i++) {
				final RandomAccessible<T> ext = Views.extendValue(imgs[i], zero);
				interpolatedImgs[i] = new RealRandomAccessible[] {
						Views.interpolate(ext, nearestNeighbor),
						Views.interpolate(ext, nLinear)
				};
			}
		}

		@Override
		public boolean isPresent(final int t) {
			return true;
		}

		@Override
		public RandomAccessibleInterval<T> getSource(final int t, final int level) {
			return Views.addDimension(imgs[level], 0, 0);
		}

		@Override
		public RealRandomAccessible<T> getInterpolatedSource(final int t, final int level, final Interpolation method) {
			return RealViews.addDimension(interpolatedImgs[level][method.ordinal()]);
		}

		@Override
		public void getSourceTransform(final int t, final int level, final AffineTransform3D transform) {
			final int s = 1 << level;
			transform.set(
					s, 0, 0, 0,
					0, s, 0, 0,
					0, 0, s, 0);
		}

		@Override
		public T getType() {
			return type;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public VoxelDimensions getVoxelDimensions() {
			return voxelDimensions;
		}

		@Override
		public int getNumMipmapLevels() {
			return imgs.length;
		}
	}


	// TODO: this should be in bdv-core. (it's there but not released yet)
	private static SimpleCacheArrayLoader<?> createCacheArrayLoader(final N5Reader n5, final String pathName) throws IOException {
		final DatasetAttributes attributes = n5.getDatasetAttributes(pathName);
		final int numElements = (int) Intervals.numElements(attributes.getBlockSize());
		switch (attributes.getDataType()) {
		case UINT8:
		case INT8:
			return new N5CacheArrayLoader<>(n5, pathName, attributes,
					dataBlock -> new VolatileByteArray(Cast.unchecked(dataBlock.getData()), true),
					() -> new VolatileByteArray(numElements, true));
		case UINT16:
		case INT16:
			return new N5CacheArrayLoader<>(n5, pathName, attributes,
					dataBlock -> new VolatileShortArray(Cast.unchecked(dataBlock.getData()), true),
					() -> new VolatileShortArray(numElements, true));
		case UINT32:
		case INT32:
			return new N5CacheArrayLoader<>(n5, pathName, attributes,
					dataBlock -> new VolatileIntArray(Cast.unchecked(dataBlock.getData()), true),
					() -> new VolatileIntArray(numElements, true));
		case UINT64:
		case INT64:
			return new N5CacheArrayLoader<>(n5, pathName, attributes,
					dataBlock -> new VolatileLongArray(Cast.unchecked(dataBlock.getData()), true),
					() -> new VolatileLongArray(numElements, true));
		case FLOAT32:
			return new N5CacheArrayLoader<>(n5, pathName, attributes,
					dataBlock -> new VolatileFloatArray(Cast.unchecked(dataBlock.getData()), true),
					() -> new VolatileFloatArray(numElements, true));
		case FLOAT64:
			return new N5CacheArrayLoader<>(n5, pathName, attributes,
					dataBlock -> new VolatileDoubleArray(Cast.unchecked(dataBlock.getData()), true),
					() -> new VolatileDoubleArray(numElements, true));
		default:
			throw new IllegalArgumentException();
		}
	}


	// TODO: this should be in bdv-core. (it's there but not released yet)
	private static class N5CacheArrayLoader<A> implements SimpleCacheArrayLoader<A> {

		private final N5Reader n5;
		private final String pathName;
		private final DatasetAttributes attributes;
		private final Function<DataBlock<?>, A> createArray;
		private final Supplier<A> emptyArray;

		N5CacheArrayLoader(final N5Reader n5, final String pathName, final DatasetAttributes attributes,
				final Function<DataBlock<?>, A> createArray,
				final Supplier<A> emptyArray) {
			this.n5 = n5;
			this.pathName = pathName;
			this.attributes = attributes;
			this.createArray = createArray;
			this.emptyArray = emptyArray;
		}

		@Override
		public A loadArray(final long[] gridPosition) throws IOException {
			final DataBlock<?> dataBlock = n5.readBlock(pathName, attributes, gridPosition);
			if (dataBlock == null)
				return emptyArray.get();
			else
				return createArray.apply(dataBlock);
		}
	}
}
