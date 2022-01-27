package net.imglib2.blk.copy;

import java.util.Arrays;

// T is a primitive array type
public interface MemCopy< T >
{
	void copyForward( final T src, final int srcPos, final T dest, final int destPos, final int length );

	void copyReverse( final T src, final int srcPos, final T dest, final int destPos, final int length );

	void copyValue( final T src, final int srcPos, final T dest, final int destPos, final int length );

	MemCopyByte BYTE = new MemCopyByte();
	MemCopyShort SHORT = new MemCopyShort();
	MemCopyFloat FLOAT = new MemCopyFloat();
	MemCopyDouble DOUBLE = new MemCopyDouble();

	class MemCopyByte implements MemCopy< byte[] >
	{
		@Override
		public void copyForward( final byte[] src, final int srcPos, final byte[] dest, final int destPos, final int length )
		{
			System.arraycopy( src, srcPos, dest, destPos, length );
		}

		@Override
		public void copyReverse( final byte[] src, final int srcPos, final byte[] dest, final int destPos, final int length )
		{
			for ( int i = 0; i < length; ++i )
				dest[ destPos + i ] = src[ srcPos - i ];
		}

		@Override
		public void copyValue( final byte[] src, final int srcPos, final byte[] dest, final int destPos, final int length )
		{
			Arrays.fill( dest, destPos, destPos + length, src[ srcPos ] );
		}
	}

	class MemCopyShort implements MemCopy< short[] >
	{
		@Override
		public void copyForward( final short[] src, final int srcPos, final short[] dest, final int destPos, final int length )
		{
			System.arraycopy( src, srcPos, dest, destPos, length );
		}

		@Override
		public void copyReverse( final short[] src, final int srcPos, final short[] dest, final int destPos, final int length )
		{
			for ( int i = 0; i < length; ++i )
				dest[ destPos + i ] = src[ srcPos - i ];
		}

		@Override
		public void copyValue( final short[] src, final int srcPos, final short[] dest, final int destPos, final int length )
		{
			Arrays.fill( dest, destPos, destPos + length, src[ srcPos ] );
		}
	}

	class MemCopyFloat implements MemCopy< float[] >
	{
		@Override
		public void copyForward( final float[] src, final int srcPos, final float[] dest, final int destPos, final int length )
		{
			System.arraycopy( src, srcPos, dest, destPos, length );
		}

		@Override
		public void copyReverse( final float[] src, final int srcPos, final float[] dest, final int destPos, final int length )
		{
			for ( int i = 0; i < length; ++i )
				dest[ destPos + i ] = src[ srcPos - i ];
		}

		@Override
		public void copyValue( final float[] src, final int srcPos, final float[] dest, final int destPos, final int length )
		{
			Arrays.fill( dest, destPos, destPos + length, src[ srcPos ] );
		}
	}

	class MemCopyDouble implements MemCopy< double[] >
	{
		@Override
		public void copyForward( final double[] src, final int srcPos, final double[] dest, final int destPos, final int length )
		{
			System.arraycopy( src, srcPos, dest, destPos, length );
		}

		@Override
		public void copyReverse( final double[] src, final int srcPos, final double[] dest, final int destPos, final int length )
		{
			for ( int i = 0; i < length; ++i )
				dest[ destPos + i ] = src[ srcPos - i ];
		}

		@Override
		public void copyValue( final double[] src, final int srcPos, final double[] dest, final int destPos, final int length )
		{
			Arrays.fill( dest, destPos, destPos + length, src[ srcPos ] );
		}
	}
}
