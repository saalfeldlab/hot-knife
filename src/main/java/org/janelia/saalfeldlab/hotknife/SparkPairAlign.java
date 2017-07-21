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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Align;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Show;
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

import bdv.util.Bdv;
import ij.ImageJ;
import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.RigidModel2D;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.array.ArrayLocalizingCursor;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import scala.Tuple2;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkPairAlign {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "-i", aliases = {"--n5InputGroup"}, required = true, usage = "N5 input group, e.g. /align")
		private final String inGroup = null;

		@Option(name = "-o", aliases = {"--n5OutputGroup"}, required = true, usage = "N5 output group, e.g. /align")
		private final String outGroup = null;

		@Option(name = "-a", required = true, usage = "index of first face (target), e.g. 1")
		private int indexA = 1;

		@Option(name = "-b", required = true, usage = "index of first face (moving), e.g. 2")
		private int indexB = 2;

		@Option(name = "--scaleIndex", required = true, usage = "scale index, e.g. 4 (means scale = 1.0 / 2^4)")
		private int scaleIndex = 0;

		@Option(name = "--stepSize", required = true, usage = "step size for grid")
		private int stepSize = 1024;

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

			return scaleIndex;
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
		 * @return the indexA
		 */
		public int getIndexA() {

			return indexA;
		}

		/**
		 * @return the indexB
		 */
		public int getIndexB() {

			return indexB;
		}

		/**
		 * @return the stepSize
		 */
		public int getStepSize() {

			return stepSize;
		}
	}

	static public JavaPairRDD<long[], double[]> align(
			final JavaSparkContext sc,
			final String n5Path,
			final String datasetA,
			final String datasetB,
			final int scaleIndex,
			final String transformADataset,
			final String transformBDataset,
			final int transformScaleIndex,
			final double[] boundsMin,
			final double[] boundsMax,
			final long[] scaledFloorMin,
			final long[] scaledCeilMax,
			final long gridCellWidth,
			final List<long[]> gridOffsets) throws IOException {

		final double scale = 1.0 / (1 << scaleIndex);
		final double transformScale = 1.0 / (1 << transformScaleIndex);

		final JavaRDD<long[]> offsets = sc.parallelize(gridOffsets);

		final JavaPairRDD<long[], double[]> affines =
				offsets.mapToPair(offset -> {

					final N5Reader n5Reader = N5.openFSReader(n5Path);
					final RandomAccessibleInterval<FloatType> a = N5Utils.open(n5Reader, datasetA + "/s" + scaleIndex);
					final RandomAccessibleInterval<FloatType> b = N5Utils.open(n5Reader, datasetB + "/s" + scaleIndex);

					final RealTransform transformA = Transform.loadScaledTransform(
							n5Reader,
							transformADataset,
							transformScale,
							boundsMin,
							boundsMax);
					final RealTransform transformB = Transform.loadScaledTransform(
							n5Reader,
							transformBDataset,
							transformScale,
							boundsMin,
							boundsMax);

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

					final FinalInterval gridBlockInterval =
							new FinalInterval(offset, new long[]{offset[0] + gridCellWidth - 1, offset[1] + gridCellWidth - 1});

					final IntervalView<FloatType> gridBlockA = Views.interval(transformedA, gridBlockInterval);
					final IntervalView<FloatType> gridBlockB = Views.interval(transformedB, gridBlockInterval);

					final Transform.InterpolatedAffineModel2DSupplier<AffineModel2D, RigidModel2D> modelSupplier =
							new Transform.InterpolatedAffineModel2DSupplier<AffineModel2D, RigidModel2D>(
								(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
								(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
								0.01);

					final Transform.InterpolatedAffineModel2DSupplier<AffineModel2D, RigidModel2D> filterModelSupplier =
							new Transform.InterpolatedAffineModel2DSupplier<AffineModel2D, RigidModel2D>(
								(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
								(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
								0.1);

					final MultiConsensusFilter<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>> filter = new MultiConsensusFilter<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>>(
							filterModelSupplier,
							10000,
							50.0,
							0.0,
							7);

					final AffineTransform2D transform = Align.<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>, AffineTransform2D>alignSIFT(
							gridBlockB,
							gridBlockA,
							1.0,
							0.5,
							4,
							0.92,
							1.0 / scale,
							filter,
							modelSupplier,
							Transform::convertAndInvertAffine2DtoAffineTransform2D);

					return new Tuple2<long[], double[]>(offset, transform == null ? null : transform.getRowPackedCopy());
				});

		return affines;
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
			final int priorTransformScaleIndex,
			final String datasetBaseName,
			final double[] boundsMin,
			final double[] boundsMax,
			final int stepSize,
			final double transformScale) {

		final double priorTransformScale = 1.0 / (1 << priorTransformScaleIndex);

		final JavaRDD<long[]> gridCells = affines.map(
				t -> {
					final N5Writer n5 = N5.openFSWriter(n5Path);
					final RealTransform priorTransform = Transform.loadScaledTransform(
							n5,
							priorTransformDatasetName,
							priorTransformScale,
							boundsMin,
							boundsMax);
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
	public static void composeOverlappingTransformGridCells(
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

		mappedGridCells.count();

//		final JavaRDD<long[]> deletedGridCells = mappedGridCells.map(
//				cell -> {
//					final N5Writer n5 = N5.openFSWriter(n5Path);
//					final long[] gridOffset = Grid.gridCell(
//							cell,
//							Grid.floorScaled(boundsMin, transformScale),
//							new int[]{stepSize, stepSize});
//					n5.remove(transformDatasetBaseName + "." + gridOffset[0] + "-" + gridOffset[1]);
//					return cell;
//				});
//
//		deletedGridCells.count();
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final N5Writer n5 = N5.openFSWriter(options.getN5Path());
		final String[] datasetNames = n5.getAttribute(options.getInGroup(), "datasets", String[].class);
		final int priorTransformScaleIndex = n5.getAttribute(options.getInGroup(), "scaleIndex", int.class);
		final double[] boundsMin = n5.getAttribute(options.getInGroup(), "boundsMin", double[].class);
		final double[] boundsMax = n5.getAttribute(options.getInGroup(), "boundsMax", double[].class);

		n5.createGroup(options.getOutGroup());
		n5.setAttribute(options.getOutGroup(), "datasets", datasetNames);
		n5.setAttribute(options.getOutGroup(), "scaleIndex", options.getScaleIndex());
		n5.setAttribute(options.getOutGroup(), "boundsMin", boundsMin);
		n5.setAttribute(options.getOutGroup(), "boundsMax", boundsMax);

		final double scale = 1.0 / (1 << options.getScaleIndex());

		final long[] floorScaledMin = Grid.floorScaled(boundsMin, scale);
		final long[] ceilScaledMax = Grid.ceilScaled(boundsMax, scale);

		final List<long[]> gridOffsets = Grid.createOffsets(
				new FinalInterval(floorScaledMin, ceilScaledMax),
				new int[]{options.getStepSize(), options.getStepSize()});

		System.out.println(Arrays.deepToString(gridOffsets.toArray()));

		final SparkConf conf = new SparkConf().setAppName("SparkPairAlign");
		final JavaSparkContext sc = new JavaSparkContext(conf);

		sc.setLogLevel("ERROR");

		System.out.printf("Datasets %d : %s, %s : %s, %d grid cells", options.getIndexA(), datasetNames[options.getIndexA()], options.getIndexA(), datasetNames[options.getIndexB()], gridOffsets.size());

		final JavaPairRDD<long[], double[]> affines = align(
				sc,
				options.getN5Path(),
				datasetNames[options.getIndexA()],
				datasetNames[options.getIndexB()],
				options.getScaleIndex(),
				options.getInGroup() + "/" + options.getIndexA(),
				options.getInGroup() + "/" + options.getIndexB(),
				priorTransformScaleIndex,
				boundsMin,
				boundsMax,
				floorScaledMin,
				ceilScaledMax,
				options.getStepSize() * 2,
				gridOffsets);

		final JavaRDD<long[]> gridCells = saveAccumulatedAffineGridCells(
				affines,
				options.getN5Path(),
				options.getInGroup() + "/" + options.getIndexB(),
				priorTransformScaleIndex,
				options.getOutGroup() + "/" + options.getIndexB(),
				boundsMin,
				boundsMax,
				options.getStepSize(),
				scale);

		gridCells.cache();
		gridCells.count();

		composeOverlappingTransformGridCells(
				gridCells,
				options.getN5Path(),
				options.getOutGroup() + "/" + options.getIndexB(),
				scale,
				boundsMin,
				boundsMax,
				options.getStepSize());

		new ImageJ();

		ImageJFunctions.show((RandomAccessibleInterval<DoubleType>)N5Utils.open(n5, options.getOutGroup() + "/" + options.getIndexB()));

		final int showScaleIndex = options.getScaleIndex();
		final double showScale = 1.0 / (1 << showScaleIndex);

		final double priorTransformScale = 1.0 / (1 << priorTransformScaleIndex);
		final double transformScale = 1.0 / (1 << options.getScaleIndex());

		final RealTransform[] realTransforms = new RealTransform[datasetNames.length];
		for (int i = 0; i < datasetNames.length; ++i) {
			realTransforms[i] = Transform.loadScaledTransform(
					n5,
					options.getInGroup() + "/" + i,
					priorTransformScale,
					boundsMin,
					boundsMax);
		}

		realTransforms[2] = Transform.loadScaledTransform(
				n5,
				options.getOutGroup() + "/2",
				transformScale,
				boundsMin,
				boundsMax);

		realTransforms[4] = Transform.loadScaledTransform(
				n5,
				options.getOutGroup() + "/4",
				transformScale,
				boundsMin,
				boundsMax);

		realTransforms[options.getIndexB()] = Transform.loadScaledTransform(
				n5,
				options.getOutGroup() + "/" + options.getIndexB(),
				transformScale,
				boundsMin,
				boundsMax);

		final Bdv bdv = Show.transformedStack(
				options.getN5Path(),
				Arrays.asList(datasetNames),
				showScaleIndex,
				Arrays.asList(realTransforms),
//				new FinalInterval(new long[]{-512, -512}, new long[]{1535, 1535}));
				new FinalInterval(
						Grid.floorScaled(boundsMin, showScale),
						Grid.ceilScaled(boundsMax, showScale)),
				null);

		sc.close();
	}
}
