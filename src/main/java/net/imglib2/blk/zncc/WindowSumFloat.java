package net.imglib2.blk.zncc;

import java.util.Arrays;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;

public class WindowSumFloat
{
	private final int n;
	private final int[] windowSize;

	private final int[] targetSize;   // desired output size
	private final int[] sourceSize;   // size for padded input
	private final int[] sourceOffset;

	private float[] sourceBuf;
	private float[] targetBuf;
	private float[] aux;

	private final int[] os;
	private final int[] ss;
	private final int[] ils;
	private final int[] jls;
	private final int[] ols;

	public WindowSumFloat( final int n, final int[] windowSize )
	{
		this.n = n;
		this.windowSize = windowSize;

		os = new int[ n ];
		ss = new int[ n ];
		ils = new int[ n ];
		jls = new int[ n ];
		ols = new int[ n ];

		targetSize = new int[ n ];
		sourceSize = new int[ n ];
		sourceOffset = new int[ n ];

		for ( int d = 0; d < n; ++d )
			sourceOffset[ d ] = -( windowSize[ d ] - 1 ) / 2;
	}

	public void setTargetSize( final long[] targetSize )
	{
		setTargetSize( Util.long2int( targetSize ) );
	}

	public void setTargetSize( final int[] targetSize )
	{
		if ( Arrays.equals( targetSize, this.targetSize ) )
			return;

		for ( int d = 0; d < n; d++ )
		{
			this.targetSize[ d ] = targetSize[ d ];
			sourceSize[ d ] = targetSize[ d ];
		}

		for ( int d = n - 1; d >= 0; --d )
		{
			final int w = windowSize[ d ];

			os[ d ] = 1;
			for ( int dd = 0; dd < d; ++dd )
				os[ d ] *= sourceSize[ dd ];

			ss[ d ] = os[ d ] * w;
			ils[ d ] = os[ d ] * ( targetSize[ d ] + w - 1 );
			jls[ d ] = os[ d ] * targetSize[ d ];

			ols[ d ] = 1;
			for ( int dd = d + 1; dd < n; ++dd )
				ols[ d ] *= sourceSize[ dd ];

			if ( d == 0 && n > 1 )
			{
				final int l = ( int ) Intervals.numElements( sourceSize );
				if ( targetBuf == null || targetBuf.length < l )
					targetBuf = new float[ l ];
			}

			sourceSize[ d ] += w - 1;
			if ( d == 0 )
			{
				final int l = ( int ) Intervals.numElements( sourceSize );
				if ( aux == null || aux.length < l )
					aux = new float[ l ];
			}
		}
	}

	public int[] getSourceSize()
	{
		return sourceSize;
	}

	public int[] getSourceOffset()
	{
		return sourceOffset;
	}

	// optional. also other arrays can be passed to compute()
	public float[] getSourceBuffer()
	{
		final int l = ( int ) Intervals.numElements( sourceSize );
		if ( sourceBuf == null || sourceBuf.length < l )
			sourceBuf = new float[ l ];
		return sourceBuf;
	}

	public void compute( final float[] source, final float[] target )
	{
		for ( int d = 0; d < n; ++d )
		{
			final float[] currentTarget = ( d == n - 1 ) ? target : targetBuf;
			final float[] currentSource = ( d == 0 ) ? source : targetBuf;
			sum( currentSource, aux, currentTarget, os[ d ], ss[ d ], ils[ d ], jls[ d ], ols[ d ] );
		}
	}

	/**
	 * @param source
	 * @param aux
	 * @param target
	 * @param o
	 * 		offset between consecutive elements in current dimension (in both {@code aux} and {@code source})
	 * @param s
	 * 		offset between start and end of summation window in current dimension (in {@code aux})
	 * @param il
	 * 		length of inner loop for summation, stride of one "inner line" in {@code source} and {@code aux}
	 * @param jl
	 * 		length of inner loop for subtraction, stride of one "inner line" in {@code target}
	 * @param ol
	 * 		length of outer loop
	 */
	private static void sum(
			final float[] source,
			final float[] aux,
			final float[] target,
			final int o,
			final int s,
			final int il,
			final int jl,
			final int ol )
	{
		for ( int y = 0; y < ol; ++y )
		{
			final int sio = y * il;
			for ( int x = 0; x < o; ++x )
			{
				aux[ x + sio ] = source[ x + sio ];
			}
			for ( int x = 0; x < il - o; ++x )
			{
				aux[ x + o + sio ] = aux[ x + sio ] + source[ x + o + sio ];
			}
		}

		for ( int y = 0; y < ol; ++y )
		{
			final int sio = y * il;
			final int tio = y * jl;
			for ( int x = 0; x < o; ++x )
			{
				target[ x + tio ] = aux[ x + s + sio - o ];
			}
			for ( int x = 0; x < jl - o; ++x )
			{
				target[ x + o + tio ] = aux[ x + s + sio ] - aux[ x + sio ];
			}
		}
	}
}
