package net.imglib2.blk.zncc;

import java.util.Arrays;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;

public class ZNCCFloat
{
	private final boolean invert;
	private final int windowNumElements;
	private final WindowSumFloat windowSum;

	private final int[] targetSize;
	private int targetBufLen;
	private int sourceBufLen;

	private float[][] sourceBufs;

	private float[] bufII;
	private float[] bufIJ;
	private float[] bufJJ;
	private float[] bufSumI;
	private float[] bufSumJ;
	private float[] bufSumII;
	private float[] bufSumJJ;
	private float[] bufSumIJ;


	public ZNCCFloat( final int[] windowSize, final boolean invert )
	{
		final int n = windowSize.length;
		windowNumElements = ( int ) Intervals.numElements( windowSize );
		targetSize = new int[ n ];
		windowSum = new WindowSumFloat( n, windowSize );
		this.invert = invert;
	}

	public void setTargetSize( final long[] targetSize )
	{
		setTargetSize( Util.long2int( targetSize ) );
	}

	public void setTargetSize( final int[] targetSize )
	{
		if ( Arrays.equals( targetSize, this.targetSize ) )
			return;

		windowSum.setTargetSize( targetSize );

		targetBufLen = ( int ) Intervals.numElements( targetSize );
		sourceBufLen = ( int ) Intervals.numElements( windowSum.getSourceSize() );

		if ( bufII == null || bufII.length < sourceBufLen )
		{
			bufII = new float[ sourceBufLen ];
			bufIJ = new float[ sourceBufLen ];
			bufJJ = new float[ sourceBufLen ];
			bufSumI = new float[ targetBufLen ];
			bufSumJ = new float[ targetBufLen ];
			bufSumII = new float[ targetBufLen ];
			bufSumJJ = new float[ targetBufLen ];
			bufSumIJ = new float[ targetBufLen ];
		}
	}

	public int[] getSourceSize()
	{
		return windowSum.getSourceSize();
	}

	public int[] getSourceOffset()
	{
		return windowSum.getSourceOffset();
	}

	// optional. also other arrays can be passed to compute()
	public float[][] getSourceBuffers()
	{
		if ( sourceBufs == null )
			sourceBufs = new float[ 2 ][];

		if ( sourceBufs[ 0 ] == null || sourceBufs[ 0 ].length < sourceBufLen )
		{
			sourceBufs[ 0 ] = new float[ sourceBufLen ];
			sourceBufs[ 1 ] = new float[ sourceBufLen ];
		}

		return sourceBufs;
	}

	public void compute( final float[][] srcs, final float[] dest )
	{
		final float[] bufI = srcs[ 0 ];
		final float[] bufJ = srcs[ 1 ];

		square( bufI, bufII, sourceBufLen );
		square( bufJ, bufJJ, sourceBufLen );
		mul( bufI, bufJ, bufIJ, sourceBufLen );

		windowSum.compute( bufI, bufSumI );
		windowSum.compute( bufJ, bufSumJ );
		windowSum.compute( bufII, bufSumII );
		windowSum.compute( bufJJ, bufSumJJ );
		windowSum.compute( bufIJ, bufSumIJ );

		zncc( bufSumI, bufSumJ, bufSumII, bufSumJJ, bufSumIJ, windowNumElements, dest, targetBufLen, invert );
	}

	public static void mul( final float[] bufI, final float[] bufJ, final float[] bufIJ, final int length )
	{
		if ( bufI == null || bufJ == null || bufIJ == null )
			throw new NullPointerException();

		if ( bufI.length < length || bufJ.length < length || bufIJ.length < length )
			throw new IndexOutOfBoundsException();

		for ( int i = 0; i < length; i++ )
			bufIJ[ i ] = bufI[ i ] * bufJ[ i ];
	}

	public static void square( final float[] bufI, final float[] bufII, final int length )
	{
		if ( bufI == null || bufII == null )
			throw new NullPointerException();

		if ( bufI.length < length || bufII.length < length )
			throw new IndexOutOfBoundsException();

		for ( int i = 0; i < length; i++ )
			bufII[ i ] = bufI[ i ] * bufI[ i ];
	}

	public static void zncc(
			final float[] sumI,
			final float[] sumJ,
			final float[] sumII,
			final float[] sumJJ,
			final float[] sumIJ,
			final int n,
			final float[] zncc,
			final int length,
			final boolean invert )
	{
		if ( sumI == null || sumJ == null || sumII == null || sumJJ == null || sumIJ == null || zncc == null )
			throw new NullPointerException();

		if ( sumI.length < length || sumJ.length < length || sumII.length < length || sumJJ.length < length || sumIJ.length < length || zncc.length < length )
			throw new IndexOutOfBoundsException();

		if ( invert )
		{
			for ( int i = 0; i < length; i++ )
				zncc[ i ] = 1f - ( n * sumIJ[ i ] - sumI[ i ] * sumJ[ i ] ) /
						( float ) Math.sqrt(
								( n * sumII[ i ] - sumI[ i ] * sumI[ i ] ) *
										( n * sumJJ[ i ] - sumJ[ i ] * sumJ[ i ] ) );
		}
		else
		{
			for ( int i = 0; i < length; i++ )
				zncc[ i ] = ( n * sumIJ[ i ] - sumI[ i ] * sumJ[ i ] ) /
						( float ) Math.sqrt(
								( n * sumII[ i ] - sumI[ i ] * sumI[ i ] ) *
										( n * sumJJ[ i ] - sumJ[ i ] * sumJ[ i ] ) );
		}
	}
}
