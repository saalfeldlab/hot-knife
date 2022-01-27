/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.hotknife.util;


import static net.imglib2.img.basictypeaccess.AccessFlags.VOLATILE;
import static net.imglib2.type.PrimitiveType.BYTE;
import static net.imglib2.type.PrimitiveType.DOUBLE;
import static net.imglib2.type.PrimitiveType.FLOAT;
import static net.imglib2.type.PrimitiveType.INT;
import static net.imglib2.type.PrimitiveType.LONG;
import static net.imglib2.type.PrimitiveType.SHORT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.janelia.saalfeldlab.hotknife.util.Transform.TransformedSource;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.imglib2.RandomAccessibleLoader;

import bdv.util.AbstractSource;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.LoadedCellCacheLoader;
import net.imglib2.cache.img.RandomAccessibleCacheLoader;
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.basictypeaccess.ArrayDataAccessFactory;
import net.imglib2.img.basictypeaccess.array.ArrayDataAccess;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.position.RealPositionRealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.integer.GenericByteType;
import net.imglib2.type.numeric.integer.GenericIntType;
import net.imglib2.type.numeric.integer.GenericLongType;
import net.imglib2.type.numeric.integer.GenericShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.imglib2.view.composite.RealComposite;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Show {

	private Show() {}

	public static class VolatileMipmapSource<T extends NumericType<T>, V extends Volatile<T> & NumericType<V>> extends AbstractSource<V> {

		private final Source<T> source;

		private SharedQueue queue;

		public VolatileMipmapSource(
				final Source<T> source,
				final V type,
				final SharedQueue queue) {

			super(type, source.getName());
			this.source = source;
			this.queue = queue;
		}

		public VolatileMipmapSource(
				final Source<T> source,
				final Supplier<V> typeSupplier,
				final SharedQueue queue) {

			this(source, typeSupplier.get(), queue);
		}

		@Override
		public RandomAccessibleInterval<V> getSource(final int t, final int level) {

			return VolatileViews.wrapAsVolatile(
					source.getSource(t, level),
					queue,
					new CacheHints(LoadingStrategy.VOLATILE, level, true));
		}

		@Override
		public synchronized void getSourceTransform(final int t, final int level, final AffineTransform3D transform) {

			source.getSourceTransform(t, level, transform);
		}

		@Override
		public VoxelDimensions getVoxelDimensions() {

			return source.getVoxelDimensions();
		}

		@Override
		public int getNumMipmapLevels() {

			return source.getNumMipmapLevels();
		}
	}

	/**
	 * Quickly visualize the slab-face series as transformed by a corresponding
	 * list of target to source transforms.
	 * @throws IOException
	 */
	public static Bdv transformedTopBotStack(
			final String n5Path,
			final List<String> datasetNames,
			final int scaleIndex,
			final List<? extends RealTransform> slabTransforms,
			final Interval targetInterval,
			final Bdv bdv) throws IOException {

		final ArrayList<RealTransform> transforms = new ArrayList<>();
		slabTransforms.forEach(t -> {
			transforms.add(t);
			transforms.add(t);
		});

		return transformedStack(n5Path, datasetNames, scaleIndex, transforms, targetInterval, bdv);
	}

	/**
	 * Quickly visualize the slab-face series as transformed by a corresponding
	 * list of target to source transforms.
	 * @throws IOException
	 */
	public static <T extends NumericType<T>> BdvStackSource<T> transformedStack(
			final RandomAccessibleInterval<T> stack,
			final Bdv bdv) throws IOException {

		final BdvOptions options = bdv == null ? Bdv.options() : Bdv.options().addTo(bdv);
		options.numRenderingThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
		final BdvStackSource<T> stackSource = BdvFunctions.show(stack, "transformed", options);
		stackSource.setDisplayRange(0, 255);
		return stackSource;
	}

	/**
	 * Quickly visualize the slab-face series as transformed by a corresponding
	 * list of target to source transforms.
	 * @throws IOException
	 */
	public static BdvStackSource<?> mipmapSource(
			final Source<?> source,
			final Bdv bdv) throws IOException {

		return mipmapSource(source, bdv, null);
	}


	/**
	 * Quickly visualize the slab-face series as transformed by a corresponding
	 * list of target to source transforms.
	 * @throws IOException
	 */
	public static BdvStackSource<?> mipmapSource(
			final Source<?> source,
			final Bdv bdv,
			BdvOptions options) throws IOException {

		if (options == null)
			options = bdv == null ? Bdv.options() : Bdv.options().addTo(bdv);
		final BdvStackSource<?> stackSource = BdvFunctions.show(source, options);
		stackSource.setDisplayRange(0, 255);
		return stackSource;
	}

	/**
	 * Quickly visualize the slab-face series as transformed by a corresponding
	 * list of target to source transforms.
	 * @throws IOException
	 */
	public static Bdv transformedStack(
			final String n5Path,
			final List<String> datasetNames,
			final int scaleIndex,
			final List<? extends RealTransform> transforms,
			final Interval targetInterval,
			final Bdv bdv) throws IOException {

		final RandomAccessibleInterval<FloatType> stack = Transform.createTransformedStack(n5Path, datasetNames, scaleIndex, transforms, targetInterval);
		return transformedStack(stack, bdv);
	}

	/**
	 * Quickly visualize a source as transformed by a target to source transform.
	 * @throws IOException
	 */
	public static Bdv transformed(
			final String n5Path,
			final String datasetName,
			final int scaleIndex,
			final RealTransform transforms,
			final Interval targetInterval,
			final Bdv bdv) throws IOException {

		final N5Reader n5Reader = new N5FSReader(n5Path);
		final RandomAccessibleInterval<FloatType> source = N5Utils.open(n5Reader, datasetName + "/s" + scaleIndex);
		final RandomAccessibleInterval<FloatType> transformedInterval = Transform.createTransformedInterval(
				source,
				targetInterval,
				Transform.createScaledRealTransform(transforms, scaleIndex),
				new FloatType(255));

		final BdvOptions options = bdv == null ? Bdv.options() : Bdv.options().addTo(bdv);
		final BdvStackSource<?> stackSource = BdvFunctions.show(transformedInterval, "transformed", options);
		stackSource.setDisplayRange(0, 255);
		return stackSource;
	}


	public static final <T extends NativeType<T>, A extends ArrayDataAccess<A>, CA extends ArrayDataAccess<CA>> CachedCellImg<T, CA> wrapAsCachedCellImg(
			final RandomAccessibleInterval<T> source,
			final int[] blockSize) throws IOException {

		final long[] dimensions = Intervals.dimensionsAsLongArray(source);
		final CellGrid grid = new CellGrid(dimensions, blockSize);

		final RandomAccessibleCacheLoader<T, A, CA> loader = RandomAccessibleCacheLoader.get(grid, Views.zeroMin(source), AccessFlags.setOf());
		return new ReadOnlyCachedCellImgFactory().createWithCacheLoader(dimensions, source.randomAccess().get(), loader);
	}


	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public static final <T extends NativeType<T>> RandomAccessibleInterval<T> wrapAsVolatileCachedCellImg(
			final RandomAccessibleInterval<T> source,
			final int[] blockSize) {

		final long[] dimensions = Intervals.dimensionsAsLongArray(source);
		final CellGrid grid = new CellGrid(dimensions, blockSize);

		final RandomAccessibleLoader<T> loader = new RandomAccessibleLoader<T>(Views.zeroMin(source));

		final T type = Util.getTypeFromInterval(source);

		final CachedCellImg<T, ?> img;
		final Cache<Long, Cell<?>> cache =
				new SoftRefLoaderCache().withLoader(LoadedCellCacheLoader.get(grid, loader, type, AccessFlags.setOf(VOLATILE)));

		if (GenericByteType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(BYTE, AccessFlags.setOf(VOLATILE)));
		} else if (GenericShortType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(SHORT, AccessFlags.setOf(VOLATILE)));
		} else if (GenericIntType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(INT, AccessFlags.setOf(VOLATILE)));
		} else if (GenericLongType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(LONG, AccessFlags.setOf(VOLATILE)));
		} else if (FloatType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(FLOAT, AccessFlags.setOf(VOLATILE)));
		} else if (DoubleType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(DOUBLE, AccessFlags.setOf(VOLATILE)));
		} else {
			img = null;
		}

		return img;
	}

	public static final int algebraic2ARGB(final double xs, final double ys, final double lambda) {

		final double a = Math.sqrt(xs * xs + ys * ys);
		if (a == 0.0)
			return 0;

		double o = (Math.atan2(xs / a, ys / a) + Math.PI) / Math.PI * 3;

		final double r, g, b;

		if (o < 3) r = Math.min(1.0, Math.max(0.0, 2.0 - o)) * a;
		else r = Math.min(1.0, Math.max(0.0, o - 4.0)) * a;

		o += 2;
		if (o >= 6) o -= 6;

		if (o < 3) g = Math.min(1.0, Math.max(0.0, 2.0 - o)) * a;
		else g = Math.min(1.0, Math.max(0.0, o - 4.0)) * a;

		o += 2;
		if (o >= 6) o -= 6;

		if (o < 3) b = Math.min(1.0, Math.max(0.0, 2.0 - o)) * a;
		else b = Math.min(1.0, Math.max(0.0, o - 4.0)) * a;

		return (((
				(int)(Math.pow(r, lambda) * 255) << 8) |
				(int)(Math.pow(g, lambda) * 255)) << 8) |
				(int)(Math.pow(b, lambda) * 255);
	}

	/**
	 * Compare two {@link RealTransform RealTransforms} over an
	 * {@link Interval}.  Evaluation into a target {@link Type T} is calculated
	 * by a {@link Converter} whose input is a {@link RealComposite} of
	 * {@link DoubleType} that contains the interleaved transformed positions
	 * at the raster location, i.e. (t<sub>1</sub>(x)<sub>0</sub>,
	 * t<sub>2</sub>(x)<sub>0</sub>, t<sub>1</sub>(x)<sub>1</sub>,
	 * t<sub>2</sub>(x)<sub>1</sub>, t<sub>1</sub>(x)<sub>2</sub>,
	 * t<sub>2</sub>(x)<sub>2</sub>, ...)
	 *
	 * @param transform1
	 * @param transform2
	 * @param interval
	 * @param converter
	 * @param typeSupplier
	 * @return
	 */
	public static final <T extends Type<T>> RandomAccessibleInterval<T> compareTransforms(
			final RealTransform transform1,
			final RealTransform transform2,
			final Interval interval,
			final Converter<? super RealComposite<DoubleType>, ? super T> converter,
			final Supplier<T> typeSupplier) {

		final int n = interval.numDimensions();

		final ArrayList<RandomAccessibleInterval<DoubleType>> axes = new ArrayList<>();
		for (int d = 0; d < n; ++d) {

			final RealPositionRealRandomAccessible realPositions = new RealPositionRealRandomAccessible(n, d);
			final RealTransformRealRandomAccessible<DoubleType, ?> transformedRealPositions1 = new RealTransformRealRandomAccessible<>(realPositions, transform1);
			final RealTransformRealRandomAccessible<DoubleType, ?> transformedRealPositions2 = new RealTransformRealRandomAccessible<>(realPositions, transform2);

			axes.add(Views.interval(Views.raster(transformedRealPositions1), interval));
			axes.add(Views.interval(Views.raster(transformedRealPositions2), interval));
		}

		return Converters.convert(
				Views.collapseReal(Views.stack(axes)),
				converter,
				typeSupplier.get());
	}

	public static final RandomAccessibleInterval<ARGBType> compare2DTransforms(
			final RealTransform transform1,
			final RealTransform transform2,
			final Interval interval,
			final double max,
			final double lambda) {

		final double maxFactor = 1.0 / max;
		return compareTransforms(
				transform1,
				transform2,
				interval,
				(a, b) -> {
					final double dx = (a.get(0).get() - a.get(1).get()) * maxFactor;
					final double dy = (a.get(2).get() - a.get(3).get()) * maxFactor;
					b.set(algebraic2ARGB(dx, dy, lambda));
				},
				ARGBType::new);
	}

	public static final RandomAccessibleInterval<ARGBType> compareTransformStack(
			final List<? extends RealTransform> transforms1,
			final List<? extends RealTransform> transforms2,
			final Interval targetInterval,
			final double max,
			final double lambda,
			final int scaleIndex) throws IOException {

		final ArrayList<RandomAccessibleInterval<ARGBType>> comparisonIntervals = new ArrayList<>();
		for (int i = 0; i < transforms1.size(); ++i) {

			comparisonIntervals.add(
					compare2DTransforms(
							Transform.createScaledRealTransform(transforms1.get(i), scaleIndex),
							Transform.createScaledRealTransform(transforms2.get(i), scaleIndex),
							targetInterval,
							max / (1 << scaleIndex),
							lambda));
		}

		return Views.stack(comparisonIntervals);
	}


	public static final <T extends NumericType<T> & NativeType<T>> RandomAccessibleIntervalMipmapSource<T> createTransformedMipmapSource(
			final RealTransform transformToSource,
			final RandomAccessibleInterval<T>[] rawMipmaps,
			final double[][] scales,
			final VoxelDimensions voxelDimensions,
			final String name) {

		final int numScales = rawMipmaps.length;

		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<T>[] mipmaps = new RandomAccessibleInterval[numScales];

		final T type = Util.getTypeFromInterval(rawMipmaps[0]).createVariable();

		for (int s = 0; s < numScales; ++s) {

			final double[] scale = scales[s];
			final double[] shift = new double[scale.length]; // 3
			Arrays.setAll(shift, i -> 0.5 * (scale[i] - 1));

			final RealTransformSequence transformSequence = new RealTransformSequence();
			final Scale3D scale3D = new Scale3D(scale);
			final Translation3D shift3D = new Translation3D(shift);
			transformSequence.add(scale3D);
			transformSequence.add(shift3D);
			transformSequence.add(transformToSource);
			transformSequence.add(shift3D.inverse());
			transformSequence.add(scale3D.inverse());

			final RandomAccessibleInterval<T> transformedSource =
					Transform.createTransformedInterval(
							rawMipmaps[s],
							rawMipmaps[0],
							transformSequence,
							type.createVariable());

			final RandomAccessibleInterval<T> cachedSource = Show.wrapAsVolatileCachedCellImg(transformedSource, new int[]{64, 64, 64});

			mipmaps[s] = cachedSource;
		}

		return new RandomAccessibleIntervalMipmapSource<>(
						mipmaps,
						type.createVariable(),
						scales,
						voxelDimensions,
						name);
	}

	public static final <T extends NumericType<T> & NativeType<T>, V extends Volatile<T> & NumericType<V>> TransformedSource<V> createTransformedMipmapSliceSource(
			final RealTransform transformToSource,
			final RandomAccessibleInterval<T>[] rawMipmaps,
			final double[][] scales,
			final VoxelDimensions voxelDimensions,
			final String name,
			final long offset,
			final SharedQueue queue) {

		final int numScales = rawMipmaps.length;

		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<V>[] mipmaps = new RandomAccessibleInterval[numScales];
		//final Translation3D offset3D = new Translation3D(0, 0, -offset);

		for (int s = 0; s < numScales; ++s) {
			final int n = rawMipmaps[ s ].numDimensions();
			final Interval interval = Intervals.expand( new FinalInterval( rawMipmaps[ s ] ), rawMipmaps[ s ].dimension( n - 1), n - 1 );

			//System.out.println( "expanding interval for scale " + s + ": " + Util.printInterval( rawMipmaps[ s ] ) + ">>>" + Util.printInterval( interval ) );

			mipmaps[s] = VolatileViews.wrapAsVolatile( rawMipmaps[s], queue);
			mipmaps[s] = Views.interval( Views.extendZero( mipmaps[ s ] ), interval );
		}

		// TODO: in the future one can set doBoundingBoxCulling to false (https://github.com/bigdataviewer/bigdataviewer-vistools/pull/51/commits/058b1cc9c6373c52e05c121a9f0a8e3a5b8b35c5)
		final RandomAccessibleIntervalMipmapSource<V> mipmapSource = new RandomAccessibleIntervalMipmapSource<>(
				mipmaps,
				Util.getTypeFromInterval(mipmaps[0]).createVariable(),
				scales,
				voxelDimensions,
				name);

		final RealTransformSequence transformSequence = new RealTransformSequence();
		final Translation3D shift = new Translation3D(0, 0, offset);
		transformSequence.add(shift);
		transformSequence.add(transformToSource);

		return new TransformedSource<>(
				mipmapSource,
				transformSequence,
				"transformed");

	}
}
