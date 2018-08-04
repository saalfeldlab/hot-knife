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
import static net.imglib2.type.PrimitiveType.FLOAT;

import org.janelia.saalfeldlab.hotknife.ops.UnaryComputerOpCellLoader;

import net.imagej.ops.Op;
import net.imagej.ops.OpService;
import net.imagej.ops.special.computer.UnaryComputerOp;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.LoadedCellCacheLoader;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.basictypeaccess.ArrayDataAccessFactory;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileByteArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileFloatArray;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Lazy {

	private Lazy() {}

	public static <O extends Op> RandomAccessibleInterval<UnsignedByteType> processVolatileUnsignedByte(
			final RandomAccessibleInterval<UnsignedByteType> source,
			final int[] blockSize,
			final OpService opService,
			final Class<O> opClass,
			final Object... opArgs) {

		final long[] dimensions = Intervals.dimensionsAsLongArray(source);
		final CellGrid grid = new CellGrid(dimensions, blockSize);
		final UnaryComputerOpCellLoader<UnsignedByteType, UnsignedByteType, RandomAccessible<UnsignedByteType>> loader = new UnaryComputerOpCellLoader<UnsignedByteType, UnsignedByteType, RandomAccessible<UnsignedByteType>>(
				source,
				UnsignedByteType::new,
				opService,
				opClass,
				opArgs);

		final UnsignedByteType type = new UnsignedByteType();
		final Cache<Long, Cell<VolatileByteArray>> cache = new SoftRefLoaderCache<Long, Cell<VolatileByteArray>>()
				.withLoader(LoadedCellCacheLoader.get(grid, loader, type, AccessFlags.setOf(VOLATILE)));
		final CachedCellImg<UnsignedByteType, VolatileByteArray> img = new CachedCellImg<>(grid, type, cache, ArrayDataAccessFactory.get(BYTE, AccessFlags.setOf(VOLATILE)));
		return img;
	}

	public static <O extends Op> RandomAccessibleInterval<UnsignedByteType> processUnsignedByte(
			final RandomAccessibleInterval<UnsignedByteType> source,
			final int[] blockSize,
			final OpService opService,
			final Class<O> opClass,
			final Object... opArgs) {

		final long[] dimensions = Intervals.dimensionsAsLongArray(source);
		final CellGrid grid = new CellGrid(dimensions, blockSize);
		final UnaryComputerOpCellLoader<UnsignedByteType, UnsignedByteType, RandomAccessible<UnsignedByteType>> loader = new UnaryComputerOpCellLoader<UnsignedByteType, UnsignedByteType, RandomAccessible<UnsignedByteType>>(
				source,
				UnsignedByteType::new,
				opService,
				opClass,
				opArgs);

		final UnsignedByteType type = new UnsignedByteType();
		final Cache<Long, Cell<ByteArray>> cache = new SoftRefLoaderCache<Long, Cell<ByteArray>>()
				.withLoader(LoadedCellCacheLoader.get(grid, loader, type, AccessFlags.setOf()));
		final CachedCellImg<UnsignedByteType, ByteArray> img = new CachedCellImg<>(grid, type, cache, ArrayDataAccessFactory.get(BYTE, AccessFlags.setOf()));
		return img;
	}

	public static <O extends Op> RandomAccessibleInterval<FloatType> processVolatileFloat(
			final RandomAccessibleInterval<FloatType> source,
			final int[] blockSize,
			final OpService opService,
			final Class<O> opClass,
			final Object... opArgs) {

		final long[] dimensions = Intervals.dimensionsAsLongArray(source);
		final CellGrid grid = new CellGrid(dimensions, blockSize);
		final UnaryComputerOpCellLoader<FloatType, FloatType, RandomAccessible<FloatType>> loader = new UnaryComputerOpCellLoader<FloatType, FloatType, RandomAccessible<FloatType>>(
				source,
				FloatType::new,
				opService,
				opClass,
				opArgs);

		final FloatType type = new FloatType();
		final Cache<Long, Cell<VolatileFloatArray>> cache = new SoftRefLoaderCache<Long, Cell<VolatileFloatArray>>()
				.withLoader(LoadedCellCacheLoader.get(grid, loader, type, AccessFlags.setOf(VOLATILE)));
		final CachedCellImg<FloatType, VolatileFloatArray> img = new CachedCellImg<>(grid, type, cache, ArrayDataAccessFactory.get(FLOAT, AccessFlags.setOf(VOLATILE)));
		return img;
	}

	public static <O extends Op> RandomAccessibleInterval<FloatType> processFloat(
			final RandomAccessible<FloatType> source,
			final Interval sourceInterval,
			final int[] blockSize,
			final OpService opService,
			final Class<O> opClass,
			final Object... opArgs) {

		final long[] dimensions = Intervals.dimensionsAsLongArray(sourceInterval);
		final CellGrid grid = new CellGrid(dimensions, blockSize);
		final UnaryComputerOpCellLoader<FloatType, FloatType, RandomAccessible<FloatType>> loader = new UnaryComputerOpCellLoader<FloatType, FloatType, RandomAccessible<FloatType>>(
				source,
				FloatType::new,
				opService,
				opClass,
				opArgs);

		final FloatType type = new FloatType();
		final Cache<Long, Cell<FloatArray>> cache = new SoftRefLoaderCache<Long, Cell<FloatArray>>()
				.withLoader(LoadedCellCacheLoader.get(grid, loader, type, AccessFlags.setOf()));
		final CachedCellImg<FloatType, FloatArray> img = new CachedCellImg<>(grid, type, cache, ArrayDataAccessFactory.get(FLOAT, AccessFlags.setOf()));
		return img;
	}

	public static RandomAccessibleInterval<FloatType> processFloat(
			final RandomAccessible<FloatType> source,
			final Interval sourceInterval,
			final int[] blockSize,
			final UnaryComputerOp<RandomAccessible<FloatType>, RandomAccessibleInterval<FloatType>> op) {

		final long[] dimensions = Intervals.dimensionsAsLongArray(sourceInterval);
		final CellGrid grid = new CellGrid(dimensions, blockSize);
		final UnaryComputerOpCellLoader<FloatType, FloatType, RandomAccessible<FloatType>> loader =
				new UnaryComputerOpCellLoader<FloatType, FloatType, RandomAccessible<FloatType>>(
					source,
					op);

		final FloatType type = new FloatType();
		final Cache<Long, Cell<FloatArray>> cache = new SoftRefLoaderCache<Long, Cell<FloatArray>>()
				.withLoader(LoadedCellCacheLoader.get(grid, loader, type, AccessFlags.setOf()));
		final CachedCellImg<FloatType, FloatArray> img = new CachedCellImg<>(grid, type, cache, ArrayDataAccessFactory.get(FLOAT, AccessFlags.setOf()));
		return img;
	}
}
