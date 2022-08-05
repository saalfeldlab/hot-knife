package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

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
		CommandLine.call(new SparkComputeCostBrainVNC(), args);
	}

	public static void getFullResMipMaps()
	{
		
	}

	@SuppressWarnings("unchecked")
	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException
	{
		final N5Reader n5 = new N5FSReader(n5Path);

		// crop interval (manually specified)
		final Interval crop = new FinalInterval( new long[] {47204, 46557, 40906}, new long[] {55779, 59038, 55164} );
		
		/*
		 * raw data
		 */
		final int numScales = n5.list(rawGroup).length;
		final double[][] scales = new double[numScales][];
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = new RandomAccessibleInterval[numScales];
		for (int s = 0; s < numScales; ++s) {

			final String mipmapName = rawGroup + "/s" + s;
			mipmaps[s] = (RandomAccessibleInterval<UnsignedByteType>)N5Utils.openVolatile(n5, mipmapName);
			double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
			if (scale == null)
				scale = new double[] {1, 1, 1};

			scales[s] = scale;

			System.out.println( "scale: "+ Util.printCoordinates( scale ) );
			System.out.println( "dimensions: "+ Util.printInterval( mipmaps[ s ]));

			if ( crop != null )
			{
				final AffineTransform3D mipmapTransform = new AffineTransform3D();
				mipmapTransform.set(
						scale[ 0 ], 0, 0, 0.5 * ( scale[ 0 ] - 1 ),
						0, scale[ 1 ], 0, 0.5 * ( scale[ 1 ] - 1 ),
						0, 0, scale[ 2 ], 0.5 * ( scale[ 2 ] - 1 ) );
				//mipmapTransform.preConcatenate(sourceTransform);
				//mipmapTransforms[ s ] = mipmapTransform;

				double[] min = new double[] {crop.min(0), crop.min(1), crop.min(2)};
				double[] max = new double[] {crop.max(0), crop.max(1), crop.max(2)};

				mipmapTransform.applyInverse(min, min);
				mipmapTransform.applyInverse(max, max);

				System.out.println( "mipmap transform: "+ mipmapTransform );
				System.out.println( "updated interval: "+ Util.printCoordinates( min ) + " >>> " + Util.printCoordinates( max ) );

				if ( scale[ 0 ] == 32 )
				{
					final RealInterval bb = new FinalRealInterval(min, max);
					IntervalView<UnsignedByteType> cropped = Views.interval( mipmaps[ s ], Intervals.smallestContainingInterval( bb ) );
					new ImageJ();
					ImageJFunctions.show( cropped );
				}
			}
			System.out.println();
		}

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

		Result box = BdvFunctions.selectBox(
				bdv,
				new AffineTransform3D(),
				new FinalInterval( new long[] {47204, 46557, 40906}, new long[] {55779, 59038, 55164} ),
				new FinalInterval( mipmaps[ 0 ] ) );

		System.out.println( Util.printInterval( box.getInterval() ) );
		// [47204, 46557, 40906] -> [55779, 59038, 55164], dimensions (8576, 12482, 14259)

		return null;
	}
}
