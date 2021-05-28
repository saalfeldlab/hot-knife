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

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.process.ImageConverter;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ViewAlignment {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "-i", aliases = {"--n5Group"}, required = false, usage = "N5 group, e.g. /align-0")
		private List<String> groups = new ArrayList<>();

		@Option(name = "--scaleIndex", required = true, usage = "scale index for visualization, e.g. 4 (means scale = 1.0 / 2^4)")
		private int transformScaleIndex = 0;

		@Option(name = "--noVirtual", required = false, usage = "makes a physical copy of each transformed slab surface during startup (instead of virtual rendering)")
		private boolean noVirtual = false;

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

		/**
		 * @return the scaleIndex
		 */
		public int getScaleIndex() {

			return transformScaleIndex;
		}

		/**
		 * @return the groups
		 */
		public List<String> getGroups() {

			return groups;
		}
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

//		new ImageJ();

		final N5Reader n5 = new N5FSReader(options.getN5Path());

		final int showScaleIndex = options.getScaleIndex();
		final double showScale = 1.0 / (1 << showScaleIndex);

		Bdv bdv = null;

		final int numProc = Math.max( 1, Runtime.getRuntime().availableProcessors() * 2 );
		final SharedQueue queue = new SharedQueue( numProc );
		final CacheHints cacheHints = new CacheHints( LoadingStrategy.VOLATILE, 0, true );

		if ( options.noVirtual )
			new ImageJ();

		for (final String group : options.getGroups()) {

			final String[] datasetNames = n5.getAttribute(group, "datasets", String[].class);
			final String[] transformDatasetNames = n5.getAttribute(group, "transforms", String[].class);
			final double[] boundsMin = n5.getAttribute(group, "boundsMin", double[].class);
			final double[] boundsMax = n5.getAttribute(group, "boundsMax", double[].class);

			final RealTransform[] realTransforms = new RealTransform[datasetNames.length];
			for (int i = 0; i < datasetNames.length; ++i) {
				System.out.println( "z=" + i + " >>> " + transformDatasetNames[i] );
				realTransforms[i] = Transform.loadScaledTransform(
						n5,
						group + "/" + transformDatasetNames[i]);

				/*
				if ( datasetNames[ i ].contains( "Sec27") || datasetNames[ i ].contains( "Sec28") || datasetNames[ i ].contains( "Sec29") || datasetNames[ i ].contains( "Sec30") || 
						datasetNames[ i ].contains( "Sec33") || datasetNames[ i ].contains( "Sec34") || datasetNames[ i ].contains( "Sec35") || datasetNames[ i ].contains( "Sec36") )
				{
					//System.out.println( datasetNames[ i ]);
					datasetNames[ i ] = datasetNames[ i ].substring( 0, datasetNames[ i ].indexOf( "Sec") + 5 ) + "_pass3/" + datasetNames[ i ].substring( datasetNames[ i ].indexOf( "Sec") + 6, datasetNames[ i ].length() );
					//System.out.println( datasetNames[ i ]);
				}*/

			}

			final RandomAccessibleInterval<FloatType> stack = Transform.createTransformedStack(
					options.getN5Path(),
					Arrays.asList(datasetNames),
					showScaleIndex,
					Arrays.asList(realTransforms),
					new FinalInterval(
							Grid.floorScaled(boundsMin, showScale),
							Grid.ceilScaled(boundsMax, showScale)));

			if ( options.noVirtual )
			{
				System.out.println( "copying entire stack ... " );
				long t = System.currentTimeMillis();
				final long[] min = new long[ stack.numDimensions() ];
				stack.min( min );

				final RandomAccessibleInterval<FloatType> copy = Views.translate( new CellImgFactory<>( new FloatType(), (int)stack.dimension( 2 ) ).create( stack.dimensionsAsLongArray() ), min );
				final ExecutorService service = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
				Util.copy(stack, copy, service);
				service.shutdown();

				System.out.println( "took " + (( System.currentTimeMillis() - t )/1000) + " secs.");

				//BdvFunctions.show( copy, "transformed", new BdvOptions().addTo( bdv ).numRenderingThreads(Runtime.getRuntime().availableProcessors() ));
				ImagePlus imp = ImageJFunctions.wrapFloat( copy, "group " + group );
				new ImageConverter(imp).convertToGray8();
				imp.setDimensions( 1, imp.getStackSize(), 1 );
				imp.show();
				//ImageJFunctions.show( copy );
			}
			else
			{
				bdv = Show.transformedStack(
						(RandomAccessibleInterval)VolatileViews.wrapAsVolatile(
								Show.wrapAsVolatileCachedCellImg(stack, new int[]{256, 256, 26}),
								queue,
								cacheHints),
						bdv);
			}

//			ImageJFunctions.show(stack, group);
		}
	}
}
