package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.NumericType;

import static bdv.BigDataViewer.createConverterToARGB;

public class DelegatingSourceAndConverter<T extends NumericType<T>, V extends Volatile<T> & NumericType<V>> {

	private final SourceAndConverter<T> soc;
	private final DelegatingSource<V> vsource;
	private final DelegatingSource<T> source;

	public DelegatingSourceAndConverter(T type, V volatileType, String name) {
		vsource = new DelegatingSource<>(name);
		source = new DelegatingSource<>(name);
		final SourceAndConverter<V> vsoc = new SourceAndConverter<>(vsource, createConverterToARGB(volatileType));
		soc = new SourceAndConverter<>(source, createConverterToARGB(type), vsoc);
	}

	public void setDelegate(SourceAndConverter<T> soc) {
		source.setDelegate(soc.getSpimSource());
		vsource.setDelegate((Source<V>) soc.asVolatile().getSpimSource());
	}

	public void setName(String name) {
		source.setName(name);
		vsource.setName(name);
	}

	public SourceAndConverter<T> get() {
		return soc;
	}


	static class DelegatingSource<T> implements Source<T> {

		private Source<T> delegate;

		private String name;

		DelegatingSource(final String name) {
			this.name = name;
		}

		public void setDelegate(Source<T> source) {
			delegate = source;
		}

		public void setName(String name) {
			this.name = name;
		}

		@Override
		public boolean isPresent(final int t) {
			return delegate.isPresent(t);
		}

		@Override
		public RandomAccessibleInterval<T> getSource(final int t, final int level) {
			return delegate.getSource(t, level);
		}

		@Override
		public boolean doBoundingBoxCulling() {
			return delegate.doBoundingBoxCulling();
		}

		@Override
		public RealRandomAccessible<T> getInterpolatedSource(final int t, final int level, final Interpolation method) {
			return delegate.getInterpolatedSource(t, level, method);
		}

		@Override
		public void getSourceTransform(final int t, final int level, final AffineTransform3D transform) {
			delegate.getSourceTransform(t, level, transform);
		}

		@Override
		public T getType() {
			return delegate.getType();
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public VoxelDimensions getVoxelDimensions() {
			return delegate.getVoxelDimensions();
		}

		@Override
		public int getNumMipmapLevels() {
			return delegate.getNumMipmapLevels();
		}
	}
}
