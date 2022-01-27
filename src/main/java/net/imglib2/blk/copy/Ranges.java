package net.imglib2.blk.copy;

import java.util.ArrayList;
import java.util.List;

import static net.imglib2.blk.copy.Ranges.Direction.BACKWARD;
import static net.imglib2.blk.copy.Ranges.Direction.CONSTANT;
import static net.imglib2.blk.copy.Ranges.Direction.FORWARD;
import static net.imglib2.blk.copy.Ranges.Direction.STAY;

@FunctionalInterface
public interface Ranges
{
	/**
	 * Find ranges for one dimension.
	 *
	 * @param bx
	 * 		start of block in source coordinates (in pixels)
	 * @param bw
	 * 		width of block to copy (in pixels)
	 * @param iw
	 * 		source image width (in pixels)
	 * @param cw
	 * 		source cell width (in pixels)
	 */
	List< Range > findRanges( int bx, int bw, int iw, int cw );

	Ranges FIND_RANGES_CONSTANT = Ranges::findRanges_constant;
	Ranges FIND_RANGES_MIRROR_SINGLE = Ranges::findRanges_mirror_single;
	Ranges FIND_RANGES_MIRROR_DOUBLE = Ranges::findRanges_mirror_double;
	Ranges FIND_RANGES_BORDER = Ranges::findRanges_border;

	static Ranges forExtension( Extension method )
	{
		switch ( method )
		{
		case CONSTANT:
			return FIND_RANGES_CONSTANT;
		case MIRROR_SINGLE:
			return FIND_RANGES_MIRROR_SINGLE;
		case MIRROR_DOUBLE:
			return FIND_RANGES_MIRROR_DOUBLE;
		case BORDER:
		default:
			return FIND_RANGES_BORDER;
		}
	}

	enum Direction
	{
		FORWARD,
		BACKWARD,
		STAY,
		CONSTANT;
	}

	/**
	 * Copy {@code w} elements to coordinates {@code x} through {@code x + w}
	 * (exclusive) in destination, from source cell with {@code gridx} grid
	 * coordinate, starting at coordinate {@code cellx} within cell, and from
	 * there moving in {@code dir} for successive source elements.
	 *
	 * It is guaranteed that all {@code w} elements fall within the same cell.
	 */
	// TODO: should be flattened out instead of creating objects
	class Range
	{
		final int gridx;
		final int cellx;
		final int w;
		final Direction dir;
		final int x;

		public Range( final int gridx, final int cellx, final int w, final Direction dir, final int x )
		{
			this.gridx = gridx;
			this.cellx = cellx;
			this.w = w;
			this.dir = dir;
			this.x = x;
		}

		@Override
		public String toString()
		{
			return "Range{" +
					"gridx=" + gridx +
					", cellx=" + cellx +
					", w=" + w +
					", dir=" + dir +
					", x=" + x +
					'}';
		}
	}

	/**
	 * Split the requested interval into ranges covering (possibly partial)
	 * cells of the input image. The requested interval is given by start
	 * coordinate {@code bx} (in the extended source image) and size of the
	 * block to copy {@code bw}, in a particular dimension. The full size of the
	 * (non-extended) image in this dimension is given by {@code iw}, the size
	 * of a (non-truncated) cell in this dimension is given by {@code cw}.
	 * <p>
	 * Out-of-bounds values are set to a constant.
	 */
	static List< Range > findRanges_constant(
			int bx, // start of block in source coordinates (in pixels)
			int bw, // width of block to copy (in pixels)
			int iw, // source image width (in pixels)
			int cw  // source cell width (in pixels)
	)
	{
		List< Range > ranges = new ArrayList<>();

		if ( bw <= 0 )
			return ranges;

		int x = 0;
		if ( bx < 0 )
		{
			int w = Math.min( bw, -bx );
			ranges.add( new Range( -1, -1, w, CONSTANT, x ) );
			bw -= w;
			bx += w; // = 0
			x += w;
		}

		if ( bw <= 0 )
			return ranges;

		int gx = bx / cw;
		int cx = bx - ( gx * cw );
		while ( bw > 0 && bx < iw )
		{
			final int w = Math.min( bw, cellWidth( gx, cw, iw ) - cx );
			ranges.add( new Range( gx, cx, w, FORWARD, x ) );
			bw -= w;
			bx += w;
			x += w;
			++gx;
			cx = 0;
		}

		if ( bw > 0 )
			ranges.add( new Range( -1, -1, bw, CONSTANT, x ) );

		return ranges;
	}

	/**
	 * Split the requested interval into ranges covering (possibly partial)
	 * cells of the input image. The requested interval is given by start
	 * coordinate {@code bx} (in the extended source image) and size of the
	 * block to copy {@code bw}, in a particular dimension. The full size of the
	 * (non-extended) image in this dimension is given by {@code iw}, the size
	 * of a (non-truncated) cell in this dimension is given by {@code cw}.
	 * <p>
	 * Out-of-bounds values are determined by border extension (clamping to
	 * nearest pixel in the image).
	 */
	static List< Range >  findRanges_border(
			int bx, // start of block in source coordinates (in pixels)
			int bw, // width of block to copy (in pixels)
			int iw, // source image width (in pixels)
			int cw  // source cell width (in pixels)
	)
	{
		List< Range > ranges = new ArrayList<>();

		if ( bw <= 0 )
			return ranges;

		int x = 0;
		if ( bx < 0 )
		{
			int w = Math.min( bw, -bx );
			ranges.add( new Range( 0, 0, w, STAY, x ) );
			bw -= w;
			bx += w; // = 0
			x += w;
		}

		if ( bw <= 0 )
			return ranges;

		int gx = bx / cw;
		int cx = bx - ( gx * cw );
		while ( bw > 0 && bx < iw )
		{
			final int w = Math.min( bw, cellWidth( gx, cw, iw ) - cx );
			ranges.add( new Range( gx, cx, w, FORWARD, x ) );
			bw -= w;
			bx += w;
			x += w;
			++gx;
			cx = 0;
		}

		if ( bw <= 0 )
			return ranges;

		gx = ( iw - 1 ) / cw;
		cx = cellWidth( gx, cw, iw ) - 1;
		ranges.add( new Range( gx, cx, bw, STAY, x ) );

		return ranges;
	}

	/**
	 * Split the requested interval into ranges covering (possibly partial)
	 * cells of the input image. The requested interval is given by start
	 * coordinate {@code bx} (in the extended source image) and size of the
	 * block to copy {@code bw}, in a particular dimension. The full size of the
	 * (non-extended) image in this dimension is given by {@code iw}, the size
	 * of a (non-truncated) cell in this dimension is given by {@code cw}.
	 * <p>
	 * Out-of-bounds values are determined by mirroring with double boundary,
	 * i.e., border pixels are repeated.
	 */
	static List< Range > findRanges_mirror_double(
			int bx, // start of block in source coordinates (in pixels)
			int bw, // width of block to copy (in pixels)
			int iw, // source image width (in pixels)
			int cw  // source cell width (in pixels)
	)
	{
		List< Range > ranges = new ArrayList<>();

		final int pi = 2 * iw;
		bx = ( bx < 0 )
				? ( bx + 1 ) % pi + pi - 1
				: bx % pi;
		Direction dir = FORWARD;
		if ( bx >= iw )
		{
			bx = pi - 1 - bx;
			dir = BACKWARD;
		}

		int gx = bx / cw;
		int cx = bx - ( gx * cw );
		int x = 0;
		while ( bw > 0 )
		{
			if ( dir == FORWARD)
			{
				final int gxw = cellWidth( gx, cw, iw );
				final int w = Math.min( bw, gxw - cx );
				final Range range = new Range( gx, cx, w, FORWARD, x );
				ranges.add( range );

				bw -= w;
				x += w;

				if ( ++gx * cw >= iw ) // moving out of bounds
				{
					--gx;
					cx = gxw - 1;
					dir = BACKWARD;
				}
				else
				{
					cx = 0;
				}
			}
			else // dir == BACKWARD
			{
				final int w = Math.min( bw, cx + 1 );
				final Range range = new Range( gx, cx, w, BACKWARD, x );
				ranges.add( range );

				bw -= w;
				x += w;

				if ( gx == 0 ) // moving into bounds
				{
					cx = 0;
					dir = FORWARD;
				}
				else
				{
					cx = cellWidth( --gx, cw, iw ) - 1;
				}
			}

		}
		return ranges;
	}

	/**
	 * Split the requested interval into ranges covering (possibly partial)
	 * cells of the input image. The requested interval is given by start
	 * coordinate {@code bx} (in the extended source image) and size of the
	 * block to copy {@code bw}, in a particular dimension. The full size of the
	 * (non-extended) image in this dimension is given by {@code iw}, the size
	 * of a (non-truncated) cell in this dimension is given by {@code cw}.
	 * <p>
	 * Out-of-bounds values are determined by mirroring with single boundary,
	 * i.e., border pixels are not repeated.
	 */
	static List< Range > findRanges_mirror_single(
			int bx, // start of block in source coordinates (in pixels)
			int bw, // width of block to copy (in pixels)
			int iw, // source image width (in pixels)
			int cw  // source cell width (in pixels)
	)
	{
		List< Range > ranges = new ArrayList<>();

		final int pi = 2 * iw - 2;
		bx = ( bx < 0 )
				? ( bx + 1 ) % pi + pi - 1
				: bx % pi;
		Direction dir = FORWARD;
		if ( bx >= iw )
		{
			bx = pi - bx;
			dir = BACKWARD;
		}

		int gx = bx / cw;
		int cx = bx - ( gx * cw );
		int x = 0;
		while ( bw > 0 )
		{
			if ( dir == FORWARD)
			{
				final int gxw = cellWidth( gx, cw, iw );
				final int w = Math.min( bw, gxw - cx );
				final Range range = new Range( gx, cx, w, FORWARD, x );
//				System.out.println( "range = " + range );
				ranges.add( range );

				bw -= w;
				x += w;

				if ( ++gx * cw >= iw ) // moving out of bounds
				{
					--gx;
					cx = gxw - 2;
					dir = BACKWARD;
				}
				else
				{
					cx = 0;
				}
			}
			else // dir == BACKWARD
			{
				final int w = Math.min( bw, gx == 0 ? cx : ( cx + 1 ) );
				final Range range = new Range( gx, cx, w, BACKWARD, x );
//				System.out.println( "range = " + range );
				ranges.add( range );

				bw -= w;
				x += w;

				if ( gx == 0 ) // moving into bounds
				{
					cx = 0;
					dir = FORWARD;
				}
				else
				{
					cx = cellWidth( --gx, cw, iw ) - 1;
				}
			}

		}
		return ranges;
	}

	static int cellWidth( final int gx, final int cw, final int iw )
	{
		final int gw = iw / cw;
		if ( gx < gw )
			return cw;
		else if ( gx == gw )
			return iw - cw * gw;
		else
			throw new IllegalArgumentException();
	}
}
