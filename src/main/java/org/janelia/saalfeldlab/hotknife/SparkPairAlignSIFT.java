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
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Align;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import mpicbg.models.AffineModel2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.RigidModel2D;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.array.ArrayLocalizingCursor;
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
public class SparkPairAlignSIFT {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "-i", aliases = {"--n5GroupInput"}, required = true, usage = "N5 input group, e.g. /align")
		private final String inGroup = null;

		@Option(name = "-o", aliases = {"--n5GroupOutput"}, required = true, usage = "N5 output group, e.g. /align")
		private final String outGroup = null;

		@Option(name = "--scaleIndex", required = true, usage = "scale index for the output transform, e.g. 4 (means scale = 1.0 / 2^4)")
		private int transformScaleIndex = 0;

		@Option(name = "--stepSize", required = true, usage = "step size for grid")
		private int stepSize = 1024;

		@Option(name = "--lambdaModel", required = false, usage = "lambda for rigid regularizer in model")
		private double lambdaModel = 0.01;

		@Option(name = "--lambdaFilter", required = false, usage = "lambda for rigid regularizer in filter")
		private double lambdaFilter = 0.1;

		@Option(name = "--maxEpsilon", required = true, usage = "residual threshold for filter in world pixels")
		private double maxFilterEpsilon = 50.0;

		@Option(name = "--maxRetries", required = false, usage = "Maximum retry attempts for N5 writes (default: 5)")
		private int maxRetries = 5;

		@Option(name = "--retryDelayMs", required = false, usage = "Initial retry delay in milliseconds (default: 2000)")
		private long retryDelayMs = 2000;

		@Option(name = "--retryBackoff", required = false, usage = "Exponential backoff multiplier (default: 2.0)")
		private double retryBackoff = 2.0;

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

		public double getLambdaModel() {

			return lambdaModel;
		}

		public double getLambdaFilter() {

			return lambdaFilter;
		}

		public double getMaxFilterEpsilon() {

			return maxFilterEpsilon;
		}

		/**
		 * @return the maxRetries
		 */
		public int getMaxRetries() {
			return maxRetries;
		}

		/**
		 * @return the retryDelayMs
		 */
		public long getRetryDelayMs() {
			return retryDelayMs;
		}

		/**
		 * @return the retryBackoff
		 */
		public double getRetryBackoff() {
			return retryBackoff;
		}
	}


	/**
	 * Functional interface for operations that can throw exceptions.
	 */
	@FunctionalInterface
	private interface RunnableWithException {
		void run() throws Exception;
	}


	/**
	 * Execute an operation with exponential backoff retry logic.
	 * Specifically handles GCS rate limit errors with delays, and retries other errors without delay.
	 *
	 * @param operation The operation to execute
	 * @param maxRetries Maximum number of retry attempts (beyond initial attempt)
	 * @param initialDelayMs Initial delay in milliseconds before first retry
	 * @param backoffMultiplier Exponential backoff multiplier for delays
	 * @param operationDescription Description of operation for logging
	 * @return Result of the operation
	 * @throws Exception if all retries are exhausted
	 */
	private static <T> T executeWithRetry(
			final Supplier<T> operation,
			final int maxRetries,
			final long initialDelayMs,
			final double backoffMultiplier,
			final String operationDescription) throws Exception {

		Exception lastException = null;
		long delayMs = initialDelayMs;

		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			try {
				return operation.get();
			} catch (final Exception e) {
				lastException = e;

				// Check if it's a GCS rate limit error
				final boolean isRateLimitError = e.getMessage() != null &&
					(e.getMessage().contains("GCS429") ||
					 e.getMessage().contains("rate limit") ||
					 e.getMessage().contains("StorageException"));

				if (isRateLimitError && attempt < maxRetries) {
					System.err.println(String.format(
						"GCS rate limit hit for %s (attempt %d/%d), retrying in %dms",
						operationDescription, attempt + 1, maxRetries + 1, delayMs));
					Thread.sleep(delayMs);
					delayMs = (long)(delayMs * backoffMultiplier);
				} else if (attempt < maxRetries) {
					// For non-rate-limit errors, retry without delay
					System.err.println(String.format(
						"Error in %s (attempt %d/%d): %s",
						operationDescription, attempt + 1, maxRetries + 1, e.getMessage()));
				}
			}
		}

		// All retries exhausted - fail fast
		throw new RuntimeException(
			String.format("Failed %s after %d attempts", operationDescription, maxRetries + 1),
			lastException);
	}


	/**
	 * Execute a void operation with exponential backoff retry logic.
	 *
	 * @param operation The operation to execute
	 * @param maxRetries Maximum number of retry attempts
	 * @param initialDelayMs Initial delay in milliseconds
	 * @param backoffMultiplier Exponential backoff multiplier
	 * @param operationDescription Description of operation for logging
	 * @throws Exception if all retries are exhausted
	 */
	private static void executeWithRetryVoid(
			final RunnableWithException operation,
			final int maxRetries,
			final long initialDelayMs,
			final double backoffMultiplier,
			final String operationDescription) throws Exception {

		executeWithRetry((Supplier<Void> & Serializable) () -> {
			try {
				operation.run();
				return null;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, maxRetries, initialDelayMs, backoffMultiplier, operationDescription);
	}


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
	public static JavaPairRDD<long[], double[]> alignSIFT(
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

		final JavaPairRDD<long[], double[]> affines =
				offsets.mapToPair(offset -> {

					final N5Reader n5Reader = N5Util.createN5Reader(n5Path);
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
							10000,
							maxFilterEpsilon,
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
	private static long[] saveAccumulatedAffineGridCell(
			final N5Writer n5,
			final String priorTransformDatasetName,
			final String datasetBaseName,
			final double[] boundsMin,
			final double[] boundsMax,
			final int stepSize,
			final double transformScale,
			final long[] offset,
			final double[] affine) throws IOException {

		final RealTransform priorTransform = Transform.loadScaledTransform(n5, priorTransformDatasetName);
		final long[] gridOffset = Grid.gridCell(
				offset,
				Grid.floorScaled(boundsMin, transformScale),
				new int[]{stepSize, stepSize});
		final String datasetName = datasetBaseName + "." + gridOffset[0] + "-" + gridOffset[1];
		final RealTransformSequence transformSequence = new RealTransformSequence();
		if (affine != null) {
			final AffineTransform2D transform = new AffineTransform2D();
			transform.set(affine);
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

		return offset;
	}

	public static JavaRDD<long[]> saveAccumulatedAffineGridCells(
			final JavaPairRDD<long[], double[]> affines,
			final String n5Path,
			final String priorTransformDatasetName,
			final String datasetBaseName,
			final double[] boundsMin,
			final double[] boundsMax,
			final int stepSize,
			final double transformScale,
			final int maxRetries,
			final long retryDelayMs,
			final double retryBackoff) {

		// Parallel write with retry logic for all storage types
		final JavaRDD<long[]> gridCells = affines.map(
				t -> {
					try {
						return executeWithRetry(
							(Supplier<long[]> & Serializable) () -> {
								try {
									final N5Writer n5 = N5Util.createN5Writer(n5Path);
									return saveAccumulatedAffineGridCell(
											n5, priorTransformDatasetName, datasetBaseName,
											boundsMin, boundsMax, stepSize, transformScale,
											t._1(), t._2());
								} catch (IOException e) {
									throw new RuntimeException(e);
								}
							},
							maxRetries,
							retryDelayMs,
							retryBackoff,
							"saveAccumulatedAffineGridCell");
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});

		return gridCells;
	}


	/**
	 * This is for 2D transformation fields only
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
				N5Util.createN5Writer(n5Path),
				transformDatasetBaseName,
				boundsMin,
				boundsMax,
				transformScale,
				new int[] {stepSize, stepSize});

		final long[] dimensions = attributes.getDimensions();

		final JavaRDD<long[]> mappedGridCells = gridCells.map(
				cell -> {
					final N5Writer n5 = N5Util.createN5Writer(n5Path);
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
							new GzipCompression());
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
					final N5Writer n5 = N5Util.createN5Writer(n5Path);
					final long[] gridOffset = Grid.gridCell(
							cell,
							Grid.floorScaled(boundsMin, transformScale),
							new int[]{stepSize, stepSize});
					n5.remove(transformDatasetBaseName + "." + gridOffset[0] + "-" + gridOffset[1]);
				});
	}


	private static void reSaveTransform(
			final N5Writer n5,
			final String inDatasetName,
			final String outDatasetName) throws IOException {
		final RealTransform transform = Transform.loadScaledTransform(n5, inDatasetName);
		final double[] boundsMin = n5.getAttribute(inDatasetName, "boundsMin", double[].class);
		final double[] boundsMax = n5.getAttribute(inDatasetName, "boundsMax", double[].class);
		final double scale = n5.getAttribute(inDatasetName, "scale", double.class);
		Transform.saveScaledTransform(
				n5,
				outDatasetName,
				transform,
				scale,
				boundsMin,
				boundsMax);
	}

	public static void reSaveTransforms(
			final JavaSparkContext sc,
			final String n5Path,
			final List<String> inDatasetNames,
			final List<String> outDatasetNames,
			final int maxRetries,
			final long retryDelayMs,
			final double retryBackoff) {

		final ArrayList<Tuple2<String, String>> datasetNames = new ArrayList<>();
		for (int i = 0; i < inDatasetNames.size(); ++i)
			datasetNames.add(new Tuple2<String, String>(inDatasetNames.get(i), outDatasetNames.get(i)));

		// Parallel write with retry logic for all storage types
		final JavaPairRDD<String, String> rddDatasetNames = sc.parallelizePairs(datasetNames);
		rddDatasetNames.foreach(
				tuple -> {
					try {
						executeWithRetryVoid(
							() -> {
								final N5Writer n5 = N5Util.createN5Writer(n5Path);
								reSaveTransform(n5, tuple._1(), tuple._2());
							},
							maxRetries,
							retryDelayMs,
							retryBackoff,
							"reSaveTransform " + tuple._1() + " -> " + tuple._2());
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				});
	}


	/**
	 * Align a pair of transformed N5 sections using SIFT and affine models on
	 * a grid of 50% overlapping cells.  The resulting alignment is the
	 * composition of the prior transform and the interpolant over the grid.
	 * For grid cells, that do not return an alignment model, the prior
	 * transformation is used.
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
	 * @param lambdaModel
	 * @param lambdaFilter
	 * @param maxFilterEpsilon
	 * @throws IOException
	 */
	public static void alignPairSIFT(
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
			final double maxFilterEpsilon,
			final int maxRetries,
			final long retryDelayMs,
			final double retryBackoff) throws IOException {

		final double scale = 1.0 / (1 << transformScaleIndex);

		final long[] floorScaledMin = Grid.floorScaled(boundsMin, scale);
		final long[] ceilScaledMax = Grid.ceilScaled(boundsMax, scale);

		final JavaPairRDD<long[], double[]> affines = alignSIFT(
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

		final JavaRDD<long[]> gridCells = saveAccumulatedAffineGridCells(
				affines,
				n5Path,
				inGroupName + "/" + transformDatasetNameB,
				outGroupName + "/" + transformDatasetNameB,
				boundsMin,
				boundsMax,
				stepSize,
				scale,
				maxRetries,
				retryDelayMs,
				retryBackoff);

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

		final N5Writer n5 = N5Util.createN5Writer(options.getN5Path());
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

		reSaveTransforms(
				sc,
				options.getN5Path(),
				inPriorTransformDatasetNames,
				outPriorTransformDatasetNames,
				options.getMaxRetries(),
				options.getRetryDelayMs(),
				options.getRetryBackoff());

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

			alignPairSIFT(
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
					options.getMaxFilterEpsilon(),
					options.getMaxRetries(),
					options.getRetryDelayMs(),
					options.getRetryBackoff());
		}

		sc.close();
	}
}
