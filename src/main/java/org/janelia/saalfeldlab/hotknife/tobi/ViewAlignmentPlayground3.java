package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import java.io.IOException;
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
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import static bdv.BigDataViewer.createConverterToARGB;

public class ViewAlignmentPlayground3 {

	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader(n5Path);

		final String transformGroup = passGroup + "/" + "flat.Sec33.bot.face";
		final String faceGroup = "/flat/Sec33/bot/face";

		final SurfacePyramid<?, ?> pyramid = new SurfacePyramid<>(n5, faceGroup);
		BdvFunctions.show(pyramid.getSourceAndConverter(), Bdv.options().is2D());

		final PositionField positionField = new PositionField(n5, transformGroup);

		final TransformedPyramid<?, ?> tpyramid = new TransformedPyramid<>(pyramid, positionField);
		BdvFunctions.show(tpyramid.getSourceAndConverter(), Bdv.options().is2D());
	}


	public static class TransformedPyramid<T extends NativeType<T> & NumericType<T>, V extends Volatile<T> & NativeType<V> & NumericType<V>> {

		private final SourceAndConverter<T> sourceAndConverter;

		public TransformedPyramid(SurfacePyramid<T, V> pyramid, PositionField positionField) {
			final int numScales = pyramid.getNumMipmapLevels();
			final RandomAccessibleInterval<T>[] imgs = new RandomAccessibleInterval[numScales];
			final RandomAccessibleInterval<V>[] vimgs = new RandomAccessibleInterval[numScales];

			for (int level = 0; level < numScales; ++level) {
				imgs[level] = pyramid.getImg(level);
				vimgs[level] = pyramid.getVolatileImg(level);
			}

			final T type = pyramid.getType();
			final V volatileType = pyramid.getVolatileType();

			final Source<V> vs = new TransformedSurfaceSource<>(volatileType, vimgs, positionField,"transformed");
			final SourceAndConverter<V> vsoc = new SourceAndConverter<>(vs, createConverterToARGB(volatileType));
			final Source<T> s = new TransformedSurfaceSource<>(type, imgs, positionField, "transformed");
			sourceAndConverter = new SourceAndConverter<>(s, createConverterToARGB(type), vsoc);
		}

		public SourceAndConverter<T> getSourceAndConverter() {
			return sourceAndConverter;
		}
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
				final String name) {
			this.type = type;
			this.name = name;

			rais = new RandomAccessibleInterval[imgs.length];
			rras = new RealRandomAccessible[imgs.length][];

			final T zero = type.createVariable();
			zero.setZero();
//			final InterpolatorFactory<T, RandomAccessible<T>> nLinear = new ClampingNLinearInterpolatorFactory<>();
//			for (int i = 0; i < imgs.length; i++) {
//				rras[i] = new RealTransformRealRandomAccessible<>(
//						Views.interpolate(Views.extendValue(imgs[i], zero), nLinear),
//						positionField.getTransform(i));
//				rais[i] = imgs[i];
//			}

			final InterpolatorFactory<T, RandomAccessible<T>> nearestNeighbor = new NearestNeighborInterpolatorFactory<>();
			final InterpolatorFactory<T, RandomAccessible<T>> nLinear = new ClampingNLinearInterpolatorFactory<>();
			for (int i = 0; i < imgs.length; i++) {
				final RandomAccessible<T> ext = Views.extendValue(imgs[i], zero);
				rras[i] = new RealRandomAccessible[] {
						new RealTransformRealRandomAccessible<>(
								Views.interpolate(ext, nearestNeighbor),
								positionField.getTransform(i)),
						new RealTransformRealRandomAccessible<>(
								Views.interpolate(ext, nLinear),
								positionField.getTransform(i)),
				};
				rais[i] = imgs[i];
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

	public static class PositionField {

		final RandomAccessibleInterval<DoubleType> positionField;
		final RealTransform transform;
		final long[] translation;
		final double transformScale;

		public PositionField(final N5Reader n5, final String datasetName)
				throws IOException {
			transformScale = n5.getAttribute(datasetName, "scale", double.class);
			final double[] boundsMin = n5.getAttribute(datasetName, "boundsMin", double[].class);
			translation = Grid.floorScaled(boundsMin, transformScale);

			positionField = N5Utils.open(n5, datasetName);
			transform = Transform.createPositionFieldTransform(positionField);
		}

		public RealTransform getTransform(final int level) {
			return new MyTransform(transform, transformScale * (1 << level), translation[0], translation[1]);
		}

		static class MyTransform implements RealTransform {

			private final RealTransform pft;
			private final double scale;
			private final double offsetX;
			private final double offsetY;

			MyTransform(final RealTransform positionFieldTransform,
					final double scale,
					final double offsetX,
					final double offsetY) {
				pft = positionFieldTransform.copy();
				this.scale = scale;
				this.offsetX = offsetX;
				this.offsetY = offsetY;
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
				apply(RealPoint.wrap(source), RealPoint.wrap(target)); // TODO
			}

			private final double[] pftSource = new double[2];
			private final RealPoint pftSourcePoint = RealPoint.wrap(pftSource);

			private final double[] pftTarget = new double[2];
			private final RealPoint pftTargetPoint = RealPoint.wrap(pftTarget);

			@Override
			public void apply(final RealLocalizable source, final RealPositionable target) {
				double x = source.getDoublePosition(0);
				double y = source.getDoublePosition(1);
				pftSource[ 0 ] = scale * x - offsetX;
				pftSource[ 1 ] = scale * y - offsetY;
				pft.apply(pftSourcePoint, pftTargetPoint);
				target.setPosition(pftTarget[0] / scale, 0);
				target.setPosition(pftTarget[1] / scale, 1);
			}

			@Override
			public RealTransform copy() {
				return new MyTransform(pft, scale, offsetX, offsetY);
			}
		}
	}
}
