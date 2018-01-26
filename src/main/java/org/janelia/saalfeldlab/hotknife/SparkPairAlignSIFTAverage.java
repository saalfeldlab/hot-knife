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
import java.util.function.Supplier;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Align;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IdentityModel;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
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
public class SparkPairAlignSIFTAverage {

	/**
	 *
	 * @param sc Spark context
	 * @param n5Path
	 * @param datasetA multi-scale dataset group, dataset path is datasetA + "/s" + scaleIndex
	 * @param datasetB multi-scale dataset group, dataset path is datasetA + "/s" + scaleIndex
	 * @param scaleIndex
	 * @param transformADataset scaled transform dataset A, scale is resolved from scale property
	 * @param transformBDataset scaled transform dataset B, scale is resolved from scale property
	 * @param boundsMin min coordinates of bounding box in world coordinates (not scaled)
	 * @param boundsMax max coordinates of bounding box in world coordinates (not scaled)
	 * @param scaledFloorMin scaled (according to scaleIndex above) and floor rounded min coordinates of bounding box
	 * @param scaledCeilMax scaled (according to scaleIndex above) and ceil rounded max coordinates of bounding box
	 * @param gridCellWidth
	 * @param gridOffsets
	 * @param lambdaModel
	 * @param lambdaFilter
	 * @param maxFilterEpsilon
	 * @return
	 * @throws IOException
	 */
	public static JavaPairRDD<long[], Tuple2<double[], double[]>> alignSIFTAverage(
			final JavaSparkContext sc,
			final String n5Path,
			final String datasetA,
			final String datasetB,
			final int scaleIndex,
			final String transformADataset,
			final String transformBDataset,
			final double[] boundsMin,
			final double[] boundsMax,
			final long[] scaledFloorMin,
			final long[] scaledCeilMax,
			final long gridCellWidth,
			final List<long[]> gridOffsets,
			final double lambdaModel,
			final double lambdaFilter,
			final double maxFilterEpsilon) throws IOException {

		final double scale = 1.0 / (1 << scaleIndex);

		final JavaRDD<long[]> offsets = sc.parallelize(gridOffsets);

		final JavaPairRDD<long[], Tuple2<double[], double[]>> affines =
				offsets.mapToPair(offset -> {

					final N5Reader n5Reader = new N5FSReader(n5Path);
					final RandomAccessibleInterval<FloatType> a = N5Utils.open(n5Reader, datasetA + "/s" + scaleIndex);
					final RandomAccessibleInterval<FloatType> b = N5Utils.open(n5Reader, datasetB + "/s" + scaleIndex);

					final RealTransform transformA = Transform.loadScaledTransform(
							n5Reader,
							transformADataset);
					final RealTransform transformB = Transform.loadScaledTransform(
							n5Reader,
							transformBDataset);

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
								lambdaModel);

					final Transform.InterpolatedAffineModel2DSupplier<AffineModel2D, RigidModel2D> filterModelSupplier =
							new Transform.InterpolatedAffineModel2DSupplier<AffineModel2D, RigidModel2D>(
								(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
								(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
								lambdaFilter);

					final MultiConsensusFilter<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>> filter = new MultiConsensusFilter<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>>(
							filterModelSupplier,
							2000,
							maxFilterEpsilon,
							0.0,
							10);

					final ArrayList<PointMatch> matches = Align.filterMatchSIFT(
							gridBlockB,
							gridBlockA,
							1.0,
							0.5,
							4,
							0.92,
							1.0 / scale,
							filter);

					final InterpolatedAffineModel2D<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>, IdentityModel> modelA =
							new InterpolatedAffineModel2D<>(modelSupplier.get(), new IdentityModel(), 0.5);
					final InterpolatedAffineModel2D<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>, IdentityModel> modelB =
							new InterpolatedAffineModel2D<>(modelSupplier.get(), new IdentityModel(), 0.5);
					final Tile<InterpolatedAffineModel2D<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>, IdentityModel>> tileA = new Tile<>(modelA);
					final Tile<InterpolatedAffineModel2D<InterpolatedAffineModel2D<AffineModel2D, RigidModel2D>, IdentityModel>> tileB = new Tile<>(modelB);

					tileB.connect(tileA, matches);

					final TileConfiguration tc = new TileConfiguration();
					tc.addTile(tileA);
					tc.addTile(tileB);

					AffineTransform2D tileATransform;
					AffineTransform2D tileBTransform;
					try {
						tc.optimizeSilently(new ErrorStatistic(201), maxFilterEpsilon, 1000, 1000, 0.6);
						tileATransform = Transform.convertAndInvertAffine2DtoAffineTransform2D(modelA);
						tileBTransform = Transform.convertAndInvertAffine2DtoAffineTransform2D(modelB);
					} catch (final IllDefinedDataPointsException | NotEnoughDataPointsException e) {
						tileATransform = null;
						tileBTransform = null;
					}

					return new Tuple2<>(
							offset,
							new Tuple2<>(
									tileATransform == null ? null : tileATransform.getRowPackedCopy(),
									tileBTransform == null ? null : tileBTransform.getRowPackedCopy()));
				});

		return affines;
	}

	/**
	 * Align a pair of transformed N5 sections using SIFT and affine models on
	 * a grid of 50% overlapping cells.  The resulting alignment is the
	 * composition of the prior transform and the interpolant over the grid.
	 * For grid cells, that do not return an alignment model, the prior
	 * transformation is used.
	 * Other than by {@link SparkPairAlignSIFT#alignPairSIFT(JavaSparkContext, String, String, String, String, String, String, String, int, double[], double[], int, List, double, double, double)},
	 * both N5 sections are transformed, B by affine/2 and A by affine<sup>-1</sup>/2.
	 *
	 * @param sc
	 * @param n5Path
	 * @param inGroupName
	 * @param outGroupName
	 * @param datasetNameA
	 * @parrm datasetNameB
	 * @param transformDatasetNameA
	 * @parrm transformDatasetNameB
	 * @param transformScaleIndex
	 * @param boundsMin
	 * @param boundsMax
	 * @param stepSize
	 * @param gridOffsets
	 * @param lambdaModel
	 * @param lambdaFilter
	 * @param maxFilterEpsilon
	 * @throws IOException
	 */
	public static void alignPairSIFTAverage(
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
			final double lambdaModel,
			final double lambdaFilter,
			final double maxFilterEpsilon) throws IOException {

		final double scale = 1.0 / (1 << transformScaleIndex);

		final long[] floorScaledMin = Grid.floorScaled(boundsMin, scale);
		final long[] ceilScaledMax = Grid.ceilScaled(boundsMax, scale);

		final JavaPairRDD<long[], Tuple2<double[], double[]>> affines = alignSIFTAverage(
				sc,
				n5Path,
				datasetNameA,
				datasetNameB,
				transformScaleIndex,
				inGroupName + "/" + transformDatasetNameA,
				inGroupName + "/" + transformDatasetNameB,
				boundsMin,
				boundsMax,
				floorScaledMin,
				ceilScaledMax,
				stepSize * 2,
				gridOffsets,
				lambdaModel,
				lambdaFilter,
				maxFilterEpsilon);

		affines.cache();
		affines.count();

		final JavaPairRDD<long[], double[]> affinesA = affines.mapToPair(
				a -> new Tuple2<>(a._1(), a._2()._1()));
		final JavaPairRDD<long[], double[]> affinesB = affines.mapToPair(
				a -> new Tuple2<>(a._1(), a._2()._2()));

		final JavaRDD<long[]> gridCellsA = SparkPairAlignSIFT.saveAccumulatedAffineGridCells(
				affinesA,
				n5Path,
				inGroupName + "/" + transformDatasetNameA,
				outGroupName + "/" + transformDatasetNameA,
				boundsMin,
				boundsMax,
				stepSize,
				scale);

		final JavaRDD<long[]> gridCellsB = SparkPairAlignSIFT.saveAccumulatedAffineGridCells(
				affinesB,
				n5Path,
				inGroupName + "/" + transformDatasetNameB,
				outGroupName + "/" + transformDatasetNameB,
				boundsMin,
				boundsMax,
				stepSize,
				scale);

		gridCellsA.cache();
		gridCellsA.count();
		gridCellsB.cache();
		gridCellsB.count();

		final JavaRDD<long[]> composedGridCellsA = SparkPairAlignSIFT.composeOverlappingTransformGridCells(
				gridCellsA,
				n5Path,
				outGroupName + "/" + transformDatasetNameA,
				scale,
				boundsMin,
				boundsMax,
				stepSize);
		final JavaRDD<long[]> composedGridCellsB = SparkPairAlignSIFT.composeOverlappingTransformGridCells(
				gridCellsB,
				n5Path,
				outGroupName + "/" + transformDatasetNameB,
				scale,
				boundsMin,
				boundsMax,
				stepSize);

		composedGridCellsA.cache();
		composedGridCellsA.count();
		composedGridCellsB.cache();
		composedGridCellsB.count();

		SparkPairAlignSIFT.deleteGridCells(
				composedGridCellsA,
				n5Path,
				outGroupName + "/" + transformDatasetNameA,
				scale,
				boundsMin,
				boundsMax,
				stepSize);
		SparkPairAlignSIFT.deleteGridCells(
				composedGridCellsB,
				n5Path,
				outGroupName + "/" + transformDatasetNameB,
				scale,
				boundsMin,
				boundsMax,
				stepSize);
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final SparkPairAlignSIFT.Options options = new SparkPairAlignSIFT.Options(args);

		if (!options.parsedSuccessfully)
			return;

		final N5Writer n5 = new N5FSWriter(options.getN5Path());
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

		final SparkConf conf = new SparkConf().setAppName("SparkPairAlignSIFT");
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

		SparkPairAlignSIFT.reSaveTransforms(
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

			final String datasetNameA = datasetNames[i];
			final String datasetNameB = datasetNames[i + 1];
			final String transformDatasetNameA = transformDatasetNames[i];
			final String transformDatasetNameB = transformDatasetNames[i + 1];

			alignPairSIFTAverage(
					sc,
					options.getN5Path(),
					options.getInGroup(),
					options.getOutGroup(),
					datasetNameA,
					datasetNameB,
					transformDatasetNameA,
					transformDatasetNameB,
					options.getTransformScaleIndex(),
					boundsMin,
					boundsMax,
					options.getStepSize(),
					gridOffsets,
					options.getLambdaModel(),
					options.getLambdaFilter(),
					options.getMaxFilterEpsilon());
		}

		sc.close();
	}
}
