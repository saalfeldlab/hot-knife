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
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.util.Grid;

import static bdv.BigDataViewer.createConverterToARGB;

public class TransformedSurfacePyramid<T extends NativeType<T> & NumericType<T>, V extends Volatile<T> & NativeType<V> & NumericType<V>> {

	private final SourceAndConverter<T> sourceAndConverter;

	public TransformedSurfacePyramid(SurfacePyramid<T, V> pyramid, PositionField positionField, RealTransform movingTransform) {
		this(pyramid, positionField, movingTransform, "transformed");
	}

	public TransformedSurfacePyramid(SurfacePyramid<T, V> pyramid, PositionField positionField, RealTransform movingTransform, final String name) {
		final int numScales = pyramid.getNumMipmapLevels();
		final RandomAccessibleInterval<T>[] imgs = new RandomAccessibleInterval[numScales];
		final RandomAccessibleInterval<V>[] vimgs = new RandomAccessibleInterval[numScales];

		for (int level = 0; level < numScales; ++level) {
			imgs[level] = pyramid.getImg(level);
			vimgs[level] = pyramid.getVolatileImg(level);
		}

		final T type = pyramid.getType();
		final V volatileType = pyramid.getVolatileType();

		final Source<V> vs = new TransformedSurfaceSource<>(volatileType, vimgs, positionField, movingTransform, name);
		final SourceAndConverter<V> vsoc = new SourceAndConverter<>(vs, createConverterToARGB(volatileType));
		final Source<T> s = new TransformedSurfaceSource<>(type, imgs, positionField, movingTransform, name);
		sourceAndConverter = new SourceAndConverter<>(s, createConverterToARGB(type), vsoc);
	}

	public SourceAndConverter<T> getSourceAndConverter() {
		return sourceAndConverter;
	}


	private static class TransformedSurfaceSource<T extends NumericType<T>> implements Source<T> {

		private final T type;
		private final String name;
		private final RandomAccessibleInterval<T>[] rais;
		private final RealRandomAccessible<T>[][] rras;
		private final DefaultVoxelDimensions voxelDimensions = new DefaultVoxelDimensions(3);

		TransformedSurfaceSource(
				final T type,
				final RandomAccessibleInterval<T>[] imgs,
				final PositionField positionField,
				final RealTransform movingTransform,
				final String name) {
			this.type = type;
			this.name = name;

			rais = new RandomAccessibleInterval[imgs.length];
			rras = new RealRandomAccessible[imgs.length][];

			final T zero = type.createVariable();
			zero.setZero();
			final InterpolatorFactory<T, RandomAccessible<T>> nearestNeighbor = new NearestNeighborInterpolatorFactory<>();
			final InterpolatorFactory<T, RandomAccessible<T>> nLinear = new ClampingNLinearInterpolatorFactory<>();
			for (int i = 0; i < imgs.length; i++) {
				final RandomAccessible<T> ext = Views.extendValue(imgs[i], zero);
				rras[i] = new RealRandomAccessible[] {
						new RealTransformRealRandomAccessible<>(
								Views.interpolate(ext, nearestNeighbor),
								positionField.getTransform(i, movingTransform)),
						new RealTransformRealRandomAccessible<>(
								Views.interpolate(ext, nLinear),
								positionField.getTransform(i, movingTransform)),
				};

				final long[] min = Grid.floorScaled(positionField.getBoundsMin(), 1);
				final long[] max = Grid.ceilScaled(positionField.getBoundsMax(), 1);
				rais[i] = Views.interval(
						new RealTransformRandomAccessible<>(
								Views.interpolate(ext, nearestNeighbor),
								positionField.getTransform(i, movingTransform)),
						min, max);
			}
		}

		@Override
		public boolean isPresent(final int t) {
			return true;
		}

		@Override
		public RandomAccessibleInterval<T> getSource(final int t, final int level) {
			return Views.addDimension(rais[level], 0, 0);
		}

		@Override
		public RealRandomAccessible<T> getInterpolatedSource(final int t, final int level, final Interpolation method) {
			return RealViews.addDimension(rras[level][method.ordinal()]);
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
			return rais.length;
		}
	}
}
