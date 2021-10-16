package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import java.util.Arrays;
import mpicbg.spim.data.sequence.DefaultVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
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
public class TransformedSurfacePyramid<T extends NativeType<T> & NumericType<T>, V extends Volatile<T> & NativeType<V> & NumericType<V>>
		implements SurfacePyramid<T, V> {

	private final SurfacePyramid<T, V> pyramid;
	private final RandomAccessibleInterval<T>[] imgs;
	private final RandomAccessibleInterval<V>[] vimgs;
	private final SourceAndConverter<T> sourceAndConverter;

	public TransformedSurfacePyramid(
			final SurfacePyramid<T, V> pyramid,
			final RealTransform incrementalTransform) {
		this.pyramid = pyramid;
		final int numScales = pyramid.getNumMipmapLevels();
		final RandomAccessibleInterval<T>[] imgs = new RandomAccessibleInterval[numScales];
		final RandomAccessibleInterval<V>[] vimgs = new RandomAccessibleInterval[numScales];
		for (int level = 0; level < numScales; ++level) {
			imgs[level] = pyramid.getImg(level);
			vimgs[level] = pyramid.getVolatileImg(level);
		}

		final T type = pyramid.getType();
		final V volatileType = pyramid.getVolatileType();

		final double[] boundsMin = pyramid.getBoundsMin();
		final TransformedSurfaceSource<V> vs = new TransformedSurfaceSource<>(
				volatileType, vimgs, boundsMin,
				incrementalTransform, "transformed");
		final SourceAndConverter<V> vsoc = new SourceAndConverter<>(vs, createConverterToARGB(volatileType));
		final TransformedSurfaceSource<T> s = new TransformedSurfaceSource<>(
				type, imgs, boundsMin,
				incrementalTransform, "transformed");
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
		return pyramid.getBoundsMin();
	}


	private static class TransformedSurfaceSource<T extends NumericType<T>> implements Source<T> {

		private final T type;
		private double[] boundsMin;
		private final String name;
		final RandomAccessibleInterval<T>[] rais;
		private final RealRandomAccessible<T>[][] rras;
		private final DefaultVoxelDimensions voxelDimensions = new DefaultVoxelDimensions(3);

		TransformedSurfaceSource(
				final T type,
				final RandomAccessibleInterval<T>[] imgs,
				final double[] boundsMin,
				final RealTransform incrementalTransform,
				final String name) {
			this.type = type;
			this.boundsMin = boundsMin;
			this.name = name;

			rais = new RandomAccessibleInterval[imgs.length];
			rras = new RealRandomAccessible[imgs.length][];

			final T zero = type.createVariable();
			zero.setZero();
			final InterpolatorFactory<T, RandomAccessible<T>> nearestNeighbor = new NearestNeighborInterpolatorFactory<>();
			final InterpolatorFactory<T, RandomAccessible<T>> nLinear = new ClampingNLinearInterpolatorFactory<>();
			for (int level = 0; level < imgs.length; level++) {
				final RandomAccessible<T> ext = Views.extendValue(imgs[level], zero);
				rras[level] = new RealRandomAccessible[] {
						new RealTransformRealRandomAccessible<>(
								Views.interpolate(ext, nearestNeighbor),
								new ScaledTransform(incrementalTransform, boundsMin, level)),
						new RealTransformRealRandomAccessible<>(
								Views.interpolate(ext, nLinear),
								new ScaledTransform(incrementalTransform, boundsMin, level))
				};

				final long[] min = imgs[level].minAsLongArray(); // should be == {0, 0}
				final long[] max = imgs[level].maxAsLongArray();
				rais[level] = Views.interval(
						new RealTransformRandomAccessible<>(
								Views.interpolate(ext, nearestNeighbor),
								new ScaledTransform(incrementalTransform, boundsMin, level)),
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
			return rais.length;
		}
	}


	private static class ScaledTransform implements RealTransform {

		private double[] boundsMin;

		// scale level that this Transform applies to
		private int level;

		// manual transform defined on scale level 0
		private final RealTransform incrementalTransform;

		// 1 << level
		private final double levelScale;

		// 1 / (1 << level)
		private final double invLevelScale;

		// floorScaled(boundsMin, 1 / (1 << level)) * 1 << level
		private final double[] offset;

		ScaledTransform(
				final RealTransform incrementalTransform,
				final double[] boundsMin,
				final int level
		) {
			this.incrementalTransform = incrementalTransform.copy();
			this.boundsMin = boundsMin;
			levelScale = 1 << level;
			invLevelScale =  1.0 / levelScale;
			final long[] o = Grid.floorScaled(boundsMin, invLevelScale);
			offset = new double[] {o[0] << level, o[1] << level};
			this.level = level;
		}

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
			apply(RealPoint.wrap(source), RealPoint.wrap(target));
		}

		private final double[] tmp1 = new double[2];
		private final double[] tmp2 = new double[2];
		private final RealPoint tmp1p = RealPoint.wrap(tmp1);
		private final RealPoint tmp2p = RealPoint.wrap(tmp2);

		@Override
		public void apply(final RealLocalizable source, final RealPositionable target) {
			final double x = source.getDoublePosition(0);
			final double y = source.getDoublePosition(1);
			// scale by (1<<level)
			tmp1[0] = x * levelScale + offset[0];
			tmp1[1] = y * levelScale + offset[1];
			// apply manualTransform
			incrementalTransform.apply(tmp1p, tmp2p);
			// scale by 1 / (1 << level)
			target.setPosition((tmp2[0] - offset[0]) * invLevelScale, 0);
			target.setPosition((tmp2[1] - offset[1]) * invLevelScale, 1);
		}

		@Override
		public RealTransform copy() {
			return new ScaledTransform(incrementalTransform, boundsMin, level);
		}
	}
}
