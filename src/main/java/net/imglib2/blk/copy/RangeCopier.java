package net.imglib2.blk.copy;

import net.imglib2.img.NativeImg;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.cell.AbstractCellImg;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.planar.PlanarImg;

public interface RangeCopier< T >
{
	/**
	 * Copy the block starting at {@code srcPos} with the given {@code size}
	 * into the (appropriately sized) {@code dest} array.
	 *
	 * @param srcPos
	 * 		min coordinates of block to copy from src Img.
	 * @param dest
	 * 		destination array. Type is {@code byte[]}, {@code float[]},
	 * 		etc, corresponding to the src Img's native type.
	 * @param size
	 * 		dimensions of block to copy from src Img.
	 */
	void copy( final int[] srcPos, final T dest, final int[] size );

	static < T > RangeCopier< T > create(
			final NativeImg< ?, ? > img,
			final Ranges findRanges,
			final MemCopy< T > memCopy,
			final T oob )
	{
		if ( img instanceof AbstractCellImg )
			return new CellImgRangeCopier<>( ( AbstractCellImg< ?, ?, ? extends Cell< ? >, ? > ) img, findRanges, memCopy, oob );
		else if ( img instanceof PlanarImg )
			return new PlanarImgRangeCopier<>( ( PlanarImg< ?, ? > ) img, findRanges, memCopy, oob );
		else if ( img instanceof ArrayImg )
			return new ArrayImgRangeCopier<>( ( ArrayImg<?, ?> ) img, findRanges, memCopy, oob );
		else
			throw new IllegalArgumentException();
	}
}
