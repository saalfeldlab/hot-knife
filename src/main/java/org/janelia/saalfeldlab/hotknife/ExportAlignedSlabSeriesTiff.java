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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import ij.IJ;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.ClippedTransitionRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ExportAlignedSlabSeriesTiff {

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

		@Option(name = "-o", aliases = {"--outPath"}, required = true, usage = "Outout path, e.g. /nrs/flyem/data/tmp/Z0115-22.export")
		private String outPath = null;

		@Option(name = "--min", usage = "min crop coordinate, e.g. 15000,14000,6000")
		private String minString = null;

		@Option(name = "--size", usage = "crop size, e.g. 10000,10000,6000")
		private String sizeString = null;


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
		 * @return the group
		 */
		public String getOutputPath() {

			return outPath;
		}

		/**
		 * @return the group
		 */
		public long[] getCropMin() {

			return minString == null ? null : parseCSLongArray(minString);
		}

		/**
		 * @return the group
		 */
		public long[] getCropSize() {

			return sizeString == null ? null : parseCSLongArray(sizeString);
		}

	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		run(
				options.getN5Path(),
				options.getOutputPath(),
				options.getGroup(),
				options.getDatasets(),
				options.getTopOffsets(),
				options.getBotOffsets(),
				new FinalVoxelDimensions("px", new double[]{1, 1, 1}),
				options.getCropMin(),
				options.getCropSize(),
				true);
	}

	public static void run(
			final String n5Path,
			final String outPath,
			final String group,
			final List<String> datasetNames,
			final List<Long> topOffsets,
			final List<Long> botOffsets,
			final VoxelDimensions voxelDimensions,
			final long[] min,
			final long[] size,
			final boolean useVolatile) throws IOException {

		final N5Reader n5 = new N5FSReader(n5Path);

		Files.createDirectories(Paths.get(outPath));

		final String[] transformDatasetNames = n5.getAttribute(group, "transforms", String[].class);

		final double[] boundsMin = n5.getAttribute(group, "boundsMin", double[].class);
		final double[] boundsMax = n5.getAttribute(group, "boundsMax", double[].class);

		final long[] fMin = min == null ? Arrays.copyOf(Grid.floorScaled(boundsMin, 1), 3) : min;
		final long[] fMax;
		if (size == null) {
			fMax = Arrays.copyOf(Grid.ceilScaled(boundsMax, 1), 3);
			fMax[2] = Long.MAX_VALUE;
		} else {
			fMax = new long[3];
			Arrays.setAll(fMax, i -> fMin[i] + size[i] - 1);
		}

		long zOffset = 0;

		final ArrayList<RandomAccessibleInterval<UnsignedByteType>> sources = new ArrayList<>();

		for (int i = 0; i < datasetNames.size(); ++i) {

			final RealTransform top = Transform.loadScaledTransform(n5, group + "/" + transformDatasetNames[i * 2]);
			final RealTransform bot = Transform.loadScaledTransform(n5, group + "/" + transformDatasetNames[i * 2 + 1]);
			final RealTransform transition =
					new ClippedTransitionRealTransform(
							top,
							bot,
							0,
							botOffsets.get(i) - topOffsets.get(i));

			final Translation3D translation = new Translation3D(0, 0, -zOffset);

			final FinalInterval cropInterval = new FinalInterval(
					fMin,
					new long[] {fMax[0], fMax[1], Math.max(fMin[2] - 1, Math.min(botOffsets.get(i) + zOffset, fMax[2]))});

			final String datasetName = datasetNames.get(i);

			final int numScales = n5.list(datasetName).length;

			@SuppressWarnings("unchecked")
			final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = (RandomAccessibleInterval<UnsignedByteType>[])new RandomAccessibleInterval[numScales];
			final double[][] scales = new double[numScales][];

			final RandomAccessibleInterval<UnsignedByteType> source = N5Utils.open(n5, datasetName + "/s0");

			final RealTransformSequence transformSequence = new RealTransformSequence();
			transformSequence.add(translation);
			transformSequence.add(transition);

			final RandomAccessibleInterval<UnsignedByteType> transformedSource = Transform.createTransformedInterval(
				Views.permute(source, 1, 2),
				cropInterval,
				transformSequence,
				new UnsignedByteType(0));

			sources.add(transformedSource);

			zOffset += botOffsets.get(i) - topOffsets.get(i) + 1;
		}



		/* export sequentially */
		final FinalInterval sourceCropInterval = new FinalInterval(
				fMin,
				new long[] {fMax[0], fMax[1], Math.max(fMin[2] - 1, Math.min(zOffset - 1, fMax[2]))});

		final int zeroPadding = Long.toString(sourceCropInterval.max(2)).length();

		System.out.println(Util.printInterval(sourceCropInterval));

		long z = Math.max(sources.get(0).min(2), sourceCropInterval.min(2));
		for (final RandomAccessibleInterval<UnsignedByteType> source : sources) {

			final ExecutorService exec = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 1);
			final ArrayList<Future<?>> futures = new ArrayList<>();
			System.out.println(Util.printInterval(source));
			for (; z <= Math.min(source.max(2), sourceCropInterval.max(2)); ++z) {
				final long fz = z;
				futures.add(
						exec.submit(() -> {
							final IntervalView<UnsignedByteType> slice = Views.hyperSlice(source, 2, fz);
							final String fileName = String.format("%s/%0" + zeroPadding + "d.tif", outPath, fz);
							System.out.println(fileName);
							IJ.saveAsTiff(ImageJFunctions.wrap(slice, ""), fileName);
						}));
			}

			futures.forEach(f -> {
				try {
					f.get();
				} catch (final InterruptedException e) {
					e.printStackTrace();
				} catch (final ExecutionException e) {
					e.printStackTrace();
				}
			});

			exec.shutdown();
		}
	}
}
