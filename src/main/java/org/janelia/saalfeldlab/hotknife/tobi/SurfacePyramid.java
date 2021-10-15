package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import mpicbg.spim.data.sequence.DefaultVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.util.Grid;

/**
 * TODO
 * @param <T>
 * @param <V>
 */
public interface SurfacePyramid<T extends NativeType<T> & NumericType<T>, V extends Volatile<T> & NativeType<V> & NumericType<V>> {

	/**
	 * TODO
	 * @return
	 */
	SourceAndConverter<T> getSourceAndConverter();

	/**
	 * TODO
	 * @return
	 */
	int getNumMipmapLevels();

	/**
	 * TODO
	 * @param level
	 * @return
	 */
	RandomAccessibleInterval<T> getImg(int level);

	/**
	 * TODO
	 * @param level
	 * @return
	 */
	RandomAccessibleInterval<V> getVolatileImg(int level);

	/**
	 * TODO
	 * @return
	 */
	T getType();

	/**
	 * TODO
	 * @return
	 */
	V getVolatileType();


	class SurfaceSource<T extends NumericType<T>> implements Source<T> {

		private final T type;
		private final String name;
		private final RandomAccessibleInterval<T>[] imgs;
		private final RealRandomAccessible<T>[][] interpolatedImgs;
		private final double[] boundsMin;
		private final DefaultVoxelDimensions voxelDimensions = new DefaultVoxelDimensions(3);

		public SurfaceSource(final T type, final RandomAccessibleInterval<T>[] imgs, final String name) {
			this(type, imgs, new double[] {0, 0}, name);
		}

		public SurfaceSource(final T type, final RandomAccessibleInterval<T>[] imgs, final double[] boundsMin, final String name) {
			this.type = type;
			this.boundsMin = boundsMin;
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
			final long[] offset = Grid.floorScaled(boundsMin, 1.0 / s);
			transform.set(
					s, 0, 0, offset[0] << level,
					0, s, 0, offset[1] << level,
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

	// TODO: Should close() be added to the interface?
	//  It's not 100% clear what it should do...
	// void close();
}
