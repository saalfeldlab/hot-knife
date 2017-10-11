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

import static net.imglib2.cache.img.AccessFlags.VOLATILE;
import static net.imglib2.cache.img.PrimitiveType.BYTE;
import static net.imglib2.cache.img.PrimitiveType.DOUBLE;
import static net.imglib2.cache.img.PrimitiveType.FLOAT;
import static net.imglib2.cache.img.PrimitiveType.INT;
import static net.imglib2.cache.img.PrimitiveType.LONG;
import static net.imglib2.cache.img.PrimitiveType.SHORT;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.imglib2.RandomAccessibleLoader;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.Source;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.ArrayDataAccessFactory;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.LoadedCellCacheLoader;
import net.imglib2.cache.img.RandomAccessibleCacheLoader;
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.img.basictypeaccess.array.ArrayDataAccess;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.NativeType;
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

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Show {

	private Show() {}

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
//		options.numRenderingThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
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

		final N5Reader n5Reader = N5.openFSReader(n5Path);
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

		final RandomAccessibleCacheLoader<T, A, CA> loader = RandomAccessibleCacheLoader.get(grid, Views.zeroMin(source));
		return new ReadOnlyCachedCellImgFactory().createWithCacheLoader(dimensions, source.randomAccess().get(), loader);
	}


	@SuppressWarnings( { "unchecked", "rawtypes" } )
	public static final <T extends NativeType<T>> RandomAccessibleInterval<T> wrapAsVolatileCachedCellImg(
			final RandomAccessibleInterval<T> source,
			final int[] blockSize) throws IOException {

		final long[] dimensions = Intervals.dimensionsAsLongArray(source);
		final CellGrid grid = new CellGrid(dimensions, blockSize);

		final RandomAccessibleLoader<T> loader = new RandomAccessibleLoader<T>(Views.zeroMin(source));

		final T type = Util.getTypeFromInterval(source);

		final CachedCellImg<T, ?> img;
		final Cache<Long, Cell<?>> cache =
				(Cache)new SoftRefLoaderCache().withLoader(LoadedCellCacheLoader.get(grid, loader, type, VOLATILE));

		if (GenericByteType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(BYTE, VOLATILE));
		} else if (GenericShortType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(SHORT, VOLATILE));
		} else if (GenericIntType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(INT, VOLATILE));
		} else if (GenericLongType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(LONG, VOLATILE));
		} else if (FloatType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(FLOAT, VOLATILE));
		} else if (DoubleType.class.isInstance(type)) {
			img = new CachedCellImg(grid, type, cache, ArrayDataAccessFactory.get(DOUBLE, VOLATILE));
		} else {
			img = null;
		}

		return img;
	}
}
