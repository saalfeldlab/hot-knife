package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.Consumer;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.volatiles.VolatileViews;
import ij.ImagePlus;
import ij.io.Opener;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.in.TiffReader;
import net.imagej.legacy.LegacyCommandline.Run;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.lazy.Lazy;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.ByteType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

public class SparkBlockedTIFFtoN5 {

	public static class MetaData<T extends NativeType<T>>
	{
		final long[] dim;
		final T type;
		final IFormatReader reader;
		final int pixelType, bitsPerPixel;
		final boolean isLittleEndian;

		public MetaData(
				final long[] dim,
				final T type,
				final IFormatReader reader,
				final int pixelType,
				final int bitsPerPixel,
				final boolean isLittleEndian )
		{
			this.dim = dim;
			this.type = type;
			this.reader = reader;
			this.pixelType = pixelType;
			this.bitsPerPixel = bitsPerPixel;
			this.isLittleEndian = isLittleEndian;
		}

		public T type() { return type; }
		public long[] dim() { return dim; }
		public IFormatReader reader() { return reader; }
		public int pixelType() { return pixelType; }
		public int bitsPerPixel() { return bitsPerPixel; }
		public boolean isLittleEndian() { return isLittleEndian; }
	}

	public static <T extends NativeType<T>> MetaData<T> openAndParseMetaData( final String file ) throws FormatException, IOException
	{
		final TiffReader r = new TiffReader();
		r.setId( file );

		final long[] dim;

		if ( r.getSizeT() > 1 )
			System.out.println( "Warning, more than one timepoints, not supported right now.");

		if ( r.getSizeC() > 1 )
			System.out.println( "Warning, more than one channel, not supported right now.");

		if ( r.getSizeZ() > 1 )
			dim = new long[] { r.getSizeX(), r.getSizeY(), r.getSizeZ() };
		else
			dim = new long[] { r.getSizeX(), r.getSizeY() };

		final int pixelType = r.getPixelType();
		final T type;

		if ( pixelType == FormatTools.UINT8 )
			type = (T)(Object) new UnsignedByteType();
		else if ( pixelType == FormatTools.INT8 )
			type = (T)(Object) new ByteType();
		else if ( pixelType == FormatTools.UINT16 )
			type = (T)(Object) new UnsignedShortType();
		else if ( pixelType == FormatTools.INT16 )
			type = (T)(Object) new ShortType();
		else if ( pixelType == FormatTools.FLOAT )
			type = (T)(Object) new FloatType();
		else
			throw new RuntimeException( "Import type " + pixelType + " not supported." );

		return new MetaData<>( dim, type, r, pixelType, r.getBitsPerPixel(), r.isLittleEndian() );
	}

	public static <T extends NativeType<T>> RandomAccessibleInterval<T> openSliceBlock(
			final MetaData<T> metaData,
			final int slice,
			final int minX,
			final int minY,
			final int width,
			final int height ) throws IOException, FormatException {

		final byte[] bytes = new byte[width * height * (int)Math.ceil(metaData.bitsPerPixel() / 8.0)];
		metaData.reader().openBytes(slice, bytes, minX, minY, width, height);

		switch (metaData.pixelType()) {
			case FormatTools.UINT8:
				return (RandomAccessibleInterval<T>)ArrayImgs.unsignedBytes(bytes, width, height);
			case FormatTools.INT8:
				return (RandomAccessibleInterval<T>)ArrayImgs.bytes(bytes, width, height);
			}
	
			final ByteBuffer buffer = ByteBuffer.wrap(bytes);
			buffer.order(metaData.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
			switch (metaData.pixelType()) {
			case FormatTools.UINT16: {
				final short[] shorts = new short[width * height];
				buffer.asShortBuffer().get(shorts);
				return (RandomAccessibleInterval<T>)ArrayImgs.unsignedShorts(shorts, width, height);
			}
			case FormatTools.INT16: {
				final short[] shorts = new short[width * height];
				buffer.asShortBuffer().get(shorts);
				return (RandomAccessibleInterval<T>)ArrayImgs.shorts(shorts, width, height);
			}
			case FormatTools.FLOAT: {
				final float[] floats = new float[width * height];
				buffer.asFloatBuffer().get(floats);
				return (RandomAccessibleInterval<T>)ArrayImgs.floats(floats, width, height);
			}
		}
		return null;
	}

	public static class BFCellLoader< T extends NativeType< T > > implements Consumer< RandomAccessibleInterval< T > >
	{
		final MetaData<T> metaData;

		public BFCellLoader( final MetaData<T> metaData )
		{
			this.metaData = metaData;
		}

		@Override
		public void accept( final RandomAccessibleInterval<T> t )
		{
			try
			{
				if ( t.numDimensions() == 2 )
				{
					final RandomAccessibleInterval< T > block =
							openSliceBlock(
									metaData,
									0,
									(int)t.min( 0 ),
									(int)t.min( 1 ),
									(int)t.dimension( 0 ),
									(int)t.dimension( 1 ));

					final Cursor< T > in = Views.flatIterable( block ).cursor();
					final Cursor< T > out = Views.flatIterable( t ).cursor();

					while ( out.hasNext() )
						out.next().set( in.next() );
				}
				else if ( t.numDimensions() == 3 )
				{
					for ( int z = (int)t.min( 2 ); z <= t.max(2 ); ++z )
					{
						final RandomAccessibleInterval< T > block =
								openSliceBlock(
										metaData,
										z,
										(int)t.min( 0 ),
										(int)t.min( 1 ),
										(int)t.dimension( 0 ),
										(int)t.dimension( 1 ));

						final Cursor< T > in = Views.flatIterable( block ).cursor();
						final Cursor< T > out = Views.flatIterable( Views.hyperSlice( t, 2, z ) ).cursor();

						while ( out.hasNext() )
							out.next().set( in.next() );
					}
				}
				else
				{
					throw new RuntimeException( "dimensionality " + t.numDimensions() + " not supported. " );
				}
			} catch (IOException e) {
				e.printStackTrace();
			} catch (FormatException e) {
				e.printStackTrace();
			}
		}
		
	}

	public static < T extends NativeType< T > > void displayFile( final MetaData<T> metaData, final int ... blockSizeIn )
	{
		System.out.println( "Size: " + Util.printCoordinates( metaData.dim() ) );

		final int[] blockSize;

		if ( blockSizeIn.length != metaData.dim().length )
		{
			blockSize = new int[ metaData.dim().length ];
			for ( int d = 0; d < blockSize.length; ++d )
				blockSize[ d ] = blockSizeIn[ blockSizeIn.length - 1 ];
			for ( int d = 0; d < blockSizeIn.length; ++d )
				blockSize[ d ] = blockSizeIn[ d ];
		}
		else
		{
			blockSize = blockSizeIn;
		}

		final CachedCellImg<T, ?> img = Lazy.generate(
				new FinalInterval( metaData.dim() ),
				blockSize,
				metaData.type(),
				AccessFlags.setOf( AccessFlags.VOLATILE ),
				new BFCellLoader<>( metaData ) );

		BdvOptions options = BdvOptions.options().numRenderingThreads( Runtime.getRuntime().availableProcessors() );

		if ( metaData.dim.length == 2 )
			options = options.is2D();

		BdvFunctions.show( VolatileViews.wrapAsVolatile( img ), "cellopen", options );
	}

	public static void main( String[] args ) throws FormatException, IOException
	{
		final String input = "/Users/spreibi/Downloads/mosaic_DAPI_z0.tif";

		displayFile( (MetaData)(Object)openAndParseMetaData( input ), 4096 );
	}
}
