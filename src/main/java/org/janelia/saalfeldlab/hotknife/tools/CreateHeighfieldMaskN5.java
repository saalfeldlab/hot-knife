package org.janelia.saalfeldlab.hotknife.tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class CreateHeighfieldMaskN5 implements Callable<Void>
{
	@Option(names = {"-i", "--n5Path"}, required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"-j", "--n5FieldPath"}, required = false, description = "N5 output path for height field, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5FieldPath = null;

	@Option(names = {"-d", "--n5Raw"}, required = true, description = "N5 input group for raw, e.g. /raw")
	private String rawGroup = null;

	@Option(names = {"-fMin", "--n5FieldMin"}, required = true, description = "N5 field dataset, e.g. /surface/s1/min")
	private String fieldGroupMin = null;

	@Option(names = {"-fMax", "--n5FieldMax"}, required = true, description = "N5 field dataset, e.g. /surface/s1/max")
	private String fieldGroupMax = null;

	@Option(names = {"-o", "--n5Mask"}, required = true, description = "N5 output group for mask, e.g. /raw")
	private String maskGroup = null;

	@Option(names = {"-s", "--scale"}, required = true,  split=",", description = "downsampling factors, e.g. 6,6,1")
	private int[] downsamplingFactors = null;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new CreateHeighfieldMaskN5(), args);
	}

	@SuppressWarnings("unchecked")
	@Override
	public final Void call() throws IOException {

		final N5Writer n5 = new N5FSWriter(n5Path);
		final N5FSReader n5Field = new N5FSReader(n5FieldPath);

		System.out.println("Loading dimensions and blocksize of raw N5" + n5Path + ":/" + rawGroup + "/s0 ... " );
		final String fullRes = rawGroup + "/s0";
		final long[] dimensions = n5.getAttribute( fullRes, "dimensions", long[].class );
		final int[] blockSize = n5.getAttribute( fullRes, "blockSize", int[].class );
		System.out.println( net.imglib2.util.Util.printCoordinates( dimensions ) + ", blocksize=" + net.imglib2.util.Util.printCoordinates( blockSize ) );

		final int nThreads = Math.max( 1, Runtime.getRuntime().availableProcessors() );
		final ExecutorService service = Executors.newFixedThreadPool( nThreads );

		System.out.println("Loading min height field " + n5FieldPath + ":/" + fieldGroupMin + "... " );
		final RandomAccessibleInterval<FloatType> heightFieldSourceMin = N5Utils.open(n5Field, fieldGroupMin);
		final ArrayImg<FloatType, ?> heightFieldMin = new ArrayImgFactory<>(new FloatType()).create(heightFieldSourceMin);
		org.janelia.saalfeldlab.hotknife.util.Util.copy(heightFieldSourceMin, heightFieldMin, service);

		final RealRandomAccessible<DoubleType> minField = Transform.scaleAndShiftHeightFieldAndValues(
				heightFieldMin,
				new double[]{
						downsamplingFactors[0],
						downsamplingFactors[1],
						downsamplingFactors[2]});

		System.out.println("Loading max height field " + n5FieldPath + ":/" + fieldGroupMax + "... " );
		final RandomAccessibleInterval<FloatType> heightFieldSourceMax = N5Utils.open(n5Field, fieldGroupMax);
		final ArrayImg<FloatType, ?> heightFieldMax = new ArrayImgFactory<>(new FloatType()).create(heightFieldSourceMax);
		org.janelia.saalfeldlab.hotknife.util.Util.copy(heightFieldSourceMax, heightFieldMax, service);

		final RealRandomAccessible<DoubleType> maxField = Transform.scaleAndShiftHeightFieldAndValues(
				heightFieldMax,
				new double[]{
						downsamplingFactors[0],
						downsamplingFactors[1],
						downsamplingFactors[2]});

		//new ImageJ();

		final List< Callable< Void > > tasks = new ArrayList<>();
		final AtomicInteger nextBlock = new AtomicInteger();
		final String maskGroupFinal = maskGroup;

		List<long[][]> gridBlocks = Grid.create(
				dimensions,
				blockSize );

		n5.createDataset(
				maskGroupFinal,
				dimensions,
				blockSize,
				DataType.UINT8,
				new GzipCompression( 1 ) );

		final int oneHundreth = gridBlocks.size() / 100 + 1;

		System.out.println( "processing " + gridBlocks.size() + " blocks using " + nThreads + " threads.");

		for ( int threadNum = 0; threadNum < nThreads ; ++threadNum )
		{
			tasks.add( () ->
			{
				final ArrayImg<UnsignedByteType, ByteArray> source = ArrayImgs.unsignedBytes( Util.int2long( blockSize ) );
				final N5Writer n5Writer = new N5FSWriter(n5Path);

				final RealRandomAccess<DoubleType> rMin = minField.realRandomAccess();
				final RealRandomAccess<DoubleType> rMax = maxField.realRandomAccess();

				final long[] pos = new long[ source.numDimensions() ];

				for ( int g = nextBlock.getAndIncrement(); g < gridBlocks.size(); g = nextBlock.getAndIncrement() )
				{
					final long[][] gridBlock = gridBlocks.get( g );

					if ( g % oneHundreth == 0 )
						System.out.println( new Date(System.currentTimeMillis()) + ": processing block " + g + "/" + gridBlocks.size() + " - " + (100.0*(double)g/gridBlocks.size()) + "%");

					//if ( gridBlock[0][ 0 ] == 6400 && gridBlock[0][ 1 ] == 1280 && gridBlock[0][ 2 ] == 4352 ) {
					//System.out.println( "displaying " + Util.printCoordinates( gridBlock[ 0]) + ", " + Util.printCoordinates( gridBlock[ 1]) + ", " + Util.printCoordinates( gridBlock[ 2]));

					// move our img to the offset of this block
					final RandomAccessibleInterval<UnsignedByteType> block = Views.translate( source, gridBlock[0] );

					final Cursor<UnsignedByteType> c = Views.iterable( block ).localizingCursor();

					while( c.hasNext() )
					{
						final UnsignedByteType v = c.next();

						c.localize( pos );

						rMin.setPosition( pos[ 0 ], 0 );
						rMin.setPosition( pos[ 2 ], 1 );
						final double min = rMin.get().get();

						if ( pos[ 1 ] >= min )
						{
							rMax.setPosition( pos[ 0 ], 0 );
							rMax.setPosition( pos[ 2 ], 1 );
							final double max = rMax.get().get();
	
							if ( pos[ 1 ] <= max )
								v.setOne();
						}
					}

					//ImageJFunctions.show( source ).setDisplayRange(0, 1);
					N5Utils.saveBlock(source, n5Writer, maskGroupFinal, gridBlock[2]);
				}

				n5Writer.close();

				return null;
			} );
		}

		try
		{
			final List< Future< Void > > futures = service.invokeAll( tasks );
			for ( final Future< Void > future : futures )
				future.get();
		}
		catch ( final InterruptedException | ExecutionException e )
		{
			e.printStackTrace();
			n5.close();
			throw new RuntimeException( e );
		}

		service.shutdown();
		n5.close();

		System.out.println("Done.");

		/*
		final ArrayImg<UnsignedByteType, ByteArray> slice = ArrayImgs.unsignedBytes( new long[]{ dimensions[0], dimensions[1] } );
		final Cursor<UnsignedByteType> c = slice.localizingCursor();

		final RealRandomAccess<DoubleType> rMin = minField.realRandomAccess();
		final RealRandomAccess<DoubleType> rMax = maxField.realRandomAccess();

		final long z = dimensions[ 2 ] / 10;

		while( c.hasNext() )
		{
			final UnsignedByteType v = c.next();

			rMin.setPosition( c.getLongPosition( 0 ), 0 );
			rMin.setPosition( z, 1 );
			final double min = rMin.get().get();

			rMax.setPosition( c.getLongPosition( 0 ), 0 );
			rMax.setPosition( z, 1 );
			final double max = rMax.get().get();

			if ( c.getLongPosition( 1 ) >= min && c.getLongPosition( 1 ) <= max )
				v.setOne();
		}

		ImageJFunctions.show( slice ).setDisplayRange(0, 1);;
		*/
		return null;
	}
}
