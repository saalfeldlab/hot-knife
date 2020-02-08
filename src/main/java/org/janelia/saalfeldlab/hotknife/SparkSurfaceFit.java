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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

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
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.InverseRealTransform;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
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

	@Option(names = {"-i", "--n5CostInput"}, required = true, description = "N5 input group for cost, e.g. /cost-0")
	private String inGroup = null;

	@Option(names = {"-o", "--n5SurfaceOutput"}, required = true, description = "N5 output group for , e.g. /surface-1")
	private String outGroup = null;

	@Option(names = {"-s", "--scale"}, description = "scale index, e.g. 10")
	private int scaleIndex = 0;

	/**
	 * Mask (0) all voxel z-columns with all equal cost.
	 *
	 * @param <T>
	 * @param cost
	 * @return
	 */
	private static <T extends Type<T>> RandomAccessibleInterval<UnsignedByteType> costMask(final RandomAccessibleInterval<T> cost) {

		final ArrayImg<UnsignedByteType, ByteArray> mask = ArrayImgs.unsignedBytes(cost.dimension(0), cost.dimension(1));
		final T reference = net.imglib2.util.Util.getTypeFromInterval(cost).createVariable();
		final ArrayCursor<UnsignedByteType> maskCursor = mask.cursor();
		final long depth = cost.dimension(2);
		final Cursor<GenericComposite<T>> costCursor = Views.flatIterable(Views.collapse(cost)).cursor();
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


	private static RandomAccessibleInterval<FloatType> inpaintCost(
			final RandomAccessibleInterval<FloatType> cost,
			final RandomAccessibleInterval<UnsignedByteType> mask) {

		final ArrayList<ArrayImg<FloatType, FloatArray>> slices = new ArrayList<>();
		for (int z = 0; z < cost.dimension(2); ++z) {
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

		return slice;
	}


	private static <T extends RealType<T>, M extends RealType<M>> double weightedAverage(
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
		return valueSum.getSum() / weightSum.getSum();
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
		//ZSurface_detector.Create_Surface_Graph( cost_orig, max_dz );
		//ZSurface_detector.Add_NoCrossing_Constraint_Between_Surfaces(1, 2, min_dist, max_dist);

		ZSurface_detector.Process();
//		final float maxFlow = ZSurface_detector.getMaxFlow();

		final Img<IntType> depth_map1 =  ZSurface_detector.get_Altitude_MapInt(1);
		//Img<FloatType> depth_map2 =  ZSurface_detector.get_Altitude_Map(2);

		return Views.translate(depth_map1, cost.min(0), cost.min(1));
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

		ZSurface_detector.Create_Surface_Graph(Views.zeroMin(cost), maxDz);
		for (int i = 0; i < numSurfaces; ++i) {
			for (int j = i + 1; j < numSurfaces; ++j) {
				ZSurface_detector.Add_NoCrossing_Constraint_Between_Surfaces(i, j, minDist, maxDist);
			}
		}

		ZSurface_detector.Process();

		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<IntType>[] heightMaps = (RandomAccessibleInterval<IntType>[])new RandomAccessibleInterval[numSurfaces];

		for (int i = 0; i < numSurfaces; ++i) {
			final Img<IntType> heightMap =  ZSurface_detector.get_Altitude_MapInt(i);
			heightMaps[i] = Views.translate(heightMap, cost.min(0), cost.min(1));
		}

		return heightMaps;
	}


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

		final IntervalView<T> cropTransformCost = Views.offsetInterval(
				transformedCost,
				new long[] {mask.min(0), mask.min(1), min},
				new long[] {mask.dimension(0), mask.dimension(1), padding * 2});

		final RandomAccessibleInterval<IntType> heightFieldUpdate = extractSurface(
				cropTransformCost,
				maxStepSize);

		final RandomAccessibleInterval<DoubleType> doubleFixedHeightField = Converters.convert(
				heightFieldUpdate,
				(a, b) -> b.setReal(a.get() - 1 - padding),
				new DoubleType());

		final RandomAccessibleInterval<FloatType> updatedMinField = Converters.convert(
			Views.collapseReal(
					Views.stack(
						Views.offsetInterval(
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
					F extends RealType<F>> ValuePair<RandomAccessibleInterval<FloatType>, RandomAccessibleInterval<FloatType>> updateMinHeightFields(
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

		final RealTransformRandomAccessible<T, InverseRealTransform> transformedCost = RealViews.transform(
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


	public static void processBlock(
			final String n5CostPath,
			final String n5FieldPath,
			final String costDataset,
			final String heightFieldGroup,
			final long[] blockMin,
			final long[] blockSize,
			final long padding,
			final int maxStepSize) throws IOException {

		final N5Reader n5Cost = new N5FSReader(n5CostPath);
		final N5Writer n5Field = new N5FSWriter(n5FieldPath);

		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<UnsignedByteType> cost =
			Views.interval(
					Views.permute(
							(RandomAccessibleInterval<UnsignedByteType>)N5Utils.open(n5Cost, costDataset),
							1,
							2),
					blockMin,
					new long[] {
							blockMin[0] + blockSize[0] - 1,
							blockMin[1] + blockSize[1] - 1,
							blockMin[2] + blockSize[2] - 1});
		final RandomAccessibleInterval<UnsignedByteType> mask = costMask(cost);

		if (maskEmpty(Views.iterable(mask)))
			return;

		final RandomAccessibleInterval<FloatType> inpaintedCost = inpaintCost(
				Converters.convert(
							cost,
							(a, b) -> b.set(a.getRealFloat()),
							new FloatType()),
				mask);

		final double[] downsamplingFactorsXZY = n5Cost.getAttribute(costDataset, "downsamplingFactors", double[].class);
		final double[] downsamplingFactors = new double[]{
				downsamplingFactorsXZY[0],
				downsamplingFactorsXZY[2],
				downsamplingFactorsXZY[1]};

		final RandomAccessibleInterval<FloatType> minField = N5Utils.open(n5Field, heightFieldGroup + "/min");
		final RandomAccessibleInterval<FloatType> maxField = N5Utils.open(n5Field, heightFieldGroup + "/max");
		final double minAvg = n5Field.getAttribute(heightFieldGroup + "/min", "avg", double.class);
		final double maxAvg = n5Field.getAttribute(heightFieldGroup + "/max", "avg", double.class);

		final double[] downsamplingHeightField = n5Cost.getAttribute(heightFieldGroup, "downsamplingFactors", double[].class);
		final double[] scale = new double[] {
				downsamplingFactors[0] / downsamplingHeightField[0],
				downsamplingFactors[1] / downsamplingHeightField[1],
				downsamplingFactors[2] / downsamplingHeightField[2]};

		final ValuePair<RandomAccessibleInterval<FloatType>, RandomAccessibleInterval<FloatType>> fields = updateMinHeightFields(
				inpaintedCost,
				mask,
				minField,
				maxField,
				minAvg,
				maxAvg,
				scale,
				padding,
				maxStepSize);



//TODO complete... save and stuff





	}


	@Override
	public Void call() throws IOException {

		final N5Reader n5 = new N5FSReader(n5Path);

		final SparkConf conf = new SparkConf().setAppName(getClass().getCanonicalName());
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");


		/* visualization */

		/*
		 * raw data
		 */
		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.min(24, Math.max(1, numProc - 2)));

		final String rawGroup = "/zcorr/Sec06___20200130_110551";

		final int numScales = n5.list(rawGroup).length;
		final double[][] scales = new double[numScales][];
		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<UnsignedByteType>[] rawMipmaps = new RandomAccessibleInterval[numScales];
		for (int s = 0; s < numScales; ++s) {

			final String mipmapName = rawGroup + "/s" + s;
			rawMipmaps[s] = N5Utils.openVolatile(n5, mipmapName);
			double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
			if (scale == null)
				scale = new double[] {1, 1, 1};

			scales[s] = scale;
		}

		final FinalVoxelDimensions voxelDimensions = new FinalVoxelDimensions("px", 1, 1, 1);

		final boolean useVolatile = true;

		final RandomAccessibleIntervalMipmapSource<UnsignedByteType> rawMipmapSource = new RandomAccessibleIntervalMipmapSource<>(
				rawMipmaps,
				new UnsignedByteType(),
				scales,
				voxelDimensions,
				"raw");

		final BdvOptions options = BdvOptions.options().screenScales(new double[] {0.5}).numRenderingThreads(10);

		BdvStackSource<?> bdv = null;

		bdv = Show.mipmapSource(useVolatile ? rawMipmapSource.asVolatile(queue) : rawMipmapSource, bdv, options.addTo(bdv));




		/* initialize */
		final long[] initDimensions = n5.getAttribute(inGroup + "/s" + scaleIndex, "dimensions", long[].class);

		double minAvg = initDimensions[1] / 4;
		double maxAvg = initDimensions[1] - minAvg - 1;

		final double[] downsamplingFactorsXZY = n5.getAttribute(inGroup + "/s" + scaleIndex, "downsamplingFactors", double[].class);
		final double[] downsamplingFactors = new double[]{
				downsamplingFactorsXZY[0],
				downsamplingFactorsXZY[2],
				downsamplingFactorsXZY[1]};

		final float fMinAvg = (float)minAvg;
		final float fMaxAvg = (float)maxAvg;

		RandomAccessibleInterval<FloatType> minField =
				Views.offsetInterval(
						new FunctionRandomAccessible<>(
							2,
							(a, b) -> b.set(fMinAvg),
							FloatType::new),
						new long[3],
						new long[] {
								initDimensions[0],
								initDimensions[2],
								initDimensions[1]});

		RandomAccessibleInterval<FloatType> maxField =
				Views.offsetInterval(
						new FunctionRandomAccessible<>(
							2,
							(a, b) -> b.set(fMaxAvg),
							FloatType::new),
						new long[3],
						new long[] {
								initDimensions[0],
								initDimensions[2],
								initDimensions[1]});

		int padding = (int)Math.round(minAvg);

		for (int s = scaleIndex; s > scaleIndex - 7; --s) {

			final String dataset = inGroup + "/s" + s;
			final RandomAccessibleInterval<UnsignedByteType> cost = N5Utils.openVolatile(n5, dataset);
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

			System.out.println(dzScale);
			System.out.println(Arrays.toString(scale));
			System.out.println(Arrays.toString(downsamplingFactors));

			final ValuePair<RandomAccessibleInterval<FloatType>, RandomAccessibleInterval<FloatType>> updatedHeightFields = updateMinHeightFields(
					inpaintedCost,
					mask,
					minField,
					maxField,
					minAvg,
					maxAvg,
					scale,
					padding,
					(int)Math.round(dzScale * 0.5));
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

			System.out.println(minAvg);
			System.out.println(maxAvg);

			padding = 8 * (int)Math.round(scale[2]) * 2 + 1;



			/* visualization again ... */

			final FlattenTransform<DoubleType> flattenTransform = new FlattenTransform<>(
					Transform.scaleAndShiftHeightFieldAndValues(minField, downsamplingFactors),
					Transform.scaleAndShiftHeightFieldAndValues(maxField, downsamplingFactors),
					(minAvg + 0.5) * downsamplingFactors[2] - 0.5,
					(maxAvg + 0.5) * downsamplingFactors[2] - 0.5);
			final AffineTransform3D permutation = new AffineTransform3D();
			permutation.set(
					1, 0, 0, 0,
					0, 0, 1, 0,
					0, 1, 0, 0);
			final RealTransformSequence tfs = new RealTransformSequence();
			tfs.add(permutation);
			tfs.add(flattenTransform.inverse());

			final RandomAccessibleIntervalMipmapSource<UnsignedByteType> mipmapSource = Show.createTransformedMipmapSource(tfs, rawMipmaps, scales, voxelDimensions, "" + s);

			final Source<?> volatileMipmapSource;
			if (useVolatile)
				volatileMipmapSource = mipmapSource.asVolatile(queue);
			else
				volatileMipmapSource = mipmapSource;

			bdv = Show.mipmapSource(volatileMipmapSource, bdv, options.addTo(bdv));
		}


		sc.close();

		return null;
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		new ImageJ();

		CommandLine.call(new SparkSurfaceFit(), args);
	}
}

