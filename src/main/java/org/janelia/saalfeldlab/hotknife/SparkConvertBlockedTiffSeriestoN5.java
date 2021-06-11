package org.janelia.saalfeldlab.hotknife;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.in.TiffReader;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.lazy.Lazy;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.ByteType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

public class SparkConvertBlockedTiffSeriestoN5 {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--urlFormat", required = true, usage = "Input URL format for tiff series, e.g. /nrs/flyem/data/Z0115-22_Sec26/flatten/flattened/zcorr.%05d-flattened.tif")
		private final String urlFormat = null;

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "--n5Dataset", required = true, usage = "N5 dataset, e.g. /Sec26")
		final String datasetName = null;

		@Option(name = "--min", required = false, usage = "Min coordinate of the output volume, e.g. 0,0,0")
		private final String minString = null;
		private final long[] min;

		@Option(name = "--size", required = false, usage = "Size of the output volume, e.g. 10000,20000,30000, a number <= 0 for any dimensions indicates default sourceSize - min")
		private final String sizeString = null;
		private final long[] size;

		@Option(name = "--blockSize", required = false, usage = "Size of output blocks, e.g. 128,128,128")
		private final String blockSizeString = null;
		private final int[] blockSize;

		@Option(name = "--firstSlice", required = false, usage = "first slice index (if not 0)")
		private long firstSliceIndex = 0;

		private final long[] sourceSize;
		private int typeBioformats = -1;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			sourceSize = new long[3];
			min = new long[3];
			size = new long[3];
			blockSize = new int[3];
			try {
				parser.parseArgument(args);

				if (minString == null)
					Arrays.fill(min, 0);
				else
					parseCSLongArray(minString, min);

				System.out.println(String.format(urlFormat, firstSliceIndex + min[2]));

				/* width and height */
				final MetaData<?> meta = SparkConvertBlockedTiffSeriestoN5.openAndParseMetaData( String.format(urlFormat, firstSliceIndex + min[2]) );
				sourceSize[0] = meta.dim()[ 0 ];
				sourceSize[1] = meta.dim()[ 1 ];
				typeBioformats = meta.pixelType();

				/* depth */
				final File[] tiffs = new File(urlFormat).getParentFile().listFiles(
						(dir, file) -> file.endsWith(urlFormat.substring(urlFormat.lastIndexOf('.'))));
				sourceSize[2] = tiffs.length;

				if (sizeString == null) {
					size[0] = sourceSize[0] - min[0];
					size[1] = sourceSize[1] - min[1];
//					size[2] = sourceSize[2] - min[2];
					size[2] = sourceSize[2];
				} else
					parseCSLongArray(sizeString, size);

				/* default min and size for -1 fields */
				for (int i = 0; i < size.length; ++i) {
					if (min[i] <= 0) min[i] = 0;
					if (size[i] <= 0) size[i] = sourceSize[i] - min[i];
				}

				if (blockSizeString == null)
					blockSize[0] = blockSize[1] = blockSize[2] = 128;
				else
					parseCSIntArray(blockSizeString, blockSize);

				parsedSuccessfully = true;
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			} catch (FormatException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		/**
		 * @return the min
		 */
		public long[] getMin() {
			return min;
		}

		/**
		 * @return the urlFormat
		 */
		public String getUrlFormat() {
			return urlFormat;
		}

		/**
		 * @return the n5Path
		 */
		public String getN5Path() {
			return n5Path;
		}

		/**
		 * @return the datasetName
		 */
		public String getDatasetName() {
			return datasetName;
		}

		/**
		 * @return the size
		 */
		public long[] getSize() {
			return size;
		}

		/**
		 * @return the blockSize
		 */
		public int[] getBlockSize() {
			return blockSize;
		}

		/**
		 * @return the sourceSize
		 */
		public long[] getSourceSize() {
			return sourceSize;
		}

		/**
		 * @return the firstSlice
		 */
		public long getFirstSliceIndex() {
			return firstSliceIndex;
		}

		/**
		 * @return the Pixel type as defined by BioFormats
		 */
		public int getPixelTypeBioFormats() {
			return typeBioformats;
		}
	}

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

	public static final < T extends NativeType<T> > void saveTIFFSeries(
			final JavaSparkContext sc,
			final String urlFormat,
			final String n5Path,
			final String datasetName,
			final int typeBioFormats,
			final long[] min,
			final long[] size,
			final int[] blockSize,
			final long firstSliceIndex) throws IOException, FormatException {

		final N5Writer n5 = new N5FSWriter(n5Path);

		final DataType type;
		switch (typeBioFormats) {
		case FormatTools.UINT16:
			type = DataType.UINT16;
			break;
		case FormatTools.INT16:
			type = DataType.INT16;
			break;
		case FormatTools.FLOAT:
			type = DataType.FLOAT32;
			break;
		case FormatTools.INT8:
			type = DataType.INT8;
			break;
		default:
			type = DataType.UINT8;
			break;
		}

        final int[] slicesDatasetBlockSize = new int[]{
        		blockSize[0] * 8,
        		blockSize[1] * 8,
        		1};
        n5.createDataset(
        		datasetName,
        		size,
        		slicesDatasetBlockSize,
        		type,
        		new GzipCompression());
		final ArrayList<Long> slices = new ArrayList<>();
		for (long z = min[2]; z < min[2] + size[2]; ++z)
			slices.add(z);

		final JavaRDD<Long> rddSlices = sc.parallelize(slices);

		rddSlices.foreach(sliceIndex -> {

			final MetaData< T > metaData = openAndParseMetaData( String.format(urlFormat, sliceIndex + firstSliceIndex) );

			final CachedCellImg<T, ?> img = Lazy.generate(
					new FinalInterval( metaData.dim() ),
					slicesDatasetBlockSize,
					metaData.type(),
					AccessFlags.setOf(),
					new BFCellLoader<>( metaData ) );

			@SuppressWarnings({ "unchecked", "rawtypes" })
			final RandomAccessibleInterval slice =
					Views.offsetInterval(
							img,
							new long[]{
									min[0],
									min[1]},
							new long[]{
									size[0],
									size[1]});
			final N5Writer n5Local = new N5FSWriter(n5Path);
			N5Utils.saveBlock(
					Views.addDimension(slice, 0, 0),
					n5Local,
					datasetName,
					new long[]{0, 0, sliceIndex - min[2]});
		});

		n5.close();
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException, FormatException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName( "SparkConvertTiffSeriesToN5" );
        final JavaSparkContext sc = new JavaSparkContext(conf);

        final String slicesDatasetName = options.getDatasetName() + "-slices";

		/* parallelize over slices */
        saveTIFFSeries(
        		sc,
        		options.getUrlFormat(),
        		options.getN5Path(),
        		slicesDatasetName,
        		options.getPixelTypeBioFormats(),
        		options.getMin(),
        		options.getSize(),
        		options.getBlockSize(),
        		options.getFirstSliceIndex());

		/* re-block */
		SparkConvertTiffSeriesToN5.reSave(
				sc,
				options.getN5Path(),
				slicesDatasetName,
				options.getDatasetName(),
				options.getBlockSize());

		sc.close();
	}

}
