package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;
import org.janelia.saalfeldlab.ispim.imglib2.VirtualRasterDataLoader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;

import com.google.gson.GsonBuilder;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import loci.formats.FormatException;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.volatiles.VolatileUnsignedShortType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class RenderFullStack implements Callable<Void>, Serializable 
{
	private static final long serialVersionUID = 7514909638900822732L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--channel", required = true, description = "Channel key, e.g. Ch488+561+647nm")
	private String channel = null;

	@Option(names = "--cam", required = true, description = "Cam key, e.g. cam1")
	private String cam = null;

	public static RandomAccessibleInterval< VolatileUnsignedShortType > loadStack(
			final String n5Path,
			final String id,
			final String channel,
			final String cam,
			final SharedQueue queue ) throws IOException, FormatException
	{
		final N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id );

		// they stay constant as we keep loading the same stack
		final Interval inputBounds = ViewISPIMStack.openStackSize( n5data.stacks.get( channel ).get( cam ), 0 );

		final RealInterval realBounds2d = ViewISPIMStack.estimateStackBounds(
				inputBounds,
				n5data.stacks.get( channel ).get( cam ),
				n5data.camTransforms.get( channel ).get( cam ).inverse(),
				n5data.alignments.get( channel ),
				0,
				n5data.lastSliceIndex,
				true );

		final Interval bounds2d = Intervals.smallestContainingInterval( realBounds2d );

		final long[] min = new long[] { bounds2d.min( 0 ), bounds2d.min( 1 ), 0 };
		final long[] max = new long[] { bounds2d.max( 0 ), bounds2d.max( 1 ), n5data.lastSliceIndex };

		final Interval interval = new FinalInterval( min, max );

		System.out.println( id + "," + channel + "," + cam + ": interval=" + interval );
		System.out.println( id + "," + channel + "," + cam + ": affine3d=" + n5data.affine3D.get( channel ) );

		final VirtualRasterDataLoader< UnsignedShortType > loader =
				new VirtualRasterDataLoader<UnsignedShortType>(
						inputBounds,
						n5data.stacks.get( channel ).get( cam ),
						n5data.alignments.get( channel ),
						0,
						n5data.lastSliceIndex,
						n5data.camTransforms.get( channel ).get( cam ),
						min,
						new UnsignedShortType() );

		final int blockSizeXY = (int)Math.min( interval.dimension( 0 ), interval.dimension( 1 ) );
		final int[] blockSize = new int[] { blockSizeXY, blockSizeXY, 40 };
		System.out.println( id + "," + channel + "," + cam + ": blocksize=" + Util.printCoordinates( blockSize ) );

		final RandomAccessibleInterval<UnsignedShortType> cachedImg =
				Views.translate(
						Lazy.process(
								interval,
								blockSize,
								new UnsignedShortType(),
								AccessFlags.setOf( AccessFlags.VOLATILE ),
								loader ),
						min );

		final RandomAccessibleInterval< VolatileUnsignedShortType > volatileRA =
				VolatileViews.wrapAsVolatile( cachedImg, queue );

		return volatileRA;
	}

	@Override
	public Void call() throws Exception {

		final N5Writer n5 = new N5FSWriter(
				n5Path,
				new GsonBuilder().
					registerTypeAdapter(
						AffineTransform3D.class,
						new AffineTransform3DAdapter()).
					registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final List<String> allIds = SparkPaiwiseAlignChannelsGeoAll.getIds(n5);
		Collections.sort( allIds );

		final int numFetchThreads = Runtime.getRuntime().availableProcessors() / 2;
		System.out.println("building SharedQueue with " + numFetchThreads + " FetcherThreads" );
		final SharedQueue queue = new SharedQueue(numFetchThreads, 1 );

		RandomAccessibleInterval< VolatileUnsignedShortType > stack = loadStack( n5Path, allIds.get( 0 ), channel, cam, queue );

		BdvOptions options = BdvOptions.options().numRenderingThreads( numFetchThreads );
		BdvStackSource<?> bdv = BdvFunctions.show( Views.extendValue( stack, 0 ), new FinalInterval( stack ), allIds.get( 0 ) + "," + channel + "," + cam );
		bdv.setDisplayRange( 0, 1000 );
		bdv.setColor( new ARGBType( ARGBType.rgba(0, 255, 0, 0)));

		final N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, allIds.get( 0 ) );
		bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channel, cam, n5data.stacks.get( channel ).get( cam ), n5data.alignments.get( channel ), n5data.camTransforms.get( channel ).get( cam ), new AffineTransform3D(), 0, n5data.lastSliceIndex );
		bdv.setDisplayRange( 0, 1000 );
		bdv.setColor( new ARGBType( ARGBType.rgba(255, 0, 255, 0)));

		SimpleMultiThreading.threadHaltUnClean();
		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new RenderFullStack()).execute(args));
	}

}
