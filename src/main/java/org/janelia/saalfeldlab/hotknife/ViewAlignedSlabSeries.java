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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.janelia.saalfeldlab.hotknife.ops.CLLCN;
import org.janelia.saalfeldlab.hotknife.ops.ImageJStackOp;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import bdv.tools.transformation.TransformedSource;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import ij.ImageJ;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.imageplus.ByteImagePlus;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ClippedTransitionRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Util;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ViewAlignedSlabSeries {

	/**
	--n5Path '/nrs/flyem/render/n5/Z0720_07m_BR'
	-j '/surface-align-BR/06-40'
	-i '/flat/Sec40/raw'
	-t 20
	-b -20
	-i '/flat/Sec39/raw'
	-t 20
	-b -20
	-i '/flat/Sec38/raw'
	-t 20
	-b -20
	-i '/flat/Sec37/raw'
	-t 20
	-b -20
	-i '/flat/Sec36/raw'
	-t 20
	-b -20
	-i '/flat/Sec35/raw'
	-t 20
	-b -20
	-i '/flat/Sec34/raw'
	-t 20
	-b -20
	-i '/flat/Sec33/raw'
	-t 20
	-b -20
	-i '/flat/Sec32/raw'
	-t 20
	-b -20
	-i '/flat/Sec31/raw'
	-t 20
	-b -20
	-i '/flat/Sec30/raw'
	-t 20
	-b -20
	-i '/flat/Sec29/raw'
	-t 20
	-b -20
	-i '/flat/Sec28/raw'
	-t 20
	-b -20
	-i '/flat/Sec27/raw'
	-t 20
	-b -20
	-i '/flat/Sec26/raw'
	-t 20
	-b -20
	-i '/flat/Sec25/raw'
	-t 20
	-b -20
	-i '/flat/Sec24/raw'
	-t 20
	-b -20
	-i '/flat/Sec23/raw'
	-t 20
	-b -20
	-i '/flat/Sec22/raw'
	-t 20
	-b -20
	-i '/flat/Sec21/raw'
	-t 20
	-b -20
	-i '/flat/Sec20/raw'
	-t 20
	-b -20
	-i '/flat/Sec19/raw'
	-t 20
	-b -20
	-i '/flat/Sec18/raw'
	-t 20
	-b -20
	-i '/flat/Sec17/raw'
	-t 20
	-b -20
	-i '/flat/Sec16/raw'
	-t 20
	-b -20
	-i '/flat/Sec15/raw'
	-t 20
	-b -20
	-i '/flat/Sec14/raw'
	-t 20
	-b -20
	-i '/flat/Sec13/raw'
	-t 20
	-b -20
	-i '/flat/Sec12/raw'
	-t 20
	-b -20
	-i '/flat/Sec11/raw'
	-t 20
	-b -20
	-i '/flat/Sec10/raw'
	-t 20
	-b -20
	-i '/flat/Sec09/raw'
	-t 20
	-b -20
	-i '/flat/Sec08/raw'
	-t 20
	-b -20
	-i '/flat/Sec07/raw'
	-t 20
	-b -20
	-i '/flat/Sec06/raw'
	-t 20
	-b -20
	 */

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "-i", aliases = {"--n5Dataset"}, required = true, usage = "N5 datasets, e.g. /nrs/flyem/data/tmp/Z0115-22.n5/slab-22/raw")
		private List<String> datasets = new ArrayList<>();

		@Option(name = "-t", aliases = {"--top"}, required = true, usage = "top slab face offset")
		private List<Long> topOffsets = new ArrayList<>();

		@Option(name = "-b", aliases = {"--bot"}, required = true, usage = "bottom slab face offset")
		private List<Long> botOffsets = new ArrayList<>();

		@Option(name = "-j", aliases = {"--n5Group"}, required = true, usage = "N5 group containing alignments, e.g. /nrs/flyem/data/tmp/Z0115-22.n5/align-6")
		private String n5GroupAlign;

		@Option(name = "-n", aliases = {"--normalizeContrast"}, required = false, usage = "optionally normalize contrast")
		private boolean normalizeContrast;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				parsedSuccessfully = datasets.size() == topOffsets.size() && datasets.size() == botOffsets.size();
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
		 * @return the datasets
		 */
		public List<String> getDatasets() {

			return datasets;
		}

		/**
		 * @return the top offsets
		 */
		public List<Long> getTopOffsets() {

			return topOffsets;
		}

		/**
		 * @return the bottom offsets (max)
		 */
		public List<Long> getBotOffsets() {

			return botOffsets;
		}

		/**
		 * @return the group
		 */
		public String getGroup() {

			return n5GroupAlign;
		}

		/**
		 * @return whether to normalize contrast
		 */
		public boolean normalizeContrast() {
			return normalizeContrast;
		}
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		run(
				options.getN5Path(),
				options.getGroup(),
				options.getDatasets(),
				options.getTopOffsets(),
				options.getBotOffsets(),
				new FinalVoxelDimensions("px", new double[]{1, 1, 1}),
				options.normalizeContrast(),
				true);
	}

	public static BdvStackSource<?> run(
			final String n5Path,
			final String group,
			final List<String> datasetNames,
			final List<Long> topOffsets,
			final List<Long> botOffsets,
			final VoxelDimensions voxelDimensions,
			final boolean normalizeContrast,
			final boolean useVolatile) throws IOException {

		final N5Reader n5 = new N5FSReader(n5Path);

		final String[] transformDatasetNames = n5.getAttribute(group, "transforms", String[].class);

		final double[] boundsMin = n5.getAttribute(group, "boundsMin", double[].class);
		final double[] boundsMax = n5.getAttribute(group, "boundsMax", double[].class);

		final long[] fMin = Grid.floorScaled(boundsMin, 1);
		final long[] fMax = Grid.ceilScaled(boundsMax, 1);

		BdvStackSource<?> bdv = null;
		final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

		long zOffset = 0;

		// create the preview for 32x downsampling
		// dataset: /flat/Sec06/raw
		// 32 [0, 0, 0] -> [1869, 2292, 44], dimensions (1870, 2293, 45)  2840
		new ImageJ();
		ByteImagePlus<UnsignedByteType> render = ImagePlusImgs.unsignedBytes(1870, 2293, 2840 + 45);
		render.getImagePlus().show();

		for (int i = 0; i < datasetNames.size(); ++i) {

			final String datasetName = datasetNames.get(i);
			final long[] dimensions = n5.getAttribute(datasetName + "/s0", "dimensions", long[].class);
			long botOffset = botOffsets.get(i);
			if (botOffset < 0) botOffset = dimensions[2] + botOffset - 1;


			final RealTransform top = Transform.loadScaledTransform(n5, group + "/" + transformDatasetNames[i * 2]);
			final RealTransform bot = Transform.loadScaledTransform(n5, group + "/" + transformDatasetNames[i * 2 + 1]);
			final RealTransform transition =
					new ClippedTransitionRealTransform(
							top,
							bot,
							topOffsets.get(i),
							botOffset);

			final FinalInterval cropInterval = new FinalInterval(
					new long[] {fMin[0], fMin[1], topOffsets.get(i)},
					new long[] {fMax[0], fMax[1], botOffset});

			System.out.println( "dataset: " + datasetName );
			System.out.println( "Dimensions: " + Util.printCoordinates( dimensions ) );
			System.out.println( "Render interval: " + Util.printInterval( cropInterval ) );

			final int numScales = n5.list(datasetName).length;

			@SuppressWarnings("unchecked")
			final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = (RandomAccessibleInterval<UnsignedByteType>[])new RandomAccessibleInterval[numScales];
			final double[][] scales = new double[numScales][];

			for (int s = 0; s < numScales; ++s) {

				final int scale = 1 << s;
				final double inverseScale = 1.0 / scale;

				final RandomAccessibleInterval<UnsignedByteType> source;

				if ( normalizeContrast )
				{
					final RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5, datasetName + "/s" + s);
	
					final int blockRadius = (int)Math.round(511 * inverseScale); //1023
	
					final ImageJStackOp<UnsignedByteType> cllcn =
							new ImageJStackOp<>(
									Views.extendZero(sourceRaw),
									(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 10, 0.5f, true, true, true),
									blockRadius,
									0,
									255);
	
					source = Lazy.process(
							sourceRaw,
							new int[] {128, 128, 16},
							new UnsignedByteType(),
							AccessFlags.setOf(AccessFlags.VOLATILE),
							cllcn);
				}
				else
				{
					source = N5Utils.open(n5, datasetName + "/s" + s);
				}

				final RealTransformSequence transformSequence = new RealTransformSequence();
				final Scale3D scale3D = new Scale3D(inverseScale, inverseScale, inverseScale);

				System.out.println( "Warning: adding custom transformation");

				// 39-26:
				// Interval: [-963, -650, 20] -> [45282, 54512, 2727], dimensions (46246, 55163, 2708)
				// 3d-affine: (1.0, 0.0, 0.0, -22160.0, 0.0, 1.0, 0.0, -26931.0, 0.0, 0.0, 1.0, 0.0)

				//final long rotationCenterX = (cropInterval.dimension(0)/2 + cropInterval.min( 0 ));
				//final long rotationCenterY = (cropInterval.dimension(1)/2 + cropInterval.min( 1 ));

				// rotate around the same point that 39-26 rotated around
				final long rotationCenterX = 22160 + 3328;
				final long rotationCenterY = 26931 + 6400;

				final AffineTransform3D rigid = new AffineTransform3D();
				rigid.translate(
						-rotationCenterX,
						-rotationCenterY,
						0 );
				//System.out.println( rigid );
				rigid.rotate( 2, Math.toRadians( -18 ) );
				rigid.translate(
						rotationCenterX,
						rotationCenterY,
						0 );

				transformSequence.add(rigid.inverse());

				transformSequence.add(transition);
				transformSequence.add(scale3D);

				final RandomAccessibleInterval<UnsignedByteType> transformedSource = Transform.createTransformedInterval(
								source,
								cropInterval,
								transformSequence,
								new UnsignedByteType(0));

				final SubsampleIntervalView<UnsignedByteType> subsampledTransformedSource = Views.subsample(transformedSource, scale);
				final RandomAccessibleInterval<UnsignedByteType> cachedSource = Show.wrapAsVolatileCachedCellImg(subsampledTransformedSource, new int[]{64, 64, 64});

				if ( scale == 32 /*&& (datasetName.contains( "Sec24") || datasetName.contains( "Sec23") || datasetName.contains( "Sec22") || datasetName.contains( "Sec21") )*/ )
				{
					System.out.println( "fusing " + datasetName );
					final long time = System.currentTimeMillis();
					// 39-26
					// last offset = 1099
					// last size = 84

					// 40-06
					// last offset = 2840
					// last size = 45
					System.out.println( scale  + " " + Util.printInterval( subsampledTransformedSource ) + "  " + (zOffset / scale) );

					// writing this below into render image
					RandomAccessibleInterval<UnsignedByteType> view = Views.translate(cachedSource, new long[] { 0, 0, (zOffset / scale) } );

					System.out.println( "input: " + Util.printInterval( view ) );
					System.out.println( "output: " + Util.printInterval( render ) );

					final int numThreads = Runtime.getRuntime().availableProcessors();
					final List< Callable< Void > > tasks = new ArrayList<>();
					final AtomicInteger nextBlock = new AtomicInteger( (int)view.min( 2 ));
					final ExecutorService service = Executors.newFixedThreadPool( numThreads );

					for ( int threadNum = 0; threadNum < numThreads; ++threadNum )
					{
						tasks.add( () ->
						{
							for ( int z = nextBlock.getAndIncrement(); z <= view.max( 2 ); z = nextBlock.getAndIncrement() )
							{
								final Cursor<UnsignedByteType> cIn =
										Views.flatIterable(
												Views.hyperSlice( view, 2, z ) ).cursor();
								final Cursor<UnsignedByteType> cOut =
										Views.flatIterable(
												Views.hyperSlice(
														Views.interval(
																render,
																new FinalInterval( view ) ), 2, z ) ).cursor();

								while ( cIn.hasNext() )
									cOut.next().set( cIn.next() );
							}
							return null;
						});
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
						throw new RuntimeException( e );
					}

					System.out.println( "done, took " + (System.currentTimeMillis() - time)/(1024*60) + " min." );
				}

				mipmaps[s] = cachedSource;
				scales[s] = new double[]{scale, scale, scale};
			}

			final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
					new RandomAccessibleIntervalMipmapSource<>(
							mipmaps,
							new UnsignedByteType(),
							scales,
							voxelDimensions,
							datasetName);

			final Source<?> volatileMipmapSource;
			if (useVolatile)
				volatileMipmapSource = mipmapSource.asVolatile(queue);
			else
				volatileMipmapSource = mipmapSource;

			final AffineTransform3D offset = new AffineTransform3D();
			offset.setTranslation(0, 0, zOffset);
			final TransformedSource<?> transformedVolatileMipmapSource = new TransformedSource<>(volatileMipmapSource);
			transformedVolatileMipmapSource.setFixedTransform(offset);

			bdv = Show.mipmapSource(transformedVolatileMipmapSource, bdv);

			zOffset += botOffset - topOffsets.get(i) + 1;
		}

		return bdv;
	}
}
