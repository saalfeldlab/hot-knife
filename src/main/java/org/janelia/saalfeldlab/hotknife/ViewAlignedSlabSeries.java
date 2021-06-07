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
import java.util.concurrent.ExecutionException;

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
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ClippedTransitionRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ViewAlignedSlabSeries {

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

				//System.out.println( "Warning: adding custom transformation");
				final AffineTransform3D rigid = new AffineTransform3D();
				rigid.translate(
						-(cropInterval.dimension(0)/2 + cropInterval.min( 0 )),
						-(cropInterval.dimension(1)/2 + cropInterval.min( 1 )),
						0 );
				rigid.rotate( 2, Math.toRadians( -18 ) );
				rigid.translate(
						(cropInterval.dimension(0)/2 + cropInterval.min( 0 )),
						(cropInterval.dimension(1)/2 + cropInterval.min( 1 )),
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
