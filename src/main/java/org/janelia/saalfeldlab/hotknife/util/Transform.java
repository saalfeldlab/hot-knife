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
package org.janelia.saalfeldlab.hotknife.util;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.ConstantRandomAccessible;
import mpicbg.models.Affine2D;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.InterpolatedModel;
import mpicbg.models.Model;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.position.RealPositionRealRandomAccessible;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.InvertibleRealTransform;
import net.imglib2.realtransform.PositionFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale;
import net.imglib2.realtransform.Scale2D;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Transform {

	private Transform() {}

	@SuppressWarnings("serial")
	public static abstract class AbstractInterpolatedModelSupplier<A extends Model<A>, B extends Model<B>, C extends InterpolatedModel<A, B, C>> implements Supplier<C>, Serializable {

		protected final Supplier<A> aSupplier;
		protected final Supplier<B> bSupplier;
		protected final double lambda;

		public <SA extends Supplier<A> & Serializable, SB extends Supplier<B> & Serializable> AbstractInterpolatedModelSupplier(
				final SA aSupplier,
				final SB bSupplier,
				final double lambda) {

			this.aSupplier = aSupplier;
			this.bSupplier = bSupplier;
			this.lambda = lambda;
		}
	}

	@SuppressWarnings("serial")
	public static class InterpolatedModelSupplier<A extends Model<A>, B extends Model<B>, C extends InterpolatedModel<A, B, C>> extends AbstractInterpolatedModelSupplier<A, B, C>{

		public <SA extends Supplier<A> & Serializable, SB extends Supplier<B> & Serializable> InterpolatedModelSupplier(
				final SA aSupplier,
				final SB bSupplier,
				final double lambda) {

			super(aSupplier, bSupplier, lambda);
		}

		@SuppressWarnings("unchecked")
		@Override
		public C get() {

			return (C)new InterpolatedModel<>(aSupplier.get(), bSupplier.get(), lambda);
		}
	}

	@SuppressWarnings("serial")
	public static class InterpolatedAffineModel2DSupplier<A extends Model<A> & Affine2D<A>, B extends Model<B> & Affine2D<B>> extends AbstractInterpolatedModelSupplier<A, B, InterpolatedAffineModel2D<A, B>> {

		public <SA extends Supplier<A> & Serializable, SB extends Supplier<B> & Serializable> InterpolatedAffineModel2DSupplier(
				final SA aSupplier,
				final SB bSupplier,
				final double lambda) {

			super(aSupplier, bSupplier, lambda);
		}

		@Override
		public InterpolatedAffineModel2D<A, B> get() {

			return new InterpolatedAffineModel2D<>(aSupplier.get(), bSupplier.get(), lambda);
		}
	}

	/**
	 * Create a {@link RandomAccessibleInterval} from a source
	 * {@link RandomAccessibleInterval} and an {@link AffineTransform} mapping
	 * coordinates from source space into target space (forward transform).
	 *
	 * @param source
	 * @param targetInterval
	 * @param transform
	 * @return
	 */
	public static <T extends NumericType<T>> RandomAccessibleInterval<T> createAffineTransformedInterval(
			final RandomAccessibleInterval<T> source,
			final Interval targetInterval,
			final AffineGet transform,
			final T background) {

		return Views.interval(
				RealViews.affine(
						Views.interpolate(
								Views.extendValue(source, background),
								new ClampingNLinearInterpolatorFactory<>()),
						transform),
				targetInterval);
	}

	/**
	 * Create a {@link RandomAccessibleInterval} binary mask for an
	 * {@link Interval} transformed by an {@link AffineTransform}
	 * (forward transform).
	 *
	 * @param sourceInterval
	 * @param targetInterval
	 * @param transform
	 * @return
	 */
	public static RandomAccessibleInterval<UnsignedByteType> createAffineTransformedMask(
			final Interval sourceInterval,
			final Interval targetInterval,
			final AffineGet transform) {

		return Views.interval(
				RealViews.affine(
						Views.interpolate(
								Views.extendValue(
										Views.interval(
												new ConstantRandomAccessible<>(new UnsignedByteType(1), 2),
												sourceInterval),
										new UnsignedByteType(0)),
								new NearestNeighborInterpolatorFactory<>()),
						transform),
				targetInterval);
	}

	/**
	 * Create a {@link RandomAccessibleInterval} from a source
	 * {@link RandomAccessibleInterval} and a {@link RealTransform} mapping
	 * coordinates from target space into source space (inverse transform).
	 *
	 * @param source
	 * @param targetInterval
	 * @param transformFromSource
	 * @return
	 */
	public static <T extends NumericType<T>> RandomAccessibleInterval<T> createTransformedInterval(
			final RandomAccessibleInterval<T> source,
			final Interval targetInterval,
			final RealTransform transformFromSource,
			final T background) {

		return Views.interval(
				new RealTransformRandomAccessible<>(
						Views.interpolate(
								Views.extendValue(source, background),
								new ClampingNLinearInterpolatorFactory<>()),
						transformFromSource),
				targetInterval);
	}

	/**
	 * Creates an affine transform from a world scale affine transform to be
	 * used in downscaled space.
	 *
	 * @param affine
	 * @param scaleIndex
	 * @return
	 */
	static public AffineTransform2D createScaledAffine2D(
			final AffineTransform2D affine,
			final double scaleFactor) {

		final Scale2D scale = new Scale2D(scaleFactor, scaleFactor);
		final AffineTransform2D targetTransform = new AffineTransform2D();
		targetTransform.concatenate(scale.inverse());
		targetTransform.concatenate(affine);
		targetTransform.concatenate(scale);

		return targetTransform;
	}

	/**
	 * Creates a scaled RealTransform.
	 *
	 * @param affine
	 * @param scaleFactor
	 * @return
	 */
	static public RealTransform createScaledRealTransform(
			final RealTransform transform,
			final double scaleFactor) {

		if (AffineTransform2D.class.isInstance(transform))
			return createScaledAffine2D((AffineTransform2D)transform, scaleFactor);

		final double[] scaleUp = new double[transform.numSourceDimensions()];
		final double[] scaleDown = new double[transform.numTargetDimensions()];
		Arrays.fill(scaleUp, scaleFactor);
		Arrays.fill(scaleDown, 1.0 / scaleFactor);
		final RealTransformSequence targetTransform = new RealTransformSequence();
		targetTransform.add(new Scale(scaleUp));
		targetTransform.add(transform);
		targetTransform.add(new Scale(scaleDown));

		return targetTransform;
	}

	/**
	 * Creates an affine transform from a world scale affine transform to be
	 * used in downscaled space.
	 *
	 * @param affine
	 * @param scaleIndex
	 * @return
	 */
	static public AffineTransform2D createScaledAffine2D(
			final AffineTransform2D affine,
			final int scaleIndex) {

		final int scaleFactor = 1 << scaleIndex;
		return createScaledAffine2D(affine, (double)scaleFactor);
	}

	/**
	 * Creates a scaled RealTransform.
	 *
	 * @param affine
	 * @param scaleIndex
	 * @return
	 */
	static public RealTransform createScaledRealTransform(
			final RealTransform transform,
			final int scaleIndex) {

		final int scaleFactor = 1 << scaleIndex;
		return createScaledRealTransform(transform, (double)scaleFactor);
	}

	/**
	 * Creates a virtual position field for a single dimension <em>d</em>
	 * transferred by a {@link RealTransform}.
	 *
	 * @param transform
	 * @param d
	 * @return
	 */
	public static RealRandomAccessible<DoubleType> createRealPositions(
			final RealTransform transform,
			final int d) {

		return new RealTransformRealRandomAccessible<>(
				new RealPositionRealRandomAccessible(transform.numTargetDimensions(), d),
				transform);
	}

	/**
	 * Creates a virtual position raster for all dimensions.  The size of the
	 * raster is [sizeof(interval),interval.numDimensions()].
	 *
	 * @param transform
	 * @param interval
	 * @return
	 */
	public static RandomAccessibleInterval<DoubleType> createPositionField(
			final RealTransform transform,
			final Interval interval) {

		final ArrayList<RandomAccessibleInterval<DoubleType>> dFields = new ArrayList<>();
		for (int d = 0; d < interval.numDimensions(); ++d) {
				final RandomAccessibleInterval< DoubleType > dField =
						Views.interval(
							Views.raster(createRealPositions(transform, d)),
							interval);
				dFields.add(dField);
		}
		return Views.stack(dFields);
	}

	/**
	 * Creates a {@link RealTransform} from a positionField raster.  The last
	 * dimension of the input raster enumerates the dimensions, i.e. the input
	 * raster is a stack of position lookups, one slice for each dimension.
	 *
	 * Accordingly, the source and target dimensionality of the resulting
	 * {@link RealTransform} is
	 * {@link RandomAccessibleInterval#numDimensions() positionField.numDimensions()} - 1
	 * .
	 *
	 * @param positionField
	 * @return
	 */
	public static <T extends RealType<T>> PositionFieldTransform<T> createPositionFieldTransform(final RandomAccessibleInterval<T> positionField) {

		final int n = positionField.numDimensions() - 1;

		@SuppressWarnings("unchecked")
		final RealRandomAccess<T>[] positionAccesses = new RealRandomAccess[(int)positionField.dimension(n)];
		Arrays.setAll(
				positionAccesses,
				d -> Views.interpolate(
						Views.extendBorder(
								Views.hyperSlice(positionField, n, d)),
						new NLinearInterpolatorFactory<>()).realRandomAccess());

		return new PositionFieldTransform<>(positionAccesses);
	}

	/**
	 * 2D boundaries approximated by only testing transformed corner coordinates.
	 *
	 * @param min
	 * @param max
	 * @param scaleIndex
	 * @param transform
	 * @return
	 */
	public static double[][] bounds(
			final double[] max,
			final InvertibleRealTransform transform) {

		final double[] c00 = new double[]{0, 0};
		final double[] c01 = new double[]{0, max[1]};
		final double[] c10 = new double[]{max[0], 0};
		final double[] c11 = new double[]{max[0], max[1]};

		transform.applyInverse(c00, c00);
		transform.applyInverse(c01, c01);
		transform.applyInverse(c10, c10);
		transform.applyInverse(c11, c11);

		final double[] boundsMin = c00.clone();
		boundsMin[0] = Math.min(boundsMin[0], c01[0]);
		boundsMin[0] = Math.min(boundsMin[0], c10[0]);
		boundsMin[0] = Math.min(boundsMin[0], c11[0]);

		boundsMin[1] = Math.min(boundsMin[1], c01[1]);
		boundsMin[1] = Math.min(boundsMin[1], c10[1]);
		boundsMin[1] = Math.min(boundsMin[1], c11[1]);

		final double[] boundsMax = c00.clone();
		boundsMax[0] = Math.max(boundsMax[0], c01[0]);
		boundsMax[0] = Math.max(boundsMax[0], c10[0]);
		boundsMax[0] = Math.max(boundsMax[0], c11[0]);

		boundsMax[1] = Math.max(boundsMax[1], c01[1]);
		boundsMax[1] = Math.max(boundsMax[1], c10[1]);
		boundsMax[1] = Math.max(boundsMax[1], c11[1]);

		return new double[][]{boundsMin, boundsMax};
	}

	/**
	 * 2D boundaries approximated by only testing transformed corner coordinates.
	 *
	 * @param n5Path
	 * @param datasetNames
	 * @param scaleIndex
	 * @param transforms
	 * @return
	 * @throws IOException
	 */
	public static double[][] bounds(
			final String n5Path,
			final List<String> datasetNames,
			final int scaleIndex,
			final List<? extends InvertibleRealTransform> transforms) throws IOException {

		final N5Reader n5Reader = new N5FSReader(n5Path);

		final double[] min = new double[2];
		final double[] max = new double[2];
		Arrays.fill(min, Double.MAX_VALUE);
		Arrays.fill(max, -Double.MAX_VALUE);

		for (int i = 0; i < datasetNames.size(); ++i) {
			final DatasetAttributes attributes = n5Reader.getDatasetAttributes(datasetNames.get(i) + "/s" + scaleIndex);
			final double[] datasetMax = new double[2];
			datasetMax[0] = attributes.getDimensions()[0];
			datasetMax[1] = attributes.getDimensions()[1];

			final double[][] datasetBounds = bounds(datasetMax, transforms.get(i));
			min[0] = Math.min(min[0], datasetBounds[0][0]);
			min[1] = Math.min(min[1], datasetBounds[0][1]);
			max[0] = Math.max(max[0], datasetBounds[1][0]);
			max[1] = Math.max(max[1], datasetBounds[1][1]);
		}

		return new double[][]{min, max};
	}

	public static RealTransform loadScaledTransform(
			final N5Reader n5,
			final String datasetName,
			final double transformScale,
			final double[] boundsMin) throws IOException {

		final RandomAccessibleInterval<DoubleType> positionField = N5Utils.open(n5, datasetName);
		final int n = positionField.numDimensions() - 1;
		final long[] translation = Arrays.copyOf(Grid.floorScaled(boundsMin, transformScale), n + 1);
		final PositionFieldTransform<DoubleType> transform = Transform.createPositionFieldTransform(
				Views.translate(positionField, translation));
		return createScaledRealTransform(transform, transformScale);
	}

	public static RealTransform loadScaledTransform(
			final N5Reader n5,
			final String datasetName) throws IOException {

		final double[] boundsMin = n5.getAttribute(datasetName, "boundsMin", double[].class);
		final double transformScale = n5.getAttribute(datasetName, "scale", double.class);
		return loadScaledTransform(n5, datasetName, transformScale, boundsMin);
	}


	/**
	 * Saves a transform as a position field in an N5 dataset
	 *
	 * @param n5
	 * @param datasetName
	 * @param transform
	 * @param transformScale
	 * @param boundsMin
	 * @param boundsMax
	 * @throws IOException
	 */
	public static void saveScaledTransform(
			final N5Writer n5,
			final String datasetName,
			final RealTransform transform,
			final double transformScale,
			final double[] boundsMin,
			final double[] boundsMax) throws IOException {

		final int n = transform.numSourceDimensions();
		final RealTransform scaledTransform = Transform.createScaledRealTransform(transform, 1.0 / transformScale);
		final RandomAccessibleInterval<DoubleType> positionField =
				Transform.createPositionField(
						scaledTransform,
						new FinalInterval(
								Grid.floorScaled(boundsMin, transformScale),
								Grid.ceilScaled(boundsMax, transformScale)));
		final int[] blockSize = new int[n + 1];
		Arrays.fill(blockSize, 1024);
		blockSize[n] = n;
		N5Utils.save(positionField, n5, datasetName, blockSize, new GzipCompression());
		n5.setAttribute(datasetName, "boundsMin", boundsMin);
		n5.setAttribute(datasetName, "boundsMax", boundsMax);
		n5.setAttribute(datasetName, "scale", transformScale);
	}

	public static DatasetAttributes createScaledTransformDataset(
			final N5Writer n5,
			final String datasetName,
			final double[] boundsMin,
			final double[] boundsMax,
			final double transformScale,
			final int[] gridSize) throws IOException {

		final int n = boundsMin.length;

		final long[] floorScaledMin = Grid.floorScaled(boundsMin, transformScale);
		final long[] ceilScaledMax = Grid.ceilScaled(boundsMax, transformScale);
		final long[] dimensions = new long[n + 1];
		for (int d = 0; d < n; ++d)
			dimensions[d] = ceilScaledMax[d] - floorScaledMin[d] + 1;
		dimensions[n] = n;
		final int[] blockSize = Arrays.copyOf(gridSize, n + 1);
		blockSize[n] = n;

		final DatasetAttributes attributes = new DatasetAttributes(
				dimensions,
				blockSize,
				DataType.FLOAT64,
				new GzipCompression());
		n5.createDataset(datasetName, attributes);
		n5.setAttribute(datasetName, "boundsMin", boundsMin);
		n5.setAttribute(datasetName, "boundsMax", boundsMax);
		n5.setAttribute(datasetName, "scale", transformScale);
		return attributes;
	}

	/**
	 * Saves a single 2x2x... gridSize block of a transform as a position field
	 * in an N5 dataset.  The N5 dataset's size is the full expected range, but
	 * only the block cells covering the block are stored.
	 *
	 * @param n5
	 * @param datasetName
	 * @param transform
	 * @param transformScale
	 * @param boundsMin
	 * @param boundsMax
	 * @param gridOffset
	 * @param gridSize
	 * @throws IOException
	 */
	public static void saveScaledTransformBlock(
			final N5Writer n5,
			final String datasetName,
			final RealTransform transform,
			final double transformScale,
			final double[] boundsMin,
			final double[] boundsMax,
			final long[] gridOffset,
			final int[] gridSize) throws IOException {

		final int n = transform.numSourceDimensions();

		final long[] floorScaledMin = Grid.floorScaled(boundsMin, transformScale);
		final long[] ceilScaledMax = Grid.ceilScaled(boundsMax, transformScale);
		final long[] dimensions = new long[n + 1];
		for (int d = 0; d < n; ++d)
			dimensions[d] = ceilScaledMax[d] - floorScaledMin[d] + 1;
		dimensions[n] = n;
		final int[] blockSize = Arrays.copyOf(gridSize, n + 1);
		blockSize[n] = n;

		final DatasetAttributes attributes = new DatasetAttributes(
				dimensions,
				blockSize,
				DataType.FLOAT64,
				new GzipCompression());
		n5.createDataset(datasetName, attributes);
		n5.setAttribute(datasetName, "boundsMin", boundsMin);
		n5.setAttribute(datasetName, "boundsMax", boundsMax);
		n5.setAttribute(datasetName, "scale", transformScale);

		final RealTransform scaledTransform = Transform.createScaledRealTransform(transform, 1.0 / transformScale);

		final long[] intervalMin = new long[floorScaledMin.length];
		Arrays.setAll(intervalMin, i -> gridOffset[i] * gridSize[i] + floorScaledMin[i]);
		final long[] intervalMax = new long[ceilScaledMax.length];
		Arrays.setAll(intervalMax, i -> Math.min(dimensions[i] + floorScaledMin[i], intervalMin[i] + gridSize[i] * 2) - 1);

		final RandomAccessibleInterval<DoubleType> positionField =
				Transform.createPositionField(
						scaledTransform,
						new FinalInterval(
								intervalMin,
								intervalMax));

		N5Utils.saveBlock(positionField, n5, datasetName, attributes, Arrays.copyOf(gridOffset, n + 1));
	}

	/**
	 * Convert an mpicbg {@link Affine2D} into an ImgLib2
	 * {@link AffineTransform2D}.
	 *
	 * @param affine2D
	 * @return
	 */
	public static <M extends Affine2D<M>> AffineTransform2D convertAffine2DtoAffineTransform2D(final M affine2D) {

		final double[] a = new double[6];
		affine2D.toArray(a);
		final AffineTransform2D transform = new AffineTransform2D();
		transform.set(
				a[0], a[2], a[4],
				a[1], a[3], a[5]);
		return transform;
	}

	/**
	 * Convert and invert an mpicbg {@link Affine2D} into an ImgLib2
	 * {@link AffineTransform2D}.
	 *
	 * @param affine2D
	 * @return
	 */
	public static <M extends Affine2D<M>> AffineTransform2D convertAndInvertAffine2DtoAffineTransform2D(final M affine2D) {

		return convertAffine2DtoAffineTransform2D(affine2D).inverse();
	}

	/**
	 * Create a list of scaled datasets as transformed by a corresponding list
	 * of target to source transforms.
	 *
	 * @param n5Path
	 * @param datasetNames
	 * @param scaleIndex
	 * @param transforms
	 * @param targetInterval
	 * @return
	 *
	 * @throws IOException
	 */
	public static RandomAccessibleInterval<FloatType> createTransformedStack(
			final String n5Path,
			final List<String> datasetNames,
			final int scaleIndex,
			final List<? extends RealTransform> transforms,
			final Interval targetInterval) throws IOException {

		final ArrayList<RandomAccessibleInterval<FloatType>> transformedIntervals = new ArrayList<>();
		final N5Reader n5Reader = new N5FSReader(n5Path);

		for (int i = 0; i < transforms.size(); ++i) {

			final RandomAccessibleInterval<FloatType> source = N5Utils.open(n5Reader, datasetNames.get(i) + "/s" + scaleIndex);

			transformedIntervals.add(
					Transform.createTransformedInterval(
							source,
							targetInterval,
							Transform.createScaledRealTransform(transforms.get(i), scaleIndex),
							new FloatType(255)));
		}

		return Views.stack(transformedIntervals);
	}


	/**
	 * Create a scale and shift transformation from image to world space that
	 * compensates for the pixel offset introduced by scaling with top left
	 * pixel coordinates preserved (like when averaging pixel windows for
	 * downscaling).
	 *
	 * @param scale
	 * @return
	 */
	public static ScaleAndTranslation createTopLeftScaleShift(final double[] scale) {

		final double[] offset = new double[scale.length];
		Arrays.setAll(offset, i -> scale[i] * 0.5 - 0.5);
		return new ScaleAndTranslation(
						scale,
						offset);
	}


	public static <T extends RealType<T>> RealRandomAccessible<T> scaleAndShiftHeightField(
			final RandomAccessibleInterval<T> heightField,
			final double[] scale) {

		return RealViews.affineReal(
				Views.interpolate(
						Views.extendBorder(heightField),
						new NLinearInterpolatorFactory<>()),
				createTopLeftScaleShift(scale));
	}


	public static <T extends RealType<T>> RandomAccessibleInterval<DoubleType> scaleAndShiftHeightFieldValues(
			final RandomAccessibleInterval<T> heightField,
			final double scale,
			final double offset) {

		return Converters.convert(
				heightField,
				(a, b) -> b.setReal((a.getRealDouble() + offset + 0.5) * scale - 0.5),
				new DoubleType());
	}
}
