package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.tools.boundingbox.TransformedBoxSelectionDialog.Result;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import ij.ImageJ;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class SparkComputeCostBrainVNC  implements Callable<Void>
{
	@Option(names = {"-i", "--n5Path"}, required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"-d", "--n5Raw"}, required = true, description = "N5 input group for raw, e.g. /raw")
	private String rawGroup = null;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		//CommandLine.call(new SparkComputeCostBrainVNC(), args);
		new CommandLine( new SparkComputeCostBrainVNC() ).execute( args );
	}

	/**
	 * Code snippet used to identify the bounding box in the brain volume
	 *
	 * @param n5
	 * @param rawGroup
	 * @param crop
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
	public static Interval defineCrop( final N5Reader n5, final String rawGroup, final Interval crop ) throws IOException
	{
		final int numScales = n5.list(rawGroup).length;
		final double[][] scales = new double[numScales][];
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = new RandomAccessibleInterval[numScales];

		for (int s = 0; s < numScales; ++s) {
			final String mipmapName = rawGroup + "/s" + s;
			mipmaps[s] = (RandomAccessibleInterval<UnsignedByteType>)N5Utils.openVolatile(n5, mipmapName);
			final double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
			scales[s] = (scale == null) ? new double[] {1, 1, 1} : scale;
		}

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
				new RandomAccessibleIntervalMipmapSource<>(
						mipmaps,
						new UnsignedByteType(),
						scales,
						new FinalVoxelDimensions("px", 1.0, 1.0, 1.0),
						new AffineTransform3D(),
						rawGroup);

		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.min(12, Math.max(1, numProc - 2)));

		final BdvOptions options =
				BdvOptions.options()
				.screenScales(new double[] {0.5})
				.numRenderingThreads(Runtime.getRuntime().availableProcessors());

		final BdvStackSource<?> bdv = Show.mipmapSource(mipmapSource.asVolatile(queue), null, options);

		final Result box = BdvFunctions.selectBox(
				bdv,
				new AffineTransform3D(),
				crop == null ? new FinalInterval( mipmaps[ 0 ] ) : new FinalInterval( crop ),
				new FinalInterval( mipmaps[ 0 ] ) );

		System.out.println( Util.printInterval( box.getInterval() ) );
		// [47204, 46557, 40906] -> [55779, 59038, 55164], dimensions (8576, 12482, 14259)

		return box.getInterval();
	}

	public static void computeCost( final RandomAccessibleInterval<UnsignedByteType> cropped ) throws Exception
	{
		//new ImageJ();
		//ImageJFunctions.show( cropped );

		//ExecutorService executorService =  Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 2);
		ExecutorService executorService =  Executors.newFixedThreadPool(1 );
		//ExecutorService executorService =  Executors.newCachedThreadPool();

		final boolean filter = false;
		final boolean gauss = true;

		final long[] zcorrSize = new long[3];
		cropped.dimensions( zcorrSize );
		final int[] costSteps = new int[]{1,1,1};
		final Long[] gridCoord = new Long[] { 0l, 0l };
		final int costAxis=2;

		final int bandSize=50;
		final float maxSlope=0.04f;
		final float minSlope = 0;
		final float slopeCorrBandFactor=5.5f;
		final int slopeCorrXRange=20;
		final int minGradient = 20;
		final int startThresh = 50;
		final int kernelSize = 5;

		SparkComputeCost.processColumnAlongAxis(
				cropped,
				new FinalInterval( cropped ), 
				filter,
				gauss,
				//costBlockSize,
				//zcorrBlockSize,
				zcorrSize,
				costSteps,
				costAxis,
				gridCoord,
				executorService,
				bandSize,
				minGradient,
				slopeCorrXRange,
				slopeCorrBandFactor,
				maxSlope,
				minSlope,
				startThresh,
				kernelSize);
	}

	@SuppressWarnings("unchecked")
	@Override
	public final Void call() throws Exception
	{
		final N5Reader n5 = new N5FSReader(n5Path);

		// crop interval (manually specified)
		final Interval crop = new FinalInterval( new long[] {47204, 46557, 42756+5000}, new long[] {55779-4000, 59038, 53664-5000} );

		// show crop
		// defineCrop( n5, rawGroup, crop );

		/*
		 * raw data
		 */
		final int numScales = n5.list(rawGroup).length;
		final double[][] scales = new double[numScales][];
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = new RandomAccessibleInterval[numScales];
		for (int s = 0; s < numScales; ++s) {

			final String mipmapName = rawGroup + "/s" + s;
			mipmaps[s] = (RandomAccessibleInterval<UnsignedByteType>)N5Utils.open(n5, mipmapName);
			double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
			scales[s] = (scale == null) ? new double[] {1, 1, 1} : scale;

			System.out.println( "scale: "+ Util.printCoordinates( scales[s] ) );
			System.out.println( "dimensions: "+ Util.printInterval( mipmaps[ s ]));

			if ( crop != null )
			{
				final AffineTransform3D mipmapTransform = new AffineTransform3D();
				mipmapTransform.set(
						scales[s][ 0 ], 0, 0, 0.5 * ( scales[s][ 0 ] - 1 ),
						0, scales[s][ 1 ], 0, 0.5 * ( scales[s][ 1 ] - 1 ),
						0, 0, scales[s][ 2 ], 0.5 * ( scales[s][ 2 ] - 1 ) );
				//mipmapTransform.preConcatenate(sourceTransform);
				//mipmapTransforms[ s ] = mipmapTransform;

				double[] min = new double[] {crop.min(0), crop.min(1), crop.min(2)};
				double[] max = new double[] {crop.max(0), crop.max(1), crop.max(2)};

				mipmapTransform.applyInverse(min, min);
				mipmapTransform.applyInverse(max, max);

				System.out.println( "mipmap transform: "+ mipmapTransform );
				System.out.println( "updated interval: "+ Util.printCoordinates( min ) + " >>> " + Util.printCoordinates( max ) );

				if ( scales[s][ 0 ] == 8 )
				{
					final RealInterval bb = new FinalRealInterval(min, max);
					final RandomAccessibleInterval<UnsignedByteType> cropped =
							Views.interval( mipmaps[ s ], Intervals.smallestContainingInterval( bb ) );

					//new ImageJ();
					//ImageJFunctions.show( cropped );

					computeCost( Views.rotate( cropped, 1, 0 ) );
				}
			}
			System.out.println();
		}

		/*
		final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
				new RandomAccessibleIntervalMipmapSource<>(
						mipmaps,
						new UnsignedByteType(),
						scales,
						new FinalVoxelDimensions("px", 1.0, 1.0, 1.0),
						new AffineTransform3D(),
						rawGroup);

		final boolean useVolatile = true;

		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.min(12, Math.max(1, numProc - 2)));

		final Source<?> volatileMipmapSource;
		if (useVolatile)
			volatileMipmapSource = mipmapSource.asVolatile(queue);
		else
			volatileMipmapSource = mipmapSource;

		final BdvOptions options =
				BdvOptions.options()
				.screenScales(new double[] {0.5})
				.numRenderingThreads(Runtime.getRuntime().availableProcessors());

		final BdvStackSource<?> bdv = Show.mipmapSource(volatileMipmapSource, null, options);
		*/

		return null;
	}
}
