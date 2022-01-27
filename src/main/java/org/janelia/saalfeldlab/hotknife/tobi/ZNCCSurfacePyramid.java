package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import java.util.Arrays;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.blk.view.ViewBlocks;
import net.imglib2.blk.view.ViewProps;
import net.imglib2.blk.zncc.ZNCCFloat;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Lazy;

import static bdv.BigDataViewer.createConverterToARGB;
import static net.imglib2.img.basictypeaccess.AccessFlags.VOLATILE;

/**
 * A SurfacePyramid that is obtained by computing ZNCC from two source SurfacePyramids.
 * <p>
 * Each resolution is available as volatile ({@link #getVolatileImg(int)}) and
 * non-volatile ({@link #getImg(int)}) {@code RandomAccessibleInterval} (both
 * lazily loaded).
 * <p>
 * The whole pyramid is packaged as a {@link #getSourceAndConverter()
 * SourceAndConverter} for displaying in BDV.
 */
public class ZNCCSurfacePyramid implements SurfacePyramid<FloatType, VolatileFloatType> {

	private final FloatType type = new FloatType();
	private final VolatileFloatType volatileType = new VolatileFloatType();

	private final RandomAccessibleInterval<FloatType>[] imgs;
	private final RandomAccessibleInterval<VolatileFloatType>[] vimgs;
	private final SourceAndConverter<FloatType> sourceAndConverter;
	private final SharedQueue queue;
	private final double[] boundsMin = new double[ 2 ];
	private final double[] boundsMax = new double[ 2 ];

	@Override
	public SourceAndConverter<FloatType> getSourceAndConverter() {
		return sourceAndConverter;
	}

	@Override
	public int getNumMipmapLevels() {
		return imgs.length;
	}

	@Override
	public RandomAccessibleInterval<FloatType> getImg(final int level) {
		return imgs[level];
	}

	@Override
	public RandomAccessibleInterval<VolatileFloatType> getVolatileImg(final int level) {
		return vimgs[level];
	}

	@Override
	public FloatType getType() {
		return type;
	}

	@Override
	public VolatileFloatType getVolatileType() {
		return volatileType;
	}

	@Override
	public double[] getBoundsMin() {
		return boundsMin;
	}

	@Override
	public double[] getBoundsMax() {
		return boundsMax;
	}

//	@Override
//	public void close() {
//		// TODO: if passed queue was given as constructor argument, don't shut it down
//		queue.shutdown();
//	}

	public ZNCCSurfacePyramid(
			final SurfacePyramid<?, ?> surfacePyramid0,
			final SurfacePyramid<?, ?> surfacePyramid1,
			final int blockSize,
			final int windowWidth) {

		final int numScales = surfacePyramid0.getNumMipmapLevels();

		final int numFetcherThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
		queue = new SharedQueue(numFetcherThreads, numScales);
		// TODO: Optionally pass queue as constructor argument
		//   	 Does queue clamp priorities? --> Yes

		final int timepointId = 0; // TODO: what can this be re-purposed for ???
		final int setupId = 0; // TODO: what can this be re-purposed for ???

		imgs = new RandomAccessibleInterval[numScales];
		vimgs = new RandomAccessibleInterval[numScales];

		// Our boundsMin / boundsMax are min/max of pyramid0 and pyramid1
		final double[] bmin0 = surfacePyramid0.getBoundsMin();
		final double[] bmin1 = surfacePyramid1.getBoundsMin();
		boundsMin[0] = Math.min(bmin0[0], bmin1[0]);
		boundsMin[1] = Math.min(bmin0[1], bmin1[1]);
		
		final double[] bmax0 = surfacePyramid0.getBoundsMax();
		final double[] bmax1 = surfacePyramid1.getBoundsMax();
		boundsMax[0] = Math.max(bmax0[0], bmax1[0]);
		boundsMax[1] = Math.max(bmax0[1], bmax1[1]);
		
		for (int level = 0; level < numScales; ++level) {

			final double scale = 1.0 / (1 << level);
			final long[] min = Grid.floorScaled(boundsMin, scale);
			final long[] max = Grid.ceilScaled(boundsMax, scale);
			final Dimensions dims = new FinalInterval(min, max);
			final int[] blockDims = {blockSize, blockSize};

			final long[] shift0 = Grid.floorScaled(bmin0, scale);
			shift0[0] -= min[0];
			shift0[1] -= min[1];
			final long[] shift1 = Grid.floorScaled(bmin1, scale);
			shift1[0] -= min[0];
			shift1[1] -= min[1];
			CellLoader<FloatType> loader = new ZNCCLoader(surfacePyramid0, shift0, surfacePyramid1, shift1, windowWidth, level);
			imgs[level] = Lazy.createImg(dims, blockDims, type, AccessFlags.setOf(VOLATILE), loader);

			final int priority = numScales - 1 - level;
			final CacheHints hints = new CacheHints(LoadingStrategy.VOLATILE, priority, false);
			vimgs[level] = VolatileViews.wrapAsVolatile(imgs[level], queue, hints);
		}

		final Source<VolatileFloatType> vs = new SurfaceSource<>(volatileType, vimgs, this.boundsMin, "zncc");
		final SourceAndConverter<VolatileFloatType> vsoc = new SourceAndConverter<>(vs, createConverterToARGB(volatileType));
		final Source<FloatType> s = new SurfaceSource<>(type, imgs, this.boundsMin, "zncc");
		sourceAndConverter = new SourceAndConverter<>(s, createConverterToARGB(type), vsoc);
	}


	static class ZNCCLoader implements CellLoader<FloatType> {

		private final ThreadLocal<ViewBlocks<FloatType>> tlBlocksImgI;
		private final ThreadLocal<ViewBlocks<FloatType>> tlBlocksImgJ;
		private final ThreadLocal<ZNCCFloat> tlZncc;

		public ZNCCLoader(
				final SurfacePyramid<?, ?> pyramid0,
				final long[] shift0,
				final SurfacePyramid<?, ?> pyramid1,
				final long[] shift1,
				final int windowWidth,
				final int level) {

			final RandomAccessibleInterval<?> imgIunpadded = pyramid0.getImg(level);
			tlBlocksImgI = ThreadLocal.withInitial( () -> new ViewBlocks<>(
					new ViewProps(Views.translate(Views.extendMirrorSingle(imgIunpadded), shift0)),
					new FloatType())
			);
			final RandomAccessibleInterval<?> imgJunpadded = pyramid1.getImg(level);
			tlBlocksImgJ = ThreadLocal.withInitial( () -> new ViewBlocks<>(
					new ViewProps(Views.translate(Views.extendMirrorSingle(imgJunpadded), shift1)),
					new FloatType())
			);

			final int[] windowSize = new int[] {windowWidth, windowWidth};
			tlZncc = ThreadLocal.withInitial( () -> new ZNCCFloat( windowSize, true ) );
		}

		@Override
		public void load(final SingleCellArrayImg<FloatType, ?> cell) throws Exception {

			final ZNCCFloat zncc = tlZncc.get();
			final ViewBlocks<FloatType> blocksImgI = tlBlocksImgI.get();
			final ViewBlocks<FloatType> blocksImgJ = tlBlocksImgJ.get();

			final int[] srcPos = Intervals.minAsIntArray(cell);
			final float[] dest = (float[]) cell.getStorageArray();

			zncc.setTargetSize(Intervals.dimensionsAsIntArray(cell));
			final int[] sourceOffset = zncc.getSourceOffset();
			for (int d = 0; d < srcPos.length; d++)
				srcPos[d] += sourceOffset[d];
			final int[] size = zncc.getSourceSize();
			final float[][] src = zncc.getSourceBuffers();
			blocksImgI.copy(srcPos, src[0], size);
			blocksImgJ.copy(srcPos, src[1], size);
			zncc.compute(src, dest);
		}
	}
}
