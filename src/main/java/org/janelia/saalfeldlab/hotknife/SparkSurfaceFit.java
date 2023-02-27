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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrReader;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrWriter;

import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import de.mpicbg.scf.mincostsurface.MinCostZSurface;
import ij.ImageJ;
import ij.process.FloatProcessor;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.RealSum;
import net.imglib2.util.ValuePair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import net.imglib2.view.composite.GenericComposite;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkSurfaceFit implements Callable<Void>{

	@Option(names = {"--n5Path"}, required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"--n5FieldPath"}, required = true, description = "N5 output path for height fields, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5FieldPath = null;

	@Option(names = {"-i", "--n5CostInput"}, required = true, description = "N5 input group for cost, e.g. /cost-0")
	private String inGroup = null;

	@Option(names = {"--n5Raw"}, description = "N5 input group for cost, e.g. /raw")
	private String rawGroup = null;

	@Option(names = {"-o", "--n5SurfaceOutput"}, required = true, description = "N5 output group for , e.g. /surface-1")
	private String outGroup = null;

	@Option(names = {"--firstScale"}, description = "initial scale index, e.g. 8")
	private int firstScaleIndex = 0;

	@Option(names = {"--lastScale"}, description = "terminal scale index, e.g. 1")
	private int lastScaleIndex = 0;

	@Option(names = {"-z", "--maxDeltaZ"}, description = "maximum slope of the surface in original pixels, e.g. 0.25")
	private double maxDeltaZ = 0.25;

	@Option(names = {"--initMaxDeltaZ"}, description = "maximum slope of the surface in original pixels in the first scale level (initialization), e.g. 0.25")
	private double initMaxDeltaZ = 0.25;

	@Option(names = {"--minDistance"}, description = "minimum distance between the both surfaces, e.g. 1000")
	private double minDistance = 1;

	@Option(names = {"--maxDistance"}, description = "maximum distance between the both surfaces, e.g. 3500")
	private double maxDistance = Double.MAX_VALUE;

	@Option(names = {"--skipPermute"}, description = "FIB-SEM datasets needed to be permuted, Multi-Sem once not")
	private boolean skipPermute = false;

	private boolean useVisualization = false;

	/*
	--n5Path /nrs/flyem/render/n5/Z0720_07m_BR
	--n5FieldPath /nrs/flyem/render/n5/Z0720_07m_BR
	--n5CostInput /cost_new/Sec32/v3_acquire_trimmed_align_5_ic___20210503_161648_gauss
	--n5SurfaceOutput=/heightfields/Sec32/v3_acquire_trimmed_align_5_ic___20210503_161648_gauss_slope_sp
	--n5Raw /z_corr/Sec32/v3_acquire_trimmed_align_5_ic___20210503_161648
	--firstScale=8
	--lastScale=1
	--maxDeltaZ=0.15
	--initMaxDeltaZ=0.15
	--minDistance=2000
	--maxDistance=4000
	*/

	public SparkSurfaceFit() {
	}

	public SparkSurfaceFit(final String n5Path,
						   final String n5FieldPath,
						   final String inGroup,
						   final String rawGroup,
						   final String outGroup,
						   final int firstScaleIndex,
						   final int lastScaleIndex,
						   final double maxDeltaZ,
						   final double initMaxDeltaZ,
						   final double minDistance,
						   final double maxDistance,
						   final boolean skipPermute,
						   final boolean useVisualization) {
		this.n5Path = n5Path;
		this.n5FieldPath = n5FieldPath;
		this.inGroup = inGroup;
		this.rawGroup = rawGroup;
		this.outGroup = outGroup;
		this.firstScaleIndex = firstScaleIndex;
		this.lastScaleIndex = lastScaleIndex;
		this.maxDeltaZ = maxDeltaZ;
		this.initMaxDeltaZ = initMaxDeltaZ;
		this.minDistance = minDistance;
		this.maxDistance = maxDistance;
		this.skipPermute = skipPermute;
		this.useVisualization = useVisualization;
	}

	/**
	 * Mask (0) all voxel z-columns with all equal cost.
	 *
	 * @param <T>
	 * @param cost
	 * @return
	 */
	private static <T extends Type<T>> RandomAccessibleInterval<UnsignedByteType> costMask(final RandomAccessibleInterval<T> cost) {

		System.out.println(net.imglib2.util.Util.printInterval(cost));
		final ArrayImg<UnsignedByteType, ByteArray> mask = ArrayImgs.unsignedBytes(cost.dimension(0), cost.dimension(1));
		final T reference = net.imglib2.util.Util.getTypeFromInterval(cost).createVariable();
		final ArrayCursor<UnsignedByteType> maskCursor = mask.cursor();
		final long depth = cost.dimension(2);
		final Cursor<? extends GenericComposite<T>> costCursor = Views.flatIterable(Views.collapse(cost)).cursor();
		while (maskCursor.hasNext()) {
			final UnsignedByteType maskValue = maskCursor.next();
			final GenericComposite<T> costValue = costCursor.next();
			reference.set(costValue.get(0));
			for (long z = 1; z < depth; ++z) {
				if (!reference.valueEquals(costValue.get(z))) {
					maskValue.set(1);
					break;
				}
			}
		}
		return Views.translate(mask, cost.min(0), cost.min(1));
	}


	/**
	 * Set all values in slice that are 0 in mask to value.
	 * Ignores offsets and assumes that slice and mask have the same size.
	 *
	 * @param <T>
	 * @param <M>
	 * @param slice
	 * @param mask
	 * @param value
	 */
	private static <T extends Type<T>, M extends NumericType<M>> void maskSlice(
			final RandomAccessibleInterval<T> slice,
			final RandomAccessibleInterval<M> mask,
			final T value) {

		final M zero = net.imglib2.util.Util.getTypeFromInterval(mask).createVariable();
		zero.setZero();

		final Cursor<M> maskCursor = Views.flatIterable(mask).cursor();
		final Cursor<T> sliceCursor = Views.flatIterable(slice).cursor();
		while (sliceCursor.hasNext()) {
			sliceCursor.fwd();
			if (maskCursor.next().valueEquals(zero))
				sliceCursor.get().set(value);
		}
	}


	private static <M extends RealType<M>> RandomAccessibleInterval<FloatType> inpaintCost(
			final RandomAccessibleInterval<FloatType> cost,
			final RandomAccessibleInterval<M> mask) {

		final ArrayList<ArrayImg<FloatType, FloatArray>> slices = new ArrayList<>();
		for (long z = cost.min(2); z <= cost.max(2); ++z) {
			final FloatProcessor fpSlice = Util.materialize(Views.hyperSlice(cost, 2, z));
			final ArrayImg<FloatType, FloatArray> slice = ArrayImgs.floats((float[])fpSlice.getPixels(), fpSlice.getWidth(), fpSlice.getHeight());
			maskSlice(slice, mask, new FloatType(Float.NaN));
			InpaintMasked.run(fpSlice);
			slices.add(slice);
		}

		final long[] min = new long[cost.numDimensions()];
		cost.min(min);
		return Views.translate(Views.stack(slices), min);
	}


	private static <M extends RealType<M>> RandomAccessibleInterval<FloatType> inpaintHeightField(
			final RandomAccessibleInterval<FloatType> heightField,
			final RandomAccessibleInterval<M> mask) {

		final FloatProcessor fpSlice = Util.materialize(heightField);
		final ArrayImg<FloatType, FloatArray> slice =
				ArrayImgs.floats(
						(float[])fpSlice.getPixels(),
						fpSlice.getWidth(),
						fpSlice.getHeight());
		maskSlice(slice, mask, new FloatType(Float.NaN));
		InpaintMasked.run(fpSlice);

		return Views.translate(
				slice,
				Intervals.minAsLongArray(mask));
	}


	private static <T extends RealType<T>, M extends RealType<M>> double[] sumWeightedValues(
			final IterableInterval<T> values,
			final IterableInterval<M> weights) {

		final RealSum valueSum = new RealSum();
		final RealSum weightSum = new RealSum();

		final Cursor<M> weightsCursor = weights.cursor();
		final Cursor<T> valuesCursor = values.cursor();
		while (valuesCursor.hasNext()) {
			final double weight = weightsCursor.next().getRealDouble();
			weightSum.add(weight);
			valueSum.add(valuesCursor.next().getRealDouble() * weight);

			if (Double.isNaN(valuesCursor.get().getRealDouble()))
				System.out.println(weight);
		}
		return new double[]{valueSum.getSum(), weightSum.getSum()};
	}


	private static <T extends RealType<T>, M extends RealType<M>> double weightedAverage(
			final IterableInterval<T> values,
			final IterableInterval<M> weights) {

		final double[] sums = sumWeightedValues(values, weights);
		return sums[0] / sums[1];
	}


	private static <T extends RealType<T>> double[] minMax(
			final IterableInterval<T> values) {

		final double[] minMax = new double[]{Double.MAX_VALUE, -Double.MAX_VALUE};

		for (final T value : values) {
			final double v = value.getRealDouble();
			if (minMax[0] > v) minMax[0] = v;
			if (minMax[1] < v) minMax[1] = v;
		}
		return minMax;
	}



	/**
	 * Extract a single surface.
	 *
	 * Stolen from https://github.com/JaneliaSciComp/SurfaceFit/blob/master/src/main/java/net/preibisch/surface/Test.java
	 *
	 * @param <T>
	 * @param cost
	 * @param maxDz max delta z, default = 1, constraint on the surface altitude change from one pixel to another
	 * @return
	 */
	private static <T extends RealType<T>> RandomAccessibleInterval<IntType> extractSurface(
			final RandomAccessibleInterval<T> cost,
			final int maxDz) {

		final int n = cost.numDimensions();

		assert (n == 3) :"number of dimensions = 3 required.";

		final MinCostZSurface<T> ZSurface_detector = new MinCostZSurface<T>();

		ZSurface_detector.Create_Surface_Graph(Views.zeroMin(cost), maxDz);

		ZSurface_detector.Process();

		final Img<IntType> heightField =  ZSurface_detector.get_Altitude_MapInt(1);

		return Views.translate(heightField, cost.min(0), cost.min(1));
	}


	/**
	 * Extract a single surface.
	 *
	 * Stolen from https://github.com/JaneliaSciComp/SurfaceFit/blob/master/src/main/java/net/preibisch/surface/Test.java
	 *
	 * @param <T>
	 * @param cost
	 * @param maxDz max delta z, default = 1, constraint on the surface altitude change from one pixel to another
	 * @param minDist Min_distance between surfaces (in pixel), default = 3
	 * @param maxDist Max_distance between surfaces (in pixel), default = 15
	 * @param numSurfaces number of surfaces
	 * @return
	 */
	private static <T extends RealType<T>> RandomAccessibleInterval<IntType>[] extractSurfaces(
			final RandomAccessibleInterval<T> cost,
			final int maxDz,
			final int minDist,
			final int maxDist,
			final int numSurfaces) {

		final int n = cost.numDimensions();

		assert (n == 3) :"number of dimensions = 3 required.";

		final MinCostZSurface<T> ZSurface_detector = new MinCostZSurface<T>();

		for (int i = 0; i < numSurfaces; ++i)
			ZSurface_detector.Create_Surface_Graph(Views.zeroMin(cost), maxDz);

		for (int i = 0; i < numSurfaces; ++i) {
			for (int j = i + 1; j < numSurfaces; ++j) {
				System.out.println("Adding constraints for " + (i + 1) + ", " + (j + 1));
				ZSurface_detector.Add_NoCrossing_Constraint_Between_Surfaces(i + 1, j + 1, minDist, maxDist);
			}
		}

		ZSurface_detector.Process();

		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<IntType>[] heightFields = new RandomAccessibleInterval[numSurfaces];

		for (int i = 0; i < numSurfaces; ++i) {
			System.out.println(i);

			final Img<IntType> heightMap =  ZSurface_detector.get_Altitude_MapInt(i + 1);
			heightFields[i] = Views.translate(heightMap, cost.min(0), cost.min(1));
		}

		return heightFields;
	}


	/**
	 *
	 * @param <T>
	 * @param <M>
	 * @param transformedCost
	 * @param mask serves as both mask and target interval (don't forget the offsets)
	 * @param heightFieldScaled
	 * @param offsetZScaledAvg
	 * @param padding
	 * @param maxStepSize
	 * @return
	 */
	private static <
					T extends RealType<T>,
					M extends RealType<M>> RandomAccessibleInterval<FloatType> calculateHeightField(
							final RandomAccessible<T> transformedCost,
							final RandomAccessibleInterval<M> mask,
							final RealRandomAccessible<DoubleType> heightFieldScaled,
							final double offsetZScaledAvg,
							final long padding,
							final int maxStepSize) {

		final long min = (long)Math.floor(offsetZScaledAvg - padding);

		final RandomAccessibleInterval<T> cropTransformCost = Views.interval(
				transformedCost,
				new long[] {mask.min(0), mask.min(1), min},
				new long[] {mask.max(0), mask.max(1), min + padding * 2 - 1});

		final RandomAccessibleInterval<FloatType> inpaintedCropTransformCost = inpaintCost(
				Converters.convert(
						cropTransformCost,
						(a, b) -> b.set(a.getRealFloat()),
						new FloatType()),
				mask);

		final RandomAccessibleInterval<IntType> heightFieldUpdate = extractSurface(
				inpaintedCropTransformCost,
				maxStepSize);

		System.out.println("height field update " + net.imglib2.util.Util.printInterval(heightFieldUpdate));

		final RandomAccessibleInterval<DoubleType> doubleFixedHeightField = Converters.convert(
				heightFieldUpdate,
				(a, b) -> b.setReal(a.get() - 1 - padding),
				new DoubleType());

		final RandomAccessibleInterval<FloatType> updatedMinField = Converters.convert(
			Views.collapseReal(
					Views.stack(
						Views.interval(
								Views.raster(heightFieldScaled),
								heightFieldUpdate),
						doubleFixedHeightField)),
			(a, b) -> b.set(a.get(0).getRealFloat() + a.get(1).getRealFloat()),
			new FloatType());

		return inpaintHeightField(
				updatedMinField,
				mask);
	}


	/**
	 * Generate an updated min height field for a cost function warped by
	 * a given min and max height field that are usually lower scale.
	 *
	 * @param <T>
	 * @param cost at target resolution
	 * @param mask serves as both mask and target interval in x,y (don't forget the offsets)
	 *
	 * @return
	 */
	public static <
					T extends RealType<T>,
					M extends RealType<M>,
					F extends RealType<F>> RandomAccessibleInterval<FloatType>[] initHeightFields(
			final RandomAccessibleInterval<T> cost,
			final RandomAccessibleInterval<M> mask,
			final int maxStepSize,
			final int minDistance,
			final int maxDistance,
			final int numSurfaces) {

		System.out.println( "initHeightFields" );
		System.out.println( "maxStepSize: " + maxStepSize);
		System.out.println( "minDistance:" + minDistance);
		System.out.println( "maxDistance: " + maxDistance );
		
		final long min = (long)Math.floor(cost.min(2));

		final RandomAccessibleInterval<IntType>[] intHeightFields = extractSurfaces(
				cost,
				maxStepSize,
				minDistance,
				maxDistance,
				numSurfaces);

		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<FloatType>[] heightFields = new RandomAccessibleInterval[numSurfaces];

		for (int i = 0; i < numSurfaces; ++i) {

			final RandomAccessibleInterval<FloatType> doubleFixedHeightField = Converters.convert(
					intHeightFields[i],
					(a, b) -> b.setReal(a.get() - 1 + min),
					new FloatType());

			heightFields[i] = inpaintHeightField(doubleFixedHeightField, mask);
		}

		return heightFields;
	}


	/**
	 * Generate an updated min height field for a cost function warped by
	 * a given min and max height field that are usually lower scale.
	 *
	 * @param <T>
	 * @param cost at target resolution
	 * @param mask serves as both mask and target interval in x,y (don't forget the offsets)
	 * @param minField in scaled and shifted z-coordinates according to scale
	 * @param maxField in scaled and shifted z-coordinates according to scale
	 * @param minAvg weighted average of minField
	 * @param maxAvg weighted average of maxField
	 * @param scale scale factors transforming minFIeld and maxField to cost
	 * @param padding range of the cost function around the prior min face
	 *
	 * @return
	 */
	public static <
					T extends RealType<T>,
					M extends RealType<M>,
					F extends RealType<F>> ValuePair<RandomAccessibleInterval<FloatType>, RandomAccessibleInterval<FloatType>> updateHeightFields(
			final RandomAccessibleInterval<T> cost,
			final RandomAccessibleInterval<M> mask,
			final RandomAccessibleInterval<F> minField,
			final RandomAccessibleInterval<F> maxField,
			final double minAvg,
			final double maxAvg,
			final double[] scale,
			final long padding,
			final int maxStepSize) {

		final RealRandomAccessible<DoubleType> minFieldScaled =
				Transform.scaleAndShiftHeightField(
						Transform.scaleAndShiftHeightFieldValues(
								minField,
								scale[2],
								0),
						Arrays.copyOf(scale, 2));

//		ImageJFunctions.show(Views.interval(Views.raster(minFieldScaled), cost), "min field scaled");

		final RealRandomAccessible<DoubleType> maxFieldScaled =
				Transform.scaleAndShiftHeightField(
						Transform.scaleAndShiftHeightFieldValues(
								maxField,
								scale[2],
								0),
						Arrays.copyOf(scale, 2));

//		ImageJFunctions.show(Views.interval(Views.raster(maxFieldScaled), cost), "max field scaled");

		final double offsetZScaledMinAvg = (minAvg + 0.5) * scale[2] - 0.5;
		final double offsetZScaledMaxAvg = (maxAvg + 0.5) * scale[2] - 0.5;

		final FlattenTransform<DoubleType> flatteningTransform =
				new FlattenTransform<>(
						minFieldScaled,
						maxFieldScaled,
						offsetZScaledMinAvg,
						offsetZScaledMaxAvg);

		final RealTransformRandomAccessible<T, ? extends RealTransform> transformedCost = RealViews.transform(
				Views.interpolate(
						Views.extendBorder(cost),
						new NLinearInterpolatorFactory<>()),
				flatteningTransform);

//		ImageJFunctions.show(Views.interval(Views.raster(transformedCost), cost), "transformed cost");


		final RandomAccessibleInterval<FloatType> updatedMinField = calculateHeightField(
				transformedCost,
				mask,
				minFieldScaled,
				offsetZScaledMinAvg,
				padding,
				maxStepSize);

		final RandomAccessibleInterval<FloatType> updatedMaxField = calculateHeightField(
				transformedCost,
				mask,
				maxFieldScaled,
				offsetZScaledMaxAvg,
				padding,
				maxStepSize);

		return new ValuePair<>(updatedMinField, updatedMaxField);
	}


	private static boolean maskEmpty(final Iterable<UnsignedByteType> mask) {

		for (final UnsignedByteType t : mask)
			if (t.get() != 0)
				return false;
		return true;
	}


	/**
	 * Update the height field for a block.
	 *
	 * @param n5CostPath n5 container for cost, input only
	 * @param n5FieldPath n5 container for height fields, both in put and output
	 * @param costDataset
	 * @param heightFieldGroup input height field group
	 * @param heightFieldGroupOutput output height field group
	 * @param blockMinOut 2D min coordinates of the out put block
	 * @param blockSizeOut 2D size of the output block
	 * @param blockPadding 2D padding of the output block for processing
	 * @param padding padding in z around the previous surface
	 * @param maxStepSize maximum z step size for surface update in output space
	 *
	 * @return value and weight sums of the updated height fields (can be all 0 if everything is masked)
	 *
	 * @throws IOException if something goes wrong with the n5 containers
	 */
	public static double[][] processBlock(
			final String n5CostPath,
			final String n5FieldPath,
			final String costDataset,
			final String heightFieldGroup,
			final String heightFieldGroupOutput,
			final long[] blockMinOut,
			final long[] blockSizeOut,
			final long[] blockPadding,
			final long padding,
			final int maxStepSize) throws IOException {

		final N5Reader n5Cost = isZarr( n5CostPath ) ? new N5ZarrReader( n5CostPath ) : new N5FSWriter(n5CostPath);
		final N5Writer n5Field = isZarr( n5FieldPath ) ? new N5ZarrWriter( n5FieldPath ) : new N5FSWriter(n5FieldPath);

		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<UnsignedByteType> fullCost =
		Views.permute(
				(RandomAccessibleInterval<UnsignedByteType>)wrap( N5Utils.open(n5Cost, costDataset) ),
				1,
				2);

		final long[] blockMin = new long[blockMinOut.length];
		Arrays.setAll(
				blockMin,
				i -> Math.max(0, blockMinOut[i] - blockPadding[i]));

		final long[] blockSize = new long[blockMin.length];
		Arrays.setAll(
				blockSize,
				i -> Math.min(
						fullCost.dimension(i) - blockMin[i],
						blockMinOut[i] + blockSizeOut[i] + blockPadding[i] - blockMin[i]));

		final RandomAccessibleInterval<UnsignedByteType> cost =
			Views.interval(
					fullCost,
					new long[] {
							blockMin[0],
							blockMin[1],
							0
					},
					new long[] {
							blockMin[0] + blockSize[0] - 1,
							blockMin[1] + blockSize[1] - 1,
							fullCost.max(2)});
		final RandomAccessibleInterval<UnsignedByteType> mask = costMask(cost);

		if (maskEmpty(Views.iterable(mask)))
			return new double[][] {{0, 0}, {0, 0}};

		final double[] downsamplingFactorsXZY = n5Cost.getAttribute(costDataset, "downsamplingFactors", double[].class);
		final double[] downsamplingFactors = new double[]{
				downsamplingFactorsXZY[0],
				downsamplingFactorsXZY[2],
				downsamplingFactorsXZY[1]};

		final RandomAccessibleInterval<FloatType> minField = N5Utils.open(n5Field, heightFieldGroup + "/min");
		final RandomAccessibleInterval<FloatType> maxField = N5Utils.open(n5Field, heightFieldGroup + "/max");
		final double minAvg = n5Field.getAttribute(heightFieldGroup + "/min", "avg", double.class);
		final double maxAvg = n5Field.getAttribute(heightFieldGroup + "/max", "avg", double.class);

		final double[] downsamplingHeightField = n5Field.getAttribute(heightFieldGroup, "downsamplingFactors", double[].class);
		final double[] scale = new double[] {
				downsamplingHeightField[0] / downsamplingFactors[0],
				downsamplingHeightField[1] / downsamplingFactors[1],
				downsamplingHeightField[2] / downsamplingFactors[2]};

		final double dzScale = downsamplingFactors[0] / downsamplingFactors[2];
		final int oldMaxStepSize = (int)Math.max(1, Math.round(dzScale * 0.25));
		final int oldPadding = 8 * (int)Math.round(scale[2]) * 2 + 1;

		System.out.println("step size: old = " + oldMaxStepSize + ", new = " + maxStepSize);
		System.out.println("padding: old = " + oldPadding + ", new = " + padding);

		System.out.println("scale " + Arrays.toString(scale));

		System.out.println("mask " + net.imglib2.util.Util.printInterval(mask));

		final ValuePair<RandomAccessibleInterval<FloatType>, RandomAccessibleInterval<FloatType>> fields = updateHeightFields(
				cost,
				mask,
				minField,
				maxField,
				minAvg,
				maxAvg,
				scale,
				padding,
				maxStepSize);

		final FinalInterval cropInterval = new FinalInterval(
				blockMinOut,
				new long[] {
						Math.min(mask.max(0), blockSizeOut[0] + blockMinOut[0] - 1),
						Math.min(mask.max(1), blockSizeOut[1] + blockMinOut[1] - 1)
				});

		System.out.println("crop interval " + net.imglib2.util.Util.printInterval(cropInterval) + " from " + net.imglib2.util.Util.printInterval(fields.getA()));

		final IntervalView<FloatType> minFieldOut = Views.interval(fields.getA(), cropInterval);
		final IntervalView<FloatType> maxFieldOut = Views.interval(fields.getB(), cropInterval);

		final String minFieldOutName = heightFieldGroupOutput + "/min";
		final String maxFieldOutName = heightFieldGroupOutput + "/max";

		N5Utils.saveBlock(
				minFieldOut,
				n5Field,
				minFieldOutName,
				n5Field.getDatasetAttributes(minFieldOutName));
		N5Utils.saveBlock(
				maxFieldOut,
				n5Field,
				maxFieldOutName,
				n5Field.getDatasetAttributes(maxFieldOutName));

		return new double[][] {
				sumWeightedValues(
						Views.flatIterable(minFieldOut),
						Views.flatIterable(mask)),
				sumWeightedValues(
						Views.flatIterable(maxFieldOut),
						Views.flatIterable(mask))};
	}


	/**
	 * Update the height field by splitting into blocks.
	 *
	 * @param n5CostPath n5 container for cost, input only
	 * @param n5FieldPath n5 container for height fields, both in put and output
	 * @param costDataset
	 * @param heightFieldGroup input height field group
	 * @param heightFieldGroupOutput output height field group
	 * @param blockSizeOut 2D size of the output block
	 * @param blockPadding 2D padding of the output block for processing
	 *
	 * @throws IOException if something goes wrong with the n5 containers
	 */
	public static void updateHeightFields(
			final JavaSparkContext sc,
			final String n5CostPath,
			final String n5FieldPath,
			final String costDataset,
			final String heightFieldGroup,
			final String heightFieldGroupOutput,
			final long[] blockSizeOut,
			final long[] blockPadding,
			final double maxDeltaZ,
			final int maxDeltaZTimes) throws IOException {

		final N5Reader n5Cost = isZarr( n5CostPath ) ? new N5ZarrReader( n5CostPath ) : new N5FSReader(n5CostPath);
		final N5Writer n5Field = isZarr( n5FieldPath ) ? new N5ZarrWriter( n5FieldPath ) : new N5FSWriter(n5FieldPath);

		final int[] blockSizeOutInt = new int[blockSizeOut.length];
		Arrays.setAll(blockSizeOutInt, i -> (int)blockSizeOut[i]);

		final long[] dimensionsXZY = n5Cost.getAttribute(costDataset, "dimensions", long[].class);
		if (dimensionsXZY == null) {
			throw new IllegalArgumentException("dimensions attribute missing from costDataset " + costDataset);
		}
		final long[] dimensions = new long[] {
				dimensionsXZY[0],
				dimensionsXZY[2],
				dimensionsXZY[1]};
		final double[] downsamplingFactorsXZY = n5Cost.getAttribute(costDataset, "downsamplingFactors", double[].class);
		if (downsamplingFactorsXZY == null) {
			throw new IllegalArgumentException("downsamplingFactors attribute missing from costDataset " + costDataset);
		}
		final double[] downsamplingFactors = new double[] {
				downsamplingFactorsXZY[0],
				downsamplingFactorsXZY[2],
				downsamplingFactorsXZY[1]};
		final double dzScale = downsamplingFactors[0] / downsamplingFactors[2];
		final int maxStepSize = (int)Math.max(1, Math.round(dzScale * maxDeltaZ));
		final int padding = Math.max(15, maxStepSize * maxDeltaZTimes + 1);

		System.out.println( "dzScale " + dzScale + ", downsampling of heightfield " + net.imglib2.util.Util.printCoordinates( downsamplingFactors ));
		System.out.println("max step size " + maxStepSize + ", z-padding with " + padding + "px");

		n5Field.createGroup(heightFieldGroupOutput);
		n5Field.setAttribute(heightFieldGroupOutput, "downsamplingFactors", downsamplingFactors);
		final DatasetAttributes attributes =
				new DatasetAttributes(
						Arrays.copyOf(dimensions, 2),
						blockSizeOutInt,
						DataType.FLOAT32,
						new GzipCompression());
		final String minDataset = heightFieldGroupOutput + "/min";
		final String maxDataset = heightFieldGroupOutput + "/max";
		n5Field.createDataset(
				minDataset,
				attributes);
		n5Field.createDataset(
				maxDataset,
				attributes);

		final JavaRDD<long[][]> grid = sc.parallelize(
				Grid.create(
						Arrays.copyOf(dimensions, 2),
						new int[] {
								(int)blockSizeOut[0],
								(int)blockSizeOut[1]}));
		final JavaRDD<double[][]> avgs =
				grid.map(cell ->
					processBlock(
							n5CostPath,
							n5FieldPath,
							costDataset,
							heightFieldGroup,
							heightFieldGroupOutput,
							cell[0],
							cell[1],
							blockPadding,
							padding,
							maxStepSize));

		final double[][] sumAvgs = avgs.reduce((a, b) -> {

			final double[][] c = new double[a.length][2];
			for (int i = 0; i < c.length; ++i) {
				c[i][0] = a[i][0] + b[i][0];
				c[i][1] = a[i][1] + b[i][1];
			}
			return c;
		});

		n5Field.setAttribute(minDataset, "avg", sumAvgs[0][0] / sumAvgs[0][1]);
		n5Field.setAttribute(maxDataset, "avg", sumAvgs[1][0] / sumAvgs[1][1]);
	}


	@SuppressWarnings("unchecked")
	public Void callSingle() throws IOException {

		new ImageJ();
		final N5Reader n5 = isZarr( n5Path ) ? new N5ZarrReader( n5Path ) : new N5FSReader(n5Path);

		//final SparkConf conf = new SparkConf().setAppName(getClass().getCanonicalName());
		//final JavaSparkContext sc = new JavaSparkContext(conf);
		//sc.setLogLevel("ERROR");


		/* visualization */

		/*
		 * raw data
		 */
		int numScales = 8;
		double[][] scales = null;
		boolean useVolatile = false;
		BdvOptions options = null;
		BdvStackSource<?> bdv = null;
		SharedQueue queue = null;
		RandomAccessibleInterval<UnsignedByteType>[] rawMipmaps = null;
		FinalVoxelDimensions voxelDimensions = null;

		if( useVisualization ) {
			final int numProc = Runtime.getRuntime().availableProcessors();
			queue = new SharedQueue(Math.min(24, Math.max(1, numProc - 2)));

			numScales = n5.list(rawGroup).length;
			scales = new double[numScales][];
			rawMipmaps = new RandomAccessibleInterval[numScales];
			for (int s = 0; s < numScales; ++s) {

				final String mipmapName = rawGroup + "/s" + s;
				rawMipmaps[s] = Views.permute((RandomAccessibleInterval<UnsignedByteType>) wrap( N5Utils.openVolatile(n5, mipmapName) ), 1, 2);
				double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
				if (scale == null)
					scale = new double[]{1, 1, 1};

				scales[s] = scale;
			}

			voxelDimensions = new FinalVoxelDimensions("px", 1, 1, 1);

			useVolatile = true;

			final RandomAccessibleIntervalMipmapSource<UnsignedByteType> rawMipmapSource = new RandomAccessibleIntervalMipmapSource<>(
					rawMipmaps,
					new UnsignedByteType(),
					scales,
					voxelDimensions,
					"raw");

			options = BdvOptions.options().screenScales(new double[]{0.5}).numRenderingThreads(10);

			bdv = null;

			bdv = Show.mipmapSource(useVolatile ? rawMipmapSource.asVolatile(queue) : rawMipmapSource, bdv, options.addTo(bdv));
		}



		/* initialize */
		double minAvg;
		double maxAvg;

		final double[] downsamplingFactors;

		RandomAccessibleInterval<FloatType> minField;
		RandomAccessibleInterval<FloatType> maxField;
		{
			final N5Writer n5Writer = isZarr( n5FieldPath ) ? new N5ZarrWriter( n5FieldPath ) : new N5FSWriter(n5FieldPath);

			final String dataset = inGroup + "/s" + firstScaleIndex;
			final RandomAccessibleInterval<UnsignedByteType> cost = wrap( N5Utils.openVolatile(n5, dataset) );
			final RandomAccessibleInterval<UnsignedByteType> permutedCost = skipPermute ? cost : Views.permute(cost, 1, 2);
			final RandomAccessibleInterval<UnsignedByteType> mask = costMask(permutedCost);
			final RandomAccessibleInterval<FloatType> inpaintedCost = inpaintCost(
					Converters.convert(
								permutedCost,
								(a, b) -> b.set(a.getRealFloat()),
								new FloatType()),
					mask);

			ImageJFunctions.show( cost ).setTitle( "cost" );
			ImageJFunctions.show( mask ).setTitle( "mask" );
			ImageJFunctions.show( inpaintedCost ).setTitle( "inpainted cost" );

			if ( skipPermute )
			{
				downsamplingFactors = n5.getAttribute(dataset, "downsamplingFactors", double[].class);
			}
			else
			{
				final double[] downsamplingFactorsXZY = n5.getAttribute(dataset, "downsamplingFactors", double[].class);

				downsamplingFactors = new double[ 3 ];
				downsamplingFactors[0] = downsamplingFactorsXZY[0];
				downsamplingFactors[1] = downsamplingFactorsXZY[2];
				downsamplingFactors[2] = downsamplingFactorsXZY[1];
			}
			final double dzScale = downsamplingFactors[0] / downsamplingFactors[2];

			System.out.println( "dzScale: " + downsamplingFactors[0] + "/" + downsamplingFactors[2 ] + "=" + dzScale );

			final RandomAccessibleInterval<FloatType>[] heightFields = initHeightFields(
					inpaintedCost,
					mask,
					(int)Math.max(1, Math.round(dzScale * initMaxDeltaZ)),
					(int)Math.ceil(minDistance / downsamplingFactors[2]),
					Math.min((int)inpaintedCost.dimension(2) - 1, (int)Math.ceil(maxDistance / downsamplingFactors[2])),
					2);

			minAvg = weightedAverage(Views.iterable(heightFields[0]), Views.iterable(mask));
			maxAvg = weightedAverage(Views.iterable(heightFields[1]), Views.iterable(mask));

			ImageJFunctions.show( heightFields[ 0 ] ).setTitle( "heightFields[ 0 ]" );
			ImageJFunctions.show( heightFields[ 1 ] ).setTitle( "heightFields[ 1 ]" );
			if (minAvg > maxAvg) {
				final double a = minAvg;
				minAvg = maxAvg;
				maxAvg = a;
				minField = heightFields[1];
				maxField = heightFields[0];
			} else {
				minField = heightFields[0];
				maxField = heightFields[1];
			}

			System.out.println(minAvg + ", " + maxAvg);

			final String groupName = outGroup + "/s" + firstScaleIndex;
			n5Writer.createGroup(groupName);
			n5Writer.setAttribute(groupName, "downsamplingFactors", downsamplingFactors);
			final String minDataset = groupName + "/min";
			N5Utils.save(minField, n5Writer, minDataset, new int[] {1024, 1024}, new GzipCompression());
			n5Writer.setAttribute(minDataset, "avg", minAvg);
			final String maxDataset = groupName + "/max";
			N5Utils.save(maxField, n5Writer, maxDataset, new int[] {1024, 1024}, new GzipCompression());
			n5Writer.setAttribute(maxDataset, "avg", maxAvg);
			SimpleMultiThreading.threadHaltUnClean();
		}

		for (int s = firstScaleIndex - 1; s >= lastScaleIndex; --s) {

			final String dataset = inGroup + "/s" + s;
			final RandomAccessibleInterval<UnsignedByteType> cost = wrap( N5Utils.openVolatile(n5, dataset) );
			final RandomAccessibleInterval<UnsignedByteType> permutedCost = Views.permute(cost, 1, 2);
			final RandomAccessibleInterval<UnsignedByteType> mask = costMask(permutedCost);
			final RandomAccessibleInterval<FloatType> inpaintedCost = inpaintCost(
					Converters.convert(
								permutedCost,
								(a, b) -> b.set(a.getRealFloat()),
								new FloatType()),
					mask);

			final double[] newDownsamplingFactorsXZY = n5.getAttribute(dataset, "downsamplingFactors", double[].class);
			final double[] scale = new double[] {
					downsamplingFactors[0] / newDownsamplingFactorsXZY[0],
					downsamplingFactors[1] / newDownsamplingFactorsXZY[2],
					downsamplingFactors[2] / newDownsamplingFactorsXZY[1]};
			downsamplingFactors[0] = newDownsamplingFactorsXZY[0];
			downsamplingFactors[1] = newDownsamplingFactorsXZY[2];
			downsamplingFactors[2] = newDownsamplingFactorsXZY[1];

			final double dzScale = downsamplingFactors[0] / downsamplingFactors[2];
			final int padding = 8 * (int)Math.round(scale[2]) * 2 + 1;

			System.out.println(dzScale);
			System.out.println(Arrays.toString(scale));
			System.out.println(Arrays.toString(downsamplingFactors));

			final ValuePair<RandomAccessibleInterval<FloatType>, RandomAccessibleInterval<FloatType>> updatedHeightFields = updateHeightFields(
					inpaintedCost,
					mask,
					minField,
					maxField,
					minAvg,
					maxAvg,
					scale,
					padding,
					(int)Math.max(1, Math.round(dzScale * maxDeltaZ)));
//					(int)Math.round(scale[2]) * 2);

			minField = updatedHeightFields.getA();
			maxField = updatedHeightFields.getB();

			ImageJFunctions.show(minField, "updated min height field " + s);
			ImageJFunctions.show(maxField, "updated max height field " + s);

			minAvg = weightedAverage(
					Views.flatIterable(minField),
					Views.flatIterable(mask));
			maxAvg = weightedAverage(
					Views.flatIterable(maxField),
					Views.flatIterable(mask));

			System.out.println(minAvg + ", " + maxAvg);



			/* visualization again ... */

			if( useVisualization ) {
				final FlattenTransform<DoubleType> flattenTransform = new FlattenTransform<>(
						Transform.scaleAndShiftHeightFieldAndValues(minField, downsamplingFactors),
						Transform.scaleAndShiftHeightFieldAndValues(maxField, downsamplingFactors),
						(minAvg + 0.5) * downsamplingFactors[2] - 0.5,
						(maxAvg + 0.5) * downsamplingFactors[2] - 0.5);

				final RandomAccessibleIntervalMipmapSource<UnsignedByteType> mipmapSource =
						Show.createTransformedMipmapSource(
								flattenTransform.inverse(),
								rawMipmaps,
								scales,
								voxelDimensions,
								"" + s);

				final Source<?> volatileMipmapSource;
				if (useVolatile)
					volatileMipmapSource = mipmapSource.asVolatile(queue);
				else
					volatileMipmapSource = mipmapSource;

				bdv = Show.mipmapSource(volatileMipmapSource, bdv, options.addTo(bdv));
			}
		}


		//sc.close();

		return null;
	}


//	@Override
//	public Void call() throws IOException {
	@SuppressWarnings("unchecked")
	public Void callSparkAndVisualization() throws IOException {

		if( useVisualization)
			new ImageJ();

		final N5Reader n5 = isZarr( n5Path ) ? new N5ZarrReader( n5Path ) : new N5FSReader(n5Path);

		final SparkConf conf = new SparkConf().setAppName(getClass().getCanonicalName());
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");


		/* visualization */

		/*
		 * raw data
		 */

		int numScales = 8;
		double[][] scales = null;
		boolean useVolatile = false;
		BdvOptions options = null;
		BdvStackSource<?> bdv = null;
		SharedQueue queue = null;
		RandomAccessibleInterval<UnsignedByteType>[] rawMipmaps = null;
		FinalVoxelDimensions voxelDimensions = null;

		if( useVisualization ) {
			final int numProc = Runtime.getRuntime().availableProcessors();
			queue = new SharedQueue(Math.min(24, Math.max(1, numProc - 2)));

			numScales = n5.list(rawGroup).length;
			scales = new double[numScales][];
			rawMipmaps = new RandomAccessibleInterval[numScales];
			for (int s = 0; s < numScales; ++s) {

				final String mipmapName = rawGroup + "/s" + s;
				rawMipmaps[s] = Views.permute((RandomAccessibleInterval<UnsignedByteType>) wrap( N5Utils.openVolatile(n5, mipmapName) ), 1, 2);
				double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
				if (scale == null)
					scale = new double[]{1, 1, 1};

				scales[s] = scale;
			}

			voxelDimensions = new FinalVoxelDimensions("px", 1, 1, 1);

			useVolatile = true;

			final RandomAccessibleIntervalMipmapSource<UnsignedByteType> rawMipmapSource = new RandomAccessibleIntervalMipmapSource<>(
					rawMipmaps,
					new UnsignedByteType(),
					scales,
					voxelDimensions,
					"raw");

			options = BdvOptions.options().screenScales(new double[]{0.5}).numRenderingThreads(10);

			bdv = null;

			bdv = Show.mipmapSource(useVolatile ? rawMipmapSource.asVolatile(queue) : rawMipmapSource, bdv, options.addTo(bdv));
		}




		/* initialize */
		double minAvg;
		double maxAvg;

		double[] downsamplingFactors = new double[3];

		RandomAccessibleInterval<FloatType> minField;
		RandomAccessibleInterval<FloatType> maxField;
		{
			final N5Writer n5Writer = isZarr( n5FieldPath ) ? new N5ZarrWriter( n5FieldPath ) : new N5FSWriter(n5FieldPath);

			final String dataset = inGroup + "/s" + firstScaleIndex;
			final RandomAccessibleInterval<UnsignedByteType> cost = wrap( N5Utils.openVolatile(n5, dataset) );
			final RandomAccessibleInterval<UnsignedByteType> permutedCost = skipPermute ? cost : Views.permute(cost, 1, 2);
			final RandomAccessibleInterval<UnsignedByteType> mask = costMask(permutedCost);
			final RandomAccessibleInterval<FloatType> inpaintedCost = inpaintCost(
					Converters.convert(
								permutedCost,
								(a, b) -> b.set(a.getRealFloat()),
								new FloatType()),
					mask);

			final double[] downsamplingFactorsXZY = n5.getAttribute(dataset, "downsamplingFactors", double[].class);
			if ( skipPermute )
			{
				downsamplingFactors[0] = downsamplingFactorsXZY[0];
				downsamplingFactors[1] = downsamplingFactorsXZY[1];
				downsamplingFactors[2] = downsamplingFactorsXZY[2];
			}
			else
			{
				downsamplingFactors[0] = downsamplingFactorsXZY[0];
				downsamplingFactors[1] = downsamplingFactorsXZY[2];
				downsamplingFactors[2] = downsamplingFactorsXZY[1];
			}
			final double dzScale = downsamplingFactors[0] / downsamplingFactors[2];

			final RandomAccessibleInterval<FloatType>[] heightFields = initHeightFields(
					inpaintedCost,
					mask,
					(int)Math.max(1, Math.round(dzScale * initMaxDeltaZ)),
					(int)Math.ceil(minDistance / downsamplingFactors[2]),
					Math.min((int)inpaintedCost.dimension(2) - 1, (int)Math.ceil(maxDistance / downsamplingFactors[2])),
					2);

			minAvg = weightedAverage(Views.flatIterable(heightFields[0]), Views.flatIterable(mask));
			maxAvg = weightedAverage(Views.flatIterable(heightFields[1]), Views.flatIterable(mask));

			System.out.println(minAvg + ", " + maxAvg);

			if (minAvg > maxAvg) {
				final double a = minAvg;
				minAvg = maxAvg;
				maxAvg = a;
				minField = heightFields[1];
				maxField = heightFields[0];
			} else {
				minField = heightFields[0];
				maxField = heightFields[1];
			}

			final String groupName = outGroup + "/s" + firstScaleIndex;
			n5Writer.createGroup(groupName);
			n5Writer.setAttribute(groupName, "downsamplingFactors", downsamplingFactors);
			final String minDataset = groupName + "/min";
			N5Utils.save(minField, n5Writer, minDataset, new int[] {1024, 1024}, new GzipCompression());
			n5Writer.setAttribute(minDataset, "avg", minAvg);
			final String maxDataset = groupName + "/max";
			N5Utils.save(maxField, n5Writer, maxDataset, new int[] {1024, 1024}, new GzipCompression());
			n5Writer.setAttribute(maxDataset, "avg", maxAvg);
		}

		for (int s = firstScaleIndex - 1; s >= lastScaleIndex; --s) {

			final long[] blockSize = new long[] {128, 128};
			final long[] blockPadding = new long[] {32, 32};

			updateHeightFields(
					sc,
					n5Path,
					n5FieldPath,
					inGroup + "/s" + s,
					outGroup + "/s" + (s + 1),
					outGroup + "/s" + s,
					blockSize,
					blockPadding,
					maxDeltaZ,
					2);


			/* visualization again ... */
			if( useVisualization ) {
				final N5FSReader n5Field = new N5FSReader(n5FieldPath);
				final String groupName = outGroup + "/s" + s;
				final String minFieldName = groupName + "/min";
				final String maxFieldName = groupName + "/max";

				minField = N5Utils.open(n5Field, minFieldName);
				maxField = N5Utils.open(n5Field, maxFieldName);

				downsamplingFactors = n5Field.getAttribute(groupName, "downsamplingFactors", double[].class);
				minAvg = n5Field.getAttribute(minFieldName, "avg", double.class);
				maxAvg = n5Field.getAttribute(maxFieldName, "avg", double.class);

				final FlattenTransform<DoubleType> flattenTransform = new FlattenTransform<>(
						Transform.scaleAndShiftHeightFieldAndValues(minField, downsamplingFactors),
						Transform.scaleAndShiftHeightFieldAndValues(maxField, downsamplingFactors),
						(minAvg + 0.5) * downsamplingFactors[2] - 0.5,
						(maxAvg + 0.5) * downsamplingFactors[2] - 0.5);

				final RandomAccessibleIntervalMipmapSource<UnsignedByteType> mipmapSource =
						Show.createTransformedMipmapSource(
								flattenTransform.inverse(),
								rawMipmaps,
								scales,
								voxelDimensions,
								"" + s);

				final Source<?> volatileMipmapSource;
				if (useVolatile)
					volatileMipmapSource = mipmapSource.asVolatile(queue);
				else
					volatileMipmapSource = mipmapSource;

				bdv = Show.mipmapSource(volatileMipmapSource, bdv, options.addTo(bdv));
			}
		}


		sc.close();

		return null;
	}


	//public Void callSpark() throws IOException {
	@Override
	public Void call() throws IOException {

		callSingle();
		SimpleMultiThreading.threadHaltUnClean();

		final SparkConf conf = new SparkConf().setAppName(getClass().getCanonicalName());
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		callWithSparkContext(sc);

		sc.close();

		return null;
	}

	// TODO: this should not be here, but is private in n5-utils
	public static boolean isZarr(final String containerPath) {

		return containerPath.toLowerCase().endsWith(".zarr") ||
				(Files.isDirectory(Paths.get(containerPath)) &&
						(Files.isRegularFile(Paths.get(containerPath, ".zarray")) ||
								Files.isRegularFile(Paths.get(containerPath, ".zgroup"))));
	}

	public static RandomAccessibleInterval<UnsignedByteType> wrap( final RandomAccessibleInterval in )
	{
		final Object t = Views.iterable( in ).firstElement();

		System.out.println( "datatype: " + t.getClass().getSimpleName() + ", first value=" + t );

		if ( UnsignedByteType.class.isInstance( t ) )
			return (RandomAccessibleInterval<UnsignedByteType>)in;
		else if ( IntegerType.class.isInstance( t ) )
			return Converters.convert( (RandomAccessibleInterval<IntegerType>)in, (i,o) -> o.setInteger( i.getInteger()), new UnsignedByteType() );
		else
			throw new RuntimeException( "Unsuported Type. Please implement Converter" );
	}

	public void callWithSparkContext(final JavaSparkContext sc)
			throws IOException {

		final N5Reader n5 = isZarr( n5Path ) ? new N5ZarrReader( n5Path ) : new N5FSReader(n5Path);

		/* initialize */
		double minAvg;
		double maxAvg;

		final double[] downsamplingFactors = new double[3];

		RandomAccessibleInterval<FloatType> minField;
		RandomAccessibleInterval<FloatType> maxField;
		{
			System.out.println( "Processing scale: " + firstScaleIndex );

			final N5Writer n5Writer = isZarr( n5FieldPath ) ? new N5ZarrWriter( n5FieldPath ) : new N5FSWriter(n5FieldPath);

			final String dataset = inGroup + "/s" + firstScaleIndex;
			final RandomAccessibleInterval<UnsignedByteType> cost = wrap( N5Utils.openVolatile(n5, dataset) );
			final RandomAccessibleInterval<UnsignedByteType> permutedCost = skipPermute ? cost : Views.permute(cost, 1, 2);
			final RandomAccessibleInterval<UnsignedByteType> mask = costMask(permutedCost);
			final RandomAccessibleInterval<FloatType> inpaintedCost = inpaintCost(
					Converters.convert(
								permutedCost,
								(a, b) -> b.set(a.getRealFloat()),
								new FloatType()),
					mask);

			final double[] downsamplingFactorsXZY = n5.getAttribute(dataset, "downsamplingFactors", double[].class);
			if ( skipPermute )
			{
				downsamplingFactors[0] = downsamplingFactorsXZY[0];
				downsamplingFactors[1] = downsamplingFactorsXZY[1];
				downsamplingFactors[2] = downsamplingFactorsXZY[2];
			}
			else
			{
				downsamplingFactors[0] = downsamplingFactorsXZY[0];
				downsamplingFactors[1] = downsamplingFactorsXZY[2];
				downsamplingFactors[2] = downsamplingFactorsXZY[1];
			}
			final double dzScale = downsamplingFactors[0] / downsamplingFactors[2];

			System.out.println( "dzScale " + dzScale + ", downsampling of heightfield " + net.imglib2.util.Util.printCoordinates( downsamplingFactors ));

			final RandomAccessibleInterval<FloatType>[] heightFields = initHeightFields(
					inpaintedCost,
					mask,
					(int)Math.max(1, Math.round(dzScale * initMaxDeltaZ)),
					(int)Math.ceil(minDistance / downsamplingFactors[2]),
					Math.min((int)inpaintedCost.dimension(2) - 1, (int)Math.ceil(maxDistance / downsamplingFactors[2])),
					2);

			minAvg = weightedAverage(Views.flatIterable(heightFields[0]), Views.flatIterable(mask));
			maxAvg = weightedAverage(Views.flatIterable(heightFields[1]), Views.flatIterable(mask));

			System.out.println( "minAvg: " + minAvg + ", maxAvg: " + maxAvg);

			if (minAvg > maxAvg) {
				final double a = minAvg;
				minAvg = maxAvg;
				maxAvg = a;
				minField = heightFields[1];
				maxField = heightFields[0];
			} else {
				minField = heightFields[0];
				maxField = heightFields[1];
			}

			final String groupName = outGroup + "/s" + firstScaleIndex;
			n5Writer.createGroup(groupName);
			n5Writer.setAttribute(groupName, "downsamplingFactors", downsamplingFactors);
			final String minDataset = groupName + "/min";
			N5Utils.save(minField, n5Writer, minDataset, new int[] {1024, 1024}, new GzipCompression());
			n5Writer.setAttribute(minDataset, "avg", minAvg);
			final String maxDataset = groupName + "/max";
			N5Utils.save(maxField, n5Writer, maxDataset, new int[] {1024, 1024}, new GzipCompression());
			n5Writer.setAttribute(maxDataset, "avg", maxAvg);
		}

		for (int s = firstScaleIndex - 1; s >= lastScaleIndex; --s) {

			System.out.println( "Processing scale: " + s );

			final long[] blockSize = new long[] {128, 128};
			final long[] blockPadding = new long[] {32, 32};

			updateHeightFields(
					sc,
					n5Path, // TODO
					n5FieldPath, // TODO
					inGroup + "/s" + s,
					outGroup + "/s" + (s + 1),
					outGroup + "/s" + s,
					blockSize,
					blockPadding,
					maxDeltaZ,
					2);
		}
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new SparkSurfaceFit(), args);
	}
}

