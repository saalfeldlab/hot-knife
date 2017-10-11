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

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Align;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Spark;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.CompressionType;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.array.ArrayLocalizingCursor;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.Scale2D;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkPairAlignFlow {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "-i", aliases = {"--n5GroupInput"}, required = true, usage = "N5 input group, e.g. /align-0")
		private final String inGroup = null;

		@Option(name = "-o", aliases = {"--n5GroupOutput"}, required = true, usage = "N5 output group, e.g. /align-1")
		private final String outGroup = null;

		@Option(name = "--scaleIndex", required = true, usage = "scale index for the output transform, e.g. 4 (means scale = 1.0 / 2^4)")
		private int transformScaleIndex = 0;

		@Option(name = "--stepSize", required = true, usage = "step size for grid")
		private int stepSize = 1024;

		@Option(name = "--maxEpsilon", required = true, usage = "residual threshold for filter in world pixels")
		private double maxFilterEpsilon = 50.0;

		@Option(name = "--sigma", required = false, usage = "smoothness filter of transform in scaled pixels")
		private double sigma = 30.0;

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
		public int getTransformScaleIndex() {

			return transformScaleIndex;
		}

		/**
		 * @return the outGroup
		 */
		public String getOutGroup() {

			return outGroup;
		}

		/**
		 * @return the inGroup
		 */
		public String getInGroup() {

			return inGroup;
		}

		/**
		 * @return the stepSize
		 */
		public int getStepSize() {

			return stepSize;
		}

		public double getMaxFilterEpsilon() {

			return maxFilterEpsilon;
		}

		public double getSigma() {

			return sigma;
		}
	}

	/**
	 * This is for 2D affine transforms only.
	 *
	 * @param affines
	 * @param n5Path
	 * @param priorTransformDatasetName
	 * @param priorTransformScaleIndex
	 * @param datasetBaseName
	 * @param boundsMin
	 * @param boundsMax
	 * @param stepSize
	 * @param transformScale
	 */
	public static JavaRDD<long[]> saveAccumulatedAffineGridCells(
			final JavaPairRDD<long[], double[]> affines,
			final String n5Path,
			final String priorTransformDatasetName,
			final String datasetBaseName,
			final double[] boundsMin,
			final double[] boundsMax,
			final int stepSize,
			final double transformScale) {

		final JavaRDD<long[]> gridCells = affines.map(
				t -> {
					final N5Writer n5 = N5.openFSWriter(n5Path);
					final RealTransform priorTransform = Transform.loadScaledTransform(
							n5,
							priorTransformDatasetName);
					final long[] gridOffset = Grid.gridCell(
							t._1(),
							Grid.floorScaled(boundsMin, transformScale),
							new int[]{stepSize, stepSize});
					final String datasetName = datasetBaseName + "." + gridOffset[0] + "-" + gridOffset[1];
					final RealTransformSequence transformSequence = new RealTransformSequence();
					if (t._2() != null) {
						final AffineTransform2D transform = new AffineTransform2D();
						transform.set(t._2());
						transformSequence.add(transform);
					}
					transformSequence.add(priorTransform);
					Transform.saveScaledTransformBlock(
							n5,
							datasetName,
							transformSequence,
							transformScale,
							boundsMin,
							boundsMax,
							gridOffset,
							new int[] {stepSize, stepSize});

					return t._1();
				});

		return gridCells;
	}


	/**
	 * This is fo 2D transformation fields only
	 *
	 * @param affines
	 * @param n5Path
	 * @param transformDatasetBaseName
	 * @param transformScale
	 * @param boundsMin
	 * @param boundsMax
	 * @param stepSize
	 * @throws IOException
	 */
	public static JavaRDD<long[]> composeOverlappingTransformGridCells(
			final JavaRDD<long[]> gridCells,
			final String n5Path,
			final String transformDatasetBaseName,
			final double transformScale,
			final double[] boundsMin,
			final double[] boundsMax,
			final int stepSize) throws IOException {

		final DatasetAttributes attributes = Transform.createScaledTransformDataset(
				N5.openFSWriter(n5Path),
				transformDatasetBaseName,
				boundsMin,
				boundsMax,
				transformScale,
				new int[] {stepSize, stepSize});

		final long[] dimensions = attributes.getDimensions();

		final JavaRDD<long[]> mappedGridCells = gridCells.map(
				cell -> {
					final N5Writer n5 = N5.openFSWriter(n5Path);
					final long[] gridOffset = Grid.gridCell(
							cell,
							Grid.floorScaled(boundsMin, transformScale),
							new int[]{stepSize, stepSize});

					final long[] intervalMin = new long[]{gridOffset[0] * stepSize, gridOffset[1] * stepSize, 0};
					final long[] intervalMax = new long[]{
							Math.min(dimensions[0], intervalMin[0] + stepSize) - 1,
							Math.min(dimensions[1], intervalMin[1] + stepSize) - 1,
							1};

					System.out.println(Arrays.toString(gridOffset) + " : " + Arrays.toString(intervalMin) + " > " + Arrays.toString(intervalMax) + " : " + transformDatasetBaseName + "." + Math.max(0, gridOffset[0] - 1) + "-" + Math.max(0, gridOffset[1] - 1));

					final IntervalView<DoubleType> t00 =
							Views.interval(
									N5Utils.<DoubleType>open(n5, transformDatasetBaseName + "." + Math.max(0, gridOffset[0] - 1) + "-" + Math.max(0, gridOffset[1] - 1)),
									intervalMin,
									intervalMax);
					final IntervalView<DoubleType> t01 =
							Views.interval(
									N5Utils.<DoubleType>open(n5, transformDatasetBaseName + "." + Math.max(0, gridOffset[0] - 1) + "-" + gridOffset[1]),
									intervalMin,
									intervalMax);
					final IntervalView<DoubleType> t10 =
							Views.interval(
									N5Utils.<DoubleType>open(n5, transformDatasetBaseName + "." + gridOffset[0] + "-" + Math.max(0, gridOffset[1] - 1)),
									intervalMin,
									intervalMax);
					final IntervalView<DoubleType> t11 =
							Views.interval(
									N5Utils.<DoubleType>open(n5, transformDatasetBaseName + "." + gridOffset[0] + "-" + gridOffset[1]),
									intervalMin,
									intervalMax);

					final Cursor<DoubleType> c00 = t00.cursor();
					final Cursor<DoubleType> c01 = t01.cursor();
					final Cursor<DoubleType> c10 = t10.cursor();
					final Cursor<DoubleType> c11 = t11.cursor();

					final ArrayImg<DoubleType, ?> tt = ArrayImgs.doubles(t00.dimension(0), t00.dimension(1), 2);
					final ArrayLocalizingCursor<DoubleType> c = tt.localizingCursor();

					while (c.hasNext()) {
						final DoubleType v = c.next();
						final DoubleType v00 = c00.next();
						final DoubleType v01 = c01.next();
						final DoubleType v10 = c10.next();
						final DoubleType v11 = c11.next();
						final double lambdaX = c.getDoublePosition(0) / stepSize;
						final double lambdaY = c.getDoublePosition(1) / stepSize;
						final double d0 = (v10.get() - v00.get()) * lambdaX + v00.get();
						final double d1 = (v11.get() - v01.get()) * lambdaX + v01.get();
						v.set((d1 - d0) * lambdaY + d0);
					}

					final DatasetAttributes targetAttributes = new DatasetAttributes(
							dimensions,
							new int[]{stepSize, stepSize, 2},
							DataType.FLOAT64,
							CompressionType.GZIP);
					N5Utils.saveBlock(tt, n5, transformDatasetBaseName, targetAttributes, Arrays.copyOf(gridOffset, 3));

					return cell;
				});

		return mappedGridCells;
	}


	public static void deleteGridCells(
			final JavaRDD<long[]> gridCells,
			final String n5Path,
			final String transformDatasetBaseName,
			final double transformScale,
			final double[] boundsMin,
			final double[] boundsMax,
			final int stepSize) {

		gridCells.foreach(
				cell -> {
					final N5Writer n5 = N5.openFSWriter(n5Path);
					final long[] gridOffset = Grid.gridCell(
							cell,
							Grid.floorScaled(boundsMin, transformScale),
							new int[]{stepSize, stepSize});
					n5.remove(transformDatasetBaseName + "." + gridOffset[0] + "-" + gridOffset[1]);
				});
	}


	static public JavaRDD<long[]> alignAndSaveAccumulatedGridCells(
			final JavaSparkContext sc,
			final String n5Path,
			final String datasetA,
			final String datasetB,
			final int scaleIndex,
			final String inTransformADataset,
			final String inTransformBDataset,
			final String outTransformDatasetBaseName,
			final double[] boundsMin,
			final double[] boundsMax,
			final long[] scaledFloorMin,
			final long[] scaledCeilMax,
			final int stepSize,
			final List<long[]> gridOffsets,
			final short radius,
			final double sigma,
			final int numIterations) throws IOException {

		final double scale = 1.0 / (1 << scaleIndex);
		final long gridCellWidth = stepSize * 2;

		final JavaRDD<long[]> offsets = sc.parallelize(gridOffsets);

		final JavaRDD<long[]> gridCells =
				offsets.map(offset -> {

					final N5Reader n5Reader = N5.openFSReader(n5Path);
					final RandomAccessibleInterval<FloatType> a = N5Utils.open(n5Reader, datasetA + "/s" + scaleIndex);
					final RandomAccessibleInterval<FloatType> b = N5Utils.open(n5Reader, datasetB + "/s" + scaleIndex);

					final RealTransform transformA = Transform.loadScaledTransform(
							n5Reader,
							inTransformADataset);
					final RealTransform transformB = Transform.loadScaledTransform(
							n5Reader,
							inTransformBDataset);

					final RandomAccessibleInterval<FloatType> transformedA = Transform.createTransformedInterval(
							a,
							new FinalInterval(scaledFloorMin, scaledCeilMax),
							Transform.createScaledRealTransform(transformA, scaleIndex),
							new FloatType(0));

					final RandomAccessibleInterval<FloatType> transformedB = Transform.createTransformedInterval(
							b,
							new FinalInterval(scaledFloorMin, scaledCeilMax),
							Transform.createScaledRealTransform(transformB, scaleIndex),
							new FloatType(0));

					/* TODO pad by radius plus something ? */
					final FinalInterval gridBlockInterval =
							new FinalInterval(offset, new long[]{offset[0] + gridCellWidth - 1, offset[1] + gridCellWidth - 1});

					final IntervalView<FloatType> gridBlockA = Views.interval(transformedA, gridBlockInterval);
					final IntervalView<FloatType> gridBlockB = Views.interval(transformedB, gridBlockInterval);

					/* TODO consider padding if padding */
					final RealTransform transform = Align.alignFlow(
							gridBlockB,
							gridBlockA,
							radius,
							sigma,
							numIterations);

					final N5Writer n5 = N5.openFSWriter(n5Path);
					final long[] gridOffset = Grid.gridCell(
							offset,
							Grid.floorScaled(boundsMin, scale),
							new int[]{stepSize, stepSize});
					final String datasetName = outTransformDatasetBaseName + "." + gridOffset[0] + "-" + gridOffset[1];
					final RealTransformSequence transformSequence = new RealTransformSequence();

					/* TODO weight */
					transformSequence.add(new Scale2D(scale,  scale));
					transformSequence.add(transform);
					transformSequence.add(new Scale2D(1.0 / scale,  1.0 / scale));
					transformSequence.add(transformB);
					Transform.saveScaledTransformBlock(
							n5,
							datasetName,
							transformSequence,
							scale,
							boundsMin,
							boundsMax,
							gridOffset,
							new int[] {stepSize, stepSize});

					return offset;
				});

		return gridCells;
	}


	/**
	 * Align a pair of transformed N5 sections using optic flow over a scale
	 * space of block sizes on a grid of 50% overlapping cells.  The resulting
	 * alignment is the composition of the prior transform and the interpolant
	 * over the grid.  For grid cells, that do not return an alignment model,
	 * the prior transformation is used.
	 *
	 * TODO weigh the composition of the calculated flow field and prior
	 * transformation by the weight (mask * R)
	 *
	 * @param sc
	 * @param n5Path
	 * @param inGroupName
	 * @param outGroupName
	 * @param datasetNames
	 * @param indexA
	 * @param indexB
	 * @param transformScaleIndex
	 * @param boundsMin
	 * @param boundsMax
	 * @param stepSize
	 * @param gridOffsets
	 * @param radius
	 * @param sigma
	 * @param numIterations
	 * @throws IOException
	 */
	public static void alignPairFlow(
			final JavaSparkContext sc,
			final String n5Path,
			final String inGroupName,
			final String outGroupName,
			final String datasetNameA,
			final String datasetNameB,
			final String transformDatasetNameA,
			final String transformDatasetNameB,
			final int transformScaleIndex,
			final double[] boundsMin,
			final double[] boundsMax,
			final int stepSize,
			final List<long[]> gridOffsets,
			final short radius,
			final double sigma,
			final int numIterations) throws IOException {

		final double scale = 1.0 / (1 << transformScaleIndex);

		final long[] floorScaledMin = Grid.floorScaled(boundsMin, scale);
		final long[] ceilScaledMax = Grid.ceilScaled(boundsMax, scale);

		final JavaRDD<long[]> gridCells = alignAndSaveAccumulatedGridCells(
				sc,
				n5Path,
				datasetNameA,
				datasetNameB,
				transformScaleIndex,
				inGroupName + "/" + transformDatasetNameA,
				inGroupName + "/" + transformDatasetNameB,
				outGroupName + "/" + transformDatasetNameB,
				boundsMin,
				boundsMax,
				floorScaledMin,
				ceilScaledMax,
				stepSize,
				gridOffsets,
				radius,
				sigma,
				numIterations);

		gridCells.cache();
		gridCells.count();

		final JavaRDD<long[]> composedGridCells = composeOverlappingTransformGridCells(
				gridCells,
				n5Path,
				outGroupName + "/" + transformDatasetNameB,
				scale,
				boundsMin,
				boundsMax,
				stepSize);

		composedGridCells.cache();
		composedGridCells.count();

		deleteGridCells(
				composedGridCells,
				n5Path,
				outGroupName + "/" + transformDatasetNameB,
				scale,
				boundsMin,
				boundsMax,
				stepSize);
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final N5Writer n5 = N5.openFSWriter(options.getN5Path());
		final String[] datasetNames = n5.getAttribute(options.getInGroup(), "datasets", String[].class);
		final String[] transformDatasetNames = n5.getAttribute(options.getInGroup(), "transforms", String[].class);
		final double[] boundsMin = n5.getAttribute(options.getInGroup(), "boundsMin", double[].class);
		final double[] boundsMax = n5.getAttribute(options.getInGroup(), "boundsMax", double[].class);

		n5.createGroup(options.getOutGroup());
		n5.setAttribute(options.getOutGroup(), "datasets", datasetNames);
		n5.setAttribute(options.getOutGroup(), "transforms", transformDatasetNames);
		n5.setAttribute(options.getOutGroup(), "scaleIndex", options.getTransformScaleIndex());
		n5.setAttribute(options.getOutGroup(), "boundsMin", boundsMin);
		n5.setAttribute(options.getOutGroup(), "boundsMax", boundsMax);

		final double scale = 1.0 / (1 << options.getTransformScaleIndex());

		final long[] floorScaledMin = Grid.floorScaled(boundsMin, scale);
		final long[] ceilScaledMax = Grid.ceilScaled(boundsMax, scale);

		final List<long[]> gridOffsets = Grid.createOffsets(
				new FinalInterval(floorScaledMin, ceilScaledMax),
				new int[]{options.getStepSize(), options.getStepSize()});

		System.out.println(Arrays.deepToString(gridOffsets.toArray()));

		final SparkConf conf = new SparkConf().setAppName("SparkPairAlignFlow");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		/* re-save the old transforms */
		final ArrayList<String> inPriorTransformDatasetNames = new ArrayList<>();
		final ArrayList<String> outPriorTransformDatasetNames = new ArrayList<>();
		inPriorTransformDatasetNames.add(options.getInGroup() + "/" + transformDatasetNames[0]);
		outPriorTransformDatasetNames.add(options.getOutGroup() + "/" + transformDatasetNames[0]);
		for (int i = 1; i < datasetNames.length; i += 2) {
			inPriorTransformDatasetNames.add(options.getInGroup() + "/" + transformDatasetNames[i]);
			outPriorTransformDatasetNames.add(options.getOutGroup() + "/" + transformDatasetNames[i]);
		}

		Spark.copyTransforms(
				sc,
				options.getN5Path(),
				inPriorTransformDatasetNames,
				outPriorTransformDatasetNames);

		for (int i = 1; i < datasetNames.length - 2; i += 2) {

			System.out.printf(
					"Aligning dataset %d : %s, %d : %s, %d grid cells",
					i,
					datasetNames[i],
					i + 1,
					datasetNames[i + 1],
					gridOffsets.size());
			System.out.println();

			alignPairFlow(
					sc,
					options.getN5Path(),
					options.getInGroup(),
					options.getOutGroup(),
					datasetNames[i],
					datasetNames[i + 1],
					transformDatasetNames[i],
					transformDatasetNames[i + 1],
					options.getTransformScaleIndex(),
					boundsMin,
					boundsMax,
					options.getStepSize(),
					gridOffsets,
					(short)Math.ceil(Math.abs(options.getMaxFilterEpsilon())),
					options.getSigma(),
					3);
		}

		sc.close();
	}
}
