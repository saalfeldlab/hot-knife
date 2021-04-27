/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.hotknife;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import ij.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;

import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 *
 *
 * @author Kyle Harrington &lt;janelia@kyleharrington.com&gt;
 */
public class ViewCost {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "-i", aliases = {"--n5Group"}, required = true, usage = "N5 group, e.g. /align-0")
		private String rawGroup = null;

		@Option(name = "--cost", required = true, usage = "N5 group for cost function, e.g. /cost-0")
		private String costGroup = null;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);

				parsedSuccessfully = true;

			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		/**
		 * @return the n5Path
		 */
		public String getN5Path() {

			return n5Path;
		}

		public String getRawGroup() {
			return rawGroup;
		}

		public String getCostGroup() {
			return costGroup;
		}
	}

	public static final void main(String... args) throws IOException, InterruptedException, ExecutionException {

		//args = new String[]{"--n5Path","/home/kharrington/Data/portableVNC.n5","-i","/z33/v2_acquire_1_7270_sp2___20200804_184632_s0_crop001/","--cost",""};


		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		new ImageJ();

		final N5Reader n5 = new N5FSReader(options.getN5Path());

		Bdv bdv = null;

		final int numProc = Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 );
		final SharedQueue queue = new SharedQueue( numProc );
		final CacheHints cacheHints = new CacheHints( LoadingStrategy.VOLATILE, 0, true );

		RandomAccessibleInterval<UnsignedByteType> raw = Views.zeroMin(N5Utils.open(n5, options.getRawGroup(), new UnsignedByteType()));
		RandomAccessibleInterval<UnsignedByteType> cost = Views.zeroMin(N5Utils.open(n5, options.getCostGroup(), new UnsignedByteType()));
		long[] rawDownsampleFactors = n5.getAttribute(options.getRawGroup(), "downsamplingFactors", long[].class);
		long[] costDownsampleFactors = n5.getAttribute(options.getCostGroup(), "downsamplingFactors", long[].class);

		// not stored in s0
		if ( rawDownsampleFactors == null )
			rawDownsampleFactors = new long[] { 1, 1, 1 };

		//ImageJFunctions.show( Views.hyperSlice( raw, 2, 10000 ));
		//ImageJFunctions.show( Views.hyperSlice( cost, 2, 10000/6 ));
		//ImageJFunctions.show( cost );
		//SimpleMultiThreading.threadHaltUnClean();
		System.out.println( Util.printCoordinates( costDownsampleFactors ));
		RandomAccessibleInterval<UnsignedByteType> costInvert = Converters.convert(cost,
											   (a,b) -> b.set(255 - a.get()),
											   new UnsignedByteType());

		System.out.println( "Raw downsampling: " + Util.printCoordinates( rawDownsampleFactors ) );
		System.out.println( "Cost downsampling: " + Util.printCoordinates( costDownsampleFactors ) );
		System.out.println("intervals: " + raw + " " + cost );

		//RandomAccessible<UnsignedByteType> rawDownsample = Views.subsample(Views.extendZero(raw), costDownsampleFactors);

		BdvOptions bdvOpt = Bdv.options().numRenderingThreads( Runtime.getRuntime().availableProcessors() / 2 ).sourceTransform( rawDownsampleFactors[ 0 ], rawDownsampleFactors[ 1 ], rawDownsampleFactors[ 2 ] );
		//bdv = BdvFunctions.show(rawDownsample, cost, "input", bdvOpt);
		bdv = BdvFunctions.show(raw, "input", bdvOpt);

		bdvOpt = Bdv.options().sourceTransform( costDownsampleFactors[ 0 ], costDownsampleFactors[ 1 ], costDownsampleFactors[ 2 ] );//6, 1, 6 );
		bdv = BdvFunctions.show(costInvert, "cost", bdvOpt.addTo(bdv));
	}
}
