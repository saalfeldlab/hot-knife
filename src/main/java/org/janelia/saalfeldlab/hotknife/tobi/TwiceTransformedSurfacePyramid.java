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

/**
 * A {@code SurfacePyramid} transformed by a single {@code PositionField} and concatenated incremental transform.
 * <p>
 * The whole pyramid is packaged as a {@link #getSourceAndConverter()
 * SourceAndConverter} for displaying in BDV.
 *
 * @param <T> pixel type
 * @param <V> volatile pixel type
 */
public class TwiceTransformedSurfacePyramid<T extends NativeType<T> & NumericType<T>, V extends Volatile<T> & NativeType<V> & NumericType<V>>
		implements SurfacePyramid<T, V> {

	private final SurfacePyramid<T, V> pyramid;
	private final PositionFieldPyramid positionFieldPyramid;
	private final RandomAccessibleInterval<T>[] imgs;
	private final RandomAccessibleInterval<V>[] vimgs;
	private final SourceAndConverter<T> sourceAndConverter;

	public TwiceTransformedSurfacePyramid(
			final SurfacePyramid<T, V> pyramid,
			final PositionFieldPyramid positionFieldPyramid,
			final RealTransform incrementalTransform) {
		this(pyramid, positionFieldPyramid, incrementalTransform, "transformed");
	}

	public TwiceTransformedSurfacePyramid(
			final SurfacePyramid<T, V> pyramid,
			final PositionFieldPyramid positionFieldPyramid,
			final RealTransform incrementalTransform,
			final String name) {
		this.pyramid = pyramid;
		this.positionFieldPyramid = positionFieldPyramid;
		final int numScales = pyramid.getNumMipmapLevels();
		final RandomAccessibleInterval<T>[] imgs = new RandomAccessibleInterval[numScales];
		final RandomAccessibleInterval<V>[] vimgs = new RandomAccessibleInterval[numScales];
		for (int level = 0; level < numScales; ++level) {
			imgs[level] = pyramid.getImg(level);
			vimgs[level] = pyramid.getVolatileImg(level);
		}

		final T type = pyramid.getType();
		final V volatileType = pyramid.getVolatileType();

		final TwiceTransformedSurfaceSource<V> vs = new TwiceTransformedSurfaceSource<>(volatileType, vimgs, positionFieldPyramid, incrementalTransform, name);
		final SourceAndConverter<V> vsoc = new SourceAndConverter<>(vs, createConverterToARGB(volatileType));
		final TwiceTransformedSurfaceSource<T> s = new TwiceTransformedSurfaceSource<>(type, imgs, positionFieldPyramid, incrementalTransform, name);
		sourceAndConverter = new SourceAndConverter<>(s, createConverterToARGB(type), vsoc);
		this.vimgs = vs.rais;
		this.imgs = s.rais;
	}

	@Override
	public SourceAndConverter<T> getSourceAndConverter() {
		return sourceAndConverter;
	}

	@Override
	public int getNumMipmapLevels() {
		return pyramid.getNumMipmapLevels();
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
		return pyramid.getType();
	}

	@Override
	public V getVolatileType() {
		return pyramid.getVolatileType();
	}

	@Override
	public double[] getBoundsMin() {
		return positionFieldPyramid.getBoundsMin();
	}

	@Override
	public double[] getBoundsMax() {
		return positionFieldPyramid.getBoundsMax();
	}


	private static class TwiceTransformedSurfaceSource<T extends NumericType<T>> implements Source<T> {

		private final T type;
		private final String name;
		final RandomAccessibleInterval<T>[] rais;
		private final RealRandomAccessible<T>[][] rras;
		private final DefaultVoxelDimensions voxelDimensions = new DefaultVoxelDimensions(3);

		TwiceTransformedSurfaceSource(
				final T type,
				final RandomAccessibleInterval<T>[] imgs,
				final PositionFieldPyramid positionFieldPyramid,
				final RealTransform incrementalTransform,
				final String name) {
			this.type = type;
			this.name = name;

			rais = new RandomAccessibleInterval[imgs.length];
			rras = new RealRandomAccessible[imgs.length][];

			final T zero = type.createVariable();
			zero.setZero();
			final InterpolatorFactory<T, RandomAccessible<T>> nearestNeighbor = new NearestNeighborInterpolatorFactory<>();
			final InterpolatorFactory<T, RandomAccessible<T>> nLinear = new ClampingNLinearInterpolatorFactory<>();
			for (int level = 0; level < imgs.length; level++) {
				final PositionField positionField = positionFieldPyramid.getPositionField(level);
				final RandomAccessible<T> ext = Views.extendValue(imgs[level], zero);
				rras[level] = new RealRandomAccessible[] {
						new RealTransformRealRandomAccessible<>(
								Views.interpolate(ext, nearestNeighbor),
								positionField.getTransform(level, incrementalTransform)),
						new RealTransformRealRandomAccessible<>(
								Views.interpolate(ext, nLinear),
								positionField.getTransform(level, incrementalTransform)),
				};

				final double scale = 1.0 / (1 << level);
				final long[] min = Grid.floorScaled(positionField.getBoundsMin(), scale);
				final long[] max = Grid.ceilScaled(positionField.getBoundsMax(), scale);
				rais[level] = Views.interval(
						new RealTransformRandomAccessible<>(
								Views.interpolate(ext, nearestNeighbor),
								positionField.getTransform(level, incrementalTransform)),
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
