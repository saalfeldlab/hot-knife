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

import bdv.TransformEventHandler3D;
import bdv.tools.transformation.TransformedSource;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;

import bdv.viewer.SynchronizedViewerState;
import bdv.viewer.ViewerPanel;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.basictypeaccess.AccessFlags;
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

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		/*
		--n5Path '/nrs/hess/render/export/hess.n5/'
		-j '/surface-align/run_20230329_100000/pass00'
		-i '/flat/cut_030_slab_026/raw'
		--invert
		-t 20
		-b -21
		-i '/flat/cut_031_slab_006/raw'
		-t 20
		-b -21
		-i '/flat/cut_032_slab_013/raw'
		-t 20
		-b -21
		-i '/flat/cut_033_slab_033/raw'
		-t 20
		-b -21
		-i '/flat/cut_034_slab_020/raw'
		-t 20
		-b -21
		-i '/flat/cut_035_slab_001/raw'
		-t 20
		-b -21
		-i '/flat/cut_036_slab_045/raw'
		-t 20
		-b -21
		*/

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

		@Option(name = "--invert", required = false, usage = "invert intensities")
		private boolean invert;

		@Option(name = "--zoom", usage = "optionally zoom starting view in or out")
		private int zoom = 0;

		@Option(name = "--multiSem", usage = "MultiSem datasets have a different scaling in z than xy")
		private boolean multiSem = false;

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

		/**
		 * @return whether to invert intensities
		 */
		public boolean invert() {
			return invert;
		}

		public boolean multiSem() {
			return multiSem;
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
				options.invert(),
				options.multiSem(),
				options.zoom,
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
			final boolean invert,
			final boolean multiSem,
			final int zoom,
			final boolean useVolatile) throws IOException {

		final N5Reader n5 = new N5FSReader(n5Path);

		final String[] transformDatasetNames = n5.getAttribute(group, "transforms", String[].class);

		final int expectedNumberOfTransforms = datasetNames.size() * 2;
		if (transformDatasetNames.length != expectedNumberOfTransforms) {
			throw new IOException("Read " + transformDatasetNames.length + " transforms from " + n5Path + group +
								  "/attributes.json, but expected to find " + expectedNumberOfTransforms +
								  " transforms because " + datasetNames.size() + " datasets were specified.  " +
								  "Dataset names are: " + datasetNames + ".  " +
								  "Transform names are: " + Arrays.toString(transformDatasetNames));
		}

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

			System.out.println( "dataset: " + datasetName );
			System.out.println( "Dimensions: " + Util.printCoordinates( dimensions ) );
			System.out.println( "Interval: " + Util.printInterval( cropInterval ) );

			final int numScales = n5.list(datasetName).length;

			@SuppressWarnings("unchecked")
			final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = (RandomAccessibleInterval<UnsignedByteType>[])new RandomAccessibleInterval[numScales];
			final double[][] scales = new double[numScales][];

			for (int s = 0; s < numScales; ++s) {

				final int scale = 1 << s;
				final int scaleZ = multiSem ? 1 : scale;

				final double inverseScale = 1.0 / scale;
				final double inverseScaleZ = 1.0 / scaleZ;

				RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5, datasetName + "/s" + s);
				final RandomAccessibleInterval<UnsignedByteType> source;

				if ( invert )
				{
					sourceRaw = Converters.convertRAI(sourceRaw, (in,out) -> out.set( 255 - in.get() ), new UnsignedByteType() );
				}

				if ( normalizeContrast )
				{
					final int blockRadius = (int)Math.round(511 * inverseScale); //1023
	
					final ImageJStackOp<UnsignedByteType> cllcn =
							new ImageJStackOp<>(
									Views.extendZero(sourceRaw),
									(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 10, 0.5f, true, true, true),
									blockRadius,
									0,
									255,
									true );
	
					source = Lazy.process(
							sourceRaw,
							new int[] {256, 256, 40},
							new UnsignedByteType(),
							AccessFlags.setOf(AccessFlags.VOLATILE),
							cllcn);
				}
				else
				{
					source = sourceRaw;
				}

				final RealTransformSequence transformSequence = new RealTransformSequence();
				final Scale3D scale3D = new Scale3D(inverseScale, inverseScale, inverseScaleZ);

				transformSequence.add(transition);
				transformSequence.add(scale3D);

				final RandomAccessibleInterval<UnsignedByteType> transformedSource = Transform.createTransformedInterval(
								source,
								cropInterval,
								transformSequence,
								new UnsignedByteType(0));

				final SubsampleIntervalView<UnsignedByteType> subsampledTransformedSource = Views.subsample(transformedSource, scale, scale, scaleZ);
				final RandomAccessibleInterval<UnsignedByteType> cachedSource = Show.wrapAsVolatileCachedCellImg(subsampledTransformedSource, new int[]{256, 256, 40});

				mipmaps[s] = cachedSource;
				scales[s] = new double[]{scale, scale, scaleZ};
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

		if (zoom != 0) {
			zoomViewer(bdv, zoom);
		}

		return bdv;
	}

	private static void zoomViewer(final BdvStackSource<?> bdv,
								  final int zoom) {
		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final SynchronizedViewerState state = viewerPanel.state();
		final AffineTransform3D viewerTransform = state.getViewerTransform();
		// center shift
		final double cX = 0.5 * viewerPanel.getWidth();
		final double cY = 0.5 * viewerPanel.getHeight();
		viewerTransform.set(viewerTransform.get( 0, 3 ) - cX, 0, 3);
		viewerTransform.set(viewerTransform.get( 1, 3 ) - cY, 1, 3);
		// scale ( see https://github.com/bigdataviewer/bigdataviewer-core/blob/master/src/main/java/bdv/TransformEventHandler3D.java#L263 )
		final double speed = 1.0;
		final double dscale = 1.0 + 0.1 * speed;
		final double scaleFactor = (zoom * 2) * dscale; // don't understand why I need to multiply by 2 here
		viewerTransform.scale(scaleFactor);
		// center un-shift
		viewerTransform.set(viewerTransform.get( 0, 3 ) + cX, 0, 3);
		viewerTransform.set(viewerTransform.get( 1, 3 ) + cY, 1, 3);
		state.setViewerTransform(viewerTransform);
	}
}
