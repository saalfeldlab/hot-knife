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
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.RigidModel2D;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealTransform;
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
		private final int indexA = 1;

		@Option(name = "-b", required = true, usage = "index of first face (moving), e.g. 2")
		private final int indexB = 2;

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
								0.1);

					final Transform.InterpolatedAffineModel2DSupplier<AffineModel2D, RigidModel2D> filterModelSupplier =
							new Transform.InterpolatedAffineModel2DSupplier<AffineModel2D, RigidModel2D>(
								(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
								(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
								0.25);

					final MultiConsensusFilter<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>> filter = new MultiConsensusFilter<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>>(
							filterModelSupplier,
							10000,
							100.0,
							0.0,
							7);

					final AffineTransform2D transform = Align.<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>, AffineTransform2D>alignSIFT(
							gridBlockA,
							gridBlockB,
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


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final N5Writer n5 = N5.openFSWriter(options.getN5Path());
		final String[] datasetNames = n5.getAttribute(options.getInGroup(), "datasets", String[].class);
		final int transformScaleIndex = n5.getAttribute(options.getInGroup(), "scaleIndex", int.class);
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

		System.out.printf("Datasets %d : %s, %s : %s, %d grid cells", options.getIndexA(), datasetNames[options.getIndexA()], options.getIndexA(), datasetNames[options.getIndexB()], gridOffsets.size());

		final JavaPairRDD<long[], double[]> affines = align(
				sc,
				options.getN5Path(),
				datasetNames[options.getIndexA()],
				datasetNames[options.getIndexB()],
				options.getScaleIndex(),
				options.getInGroup() + "/" + options.getIndexA(),
				options.getInGroup() + "/" + options.getIndexB(),
				transformScaleIndex,
				boundsMin,
				boundsMax,
				floorScaledMin,
				ceilScaledMax,
				options.getStepSize() * 2,
				gridOffsets);

		affines.foreach(t -> System.out.println(Arrays.toString(t._1()) + " : " + Arrays.toString(Grid.gridCell(t._1(), floorScaledMin, new int[]{options.getStepSize(), options.getStepSize()})) + " > " + (t._2() == null ? null : Arrays.toString(t._2()))));

		sc.close();
	}
}
