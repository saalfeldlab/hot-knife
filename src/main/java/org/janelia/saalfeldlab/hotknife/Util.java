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
package org.janelia.saalfeldlab.hotknife;

import static net.imglib2.cache.img.AccessFlags.VOLATILE;
import static net.imglib2.cache.img.PrimitiveType.BYTE;
import static net.imglib2.cache.img.PrimitiveType.FLOAT;

import java.util.ArrayList;
import java.util.List;

import org.janelia.saalfeldlab.hotknife.ops.UnaryComputerOpCellLoader;
import org.janelia.saalfeldlab.n5.DataBlock;

import ij.process.FloatProcessor;
import net.imagej.ops.Op;
import net.imagej.ops.OpService;
import net.imagej.ops.special.computer.UnaryComputerOp;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.ArrayDataAccessFactory;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.LoadedCellCacheLoader;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileByteArray;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileFloatArray;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Util {

	private Util() {}

	public static final FloatProcessor materialize(final RandomAccessibleInterval<FloatType> source) {
		final FloatProcessor target = new FloatProcessor((int) source.dimension(0), (int) source.dimension(1));
		copy(
				Views.zeroMin(source),
				ArrayImgs.floats(
						(float[]) target.getPixels(),
						target.getWidth(),
						target.getHeight()));
		return target;
	}

	public static final <T extends Type<T>> void copy(final RandomAccessible<? extends T> source, final RandomAccessibleInterval<T> target) {
		Views.flatIterable(Views.interval(Views.pair(source, target), target)).forEach(
				pair -> pair.getB().set(pair.getA()));
	}


	/**
	 * Crops the dimensions of a {@link DataBlock} at a given offset to fit
	 * into and {@link Interval} of given dimensions.  Fills long and int
	 * version of cropped block size.  Also calculates the grid raster position
	 * assuming that the offset divisible by block size without remainder.
	 *
	 * @param max
	 * @param offset
	 * @param blockSize
	 * @param croppedBlockSize
	 * @param intCroppedBlockDimensions
	 * @param gridPosition
	 */
	static void cropBlockDimensions(
			final long[] dimensions,
			final long[] offset,
			final int[] outBlockSize,
			final int[] blockSize,
			final long[] croppedBlockSize,
			final long[] gridPosition) {

		for (int d = 0; d < dimensions.length; ++d) {
			croppedBlockSize[d] = Math.min(blockSize[d], dimensions[d] - offset[d]);
			gridPosition[d] = offset[d] / outBlockSize[d];
		}
	}

	/**
	 * Create a {@link List} of grid blocks that, for each grid cell, contains
	 * the world coordinate offset, the size of the grid block, and the
	 * grid-coordinate offset.  The spacing for input grid and output grid
	 * are independent, i.e. world coordinate offsets and cropped block-sizes
	 * depend on the input grid, and the grid coordinates of the block are
	 * specified on an independent output grid.  Its is assumed that
	 * gridBlockSize is an integer multiple of outBlockSize.
	 *
	 * @param dimensions
	 * @param gridBlockSize
	 * @param outBlockSize
	 * @return
	 */
	public static List<long[][]> createGrid(
			final long[] dimensions,
			final int[] gridBlockSize,
			final int[] outBlockSize) {

		final int n = dimensions.length;
		final ArrayList<long[][]> gridBlocks = new ArrayList<>();

		final long[] offset = new long[n];
		final long[] gridPosition = new long[n];
		final long[] longCroppedGridBlockSize = new long[n];
		for (int d = 0; d < n;) {
			cropBlockDimensions(dimensions, offset, outBlockSize, gridBlockSize, longCroppedGridBlockSize, gridPosition);
				gridBlocks.add(
						new long[][]{
							offset.clone(),
							longCroppedGridBlockSize.clone(),
							gridPosition.clone()
						});

			for (d = 0; d < n; ++d) {
				offset[d] += gridBlockSize[d];
				if (offset[d] < dimensions[d])
					break;
				else
					offset[d] = 0;
			}
		}
		return gridBlocks;
	}

	/**
	 * Create a {@link List} of grid blocks that, for each grid cell, contains
	 * the world coordinate offset, the size of the grid block, and the
	 * grid-coordinate offset.
	 *
	 * @param dimensions
	 * @param blockSize
	 * @return
	 */
	public static List<long[][]> createGrid(
			final long[] dimensions,
			final int[] blockSize) {

		return createGrid(dimensions, blockSize, blockSize);
	}


	public static <O extends Op> RandomAccessibleInterval<UnsignedByteType> lazyProcessVolatileUnsignedByte(
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
				.withLoader(LoadedCellCacheLoader.get(grid, loader, type, VOLATILE));
		final CachedCellImg<UnsignedByteType, VolatileByteArray> img = new CachedCellImg<>(grid, type, cache, ArrayDataAccessFactory.get(BYTE, VOLATILE));
		return img;
	}

	public static <O extends Op> RandomAccessibleInterval<UnsignedByteType> lazyProcessUnsignedByte(
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
				.withLoader(LoadedCellCacheLoader.get(grid, loader, type));
		final CachedCellImg<UnsignedByteType, ByteArray> img = new CachedCellImg<>(grid, type, cache, ArrayDataAccessFactory.get(BYTE));
		return img;
	}

	public static <O extends Op> RandomAccessibleInterval<FloatType> lazyProcessVolatileFloat(
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
				.withLoader(LoadedCellCacheLoader.get(grid, loader, type, VOLATILE));
		final CachedCellImg<FloatType, VolatileFloatArray> img = new CachedCellImg<>(grid, type, cache, ArrayDataAccessFactory.get(FLOAT, VOLATILE));
		return img;
	}

	public static <O extends Op> RandomAccessibleInterval<FloatType> lazyProcessFloat(
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
				.withLoader(LoadedCellCacheLoader.get(grid, loader, type));
		final CachedCellImg<FloatType, FloatArray> img = new CachedCellImg<>(grid, type, cache, ArrayDataAccessFactory.get(FLOAT));
		return img;
	}

	public static RandomAccessibleInterval<FloatType> lazyProcessFloat(
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
				.withLoader(LoadedCellCacheLoader.get(grid, loader, type));
		final CachedCellImg<FloatType, FloatArray> img = new CachedCellImg<>(grid, type, cache, ArrayDataAccessFactory.get(FLOAT));
		return img;
	}
}
