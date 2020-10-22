package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import bdv.util.ConstantRandomAccessible;
import bdv.viewer.Interpolation;
import loci.formats.FormatException;
import loci.formats.in.TiffReader;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.Interpolant;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.interpolation.stack.LinearRealRandomAccessibleStackInterpolatorFactory;
import net.imglib2.interpolation.stack.NearestNeighborRealRandomAccessibleStackInterpolatorFactory;
import net.imglib2.outofbounds.OutOfBoundsConstantValueFactory;
import net.imglib2.outofbounds.OutOfBoundsFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.ValuePair;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.fusion.FusionTools;

public class PairwiseAlignChannelsUtil
{
	public static <T extends NumericType<T> & NativeType<T>> ValuePair<ValuePair<List<RealRandomAccessible<T>>,List<RealRandomAccessible<FloatType>>>, RealInterval> openWeightedStack(
			final List<Slice> slices,
			final OutOfBoundsFactory<T, RandomAccessibleInterval<T>> outOfBoundsFactory,
			final InterpolatorFactory<T, RandomAccessible<T>> interpolatorFactory,
			final OutOfBoundsFactory<FloatType, RandomAccessibleInterval<FloatType>> outOfBoundsWeightFactory,
			final InterpolatorFactory<FloatType, RandomAccessible<FloatType>> interpolatorWeightFactory,
			final int firstSliceIndex,
			final int lastSliceIndex) throws FormatException, IOException {

		try (TiffReader reader = new TiffReader()) {
			reader.setId(slices.get(firstSliceIndex).path);
			final int width = reader.getSizeX();
			final int height = reader.getSizeY();

			final ArrayList<RealRandomAccessible<T>> slicesList = new ArrayList<>();
			final ArrayList<RealRandomAccessible<FloatType>> weightList = new ArrayList<>();

			RealInterval bounds = null;
			for (int i = firstSliceIndex; i <= lastSliceIndex; ++i) {

				final Slice slice = slices.get(i);
				final RandomAccessibleInterval<T> img =
						Opener.openSlice(
								reader,
								slice.path,
								slice.index,
								width,
								height);
				if (bounds == null)
					bounds = img;
				else
					bounds = Intervals.union(bounds, img);

				// weights
				final ExtendedRandomAccessibleInterval<FloatType, RandomAccessibleInterval<FloatType>> extendedWeight = 
						Views.extend(
								Views.interval(
										new ConstantRandomAccessible<FloatType>(
												new FloatType( 1.0f ),
												2 ),
										new long[] { 0, 0 },
										new long[] { width - 1, height - 1 } ),
								outOfBoundsWeightFactory );
				final RealRandomAccessible<FloatType> interpolantWeight = Views.interpolate(extendedWeight, interpolatorWeightFactory);
				weightList.add(interpolantWeight);

				final ExtendedRandomAccessibleInterval<T, RandomAccessibleInterval<T>> extended = Views.extend(img, outOfBoundsFactory);
				final RealRandomAccessible<T> interpolant = Views.interpolate(extended, interpolatorFactory);
				slicesList.add(interpolant);
			}

			return new ValuePair<>(new ValuePair<>(slicesList, weightList), bounds);
		}
	}

	public static <T extends NumericType<T> & NativeType<T>> ValuePair<ValuePair<RealRandomAccessible<T>,RealRandomAccessible<FloatType>>, RealInterval> openAlignedWeightedStack(
			final List<Slice> slices,
			final T background,
			final Interpolation interpolationMethod,
			final AffineTransform2D camTransform,
			final RandomAccessible<AffineTransform2D> alignment,
			final int firstSliceIndex,
			final int lastSliceIndex) throws FormatException, IOException {

		/* get image slices */
		final ValuePair<ValuePair<List<RealRandomAccessible<T>>,List<RealRandomAccessible<FloatType>>>, RealInterval> realSlices = openWeightedStack(
				slices,
				new OutOfBoundsConstantValueFactory<T, RandomAccessibleInterval<T>>(background),
				interpolationMethod == Interpolation.NLINEAR ?
						new NLinearInterpolatorFactory<>() :
						new NearestNeighborInterpolatorFactory<>(),
				new OutOfBoundsConstantValueFactory<FloatType, RandomAccessibleInterval<FloatType>>( new FloatType( 0.0f )),
				interpolationMethod == Interpolation.NLINEAR ?
						new NLinearInterpolatorFactory<>() :
						new NearestNeighborInterpolatorFactory<>(),
				firstSliceIndex,
				lastSliceIndex);

		/* transform slices */
		final RandomAccess<AffineTransform2D> alignmentAccess = alignment.randomAccess();
		final ArrayList<RealRandomAccessible<T>> transformedRealSlices = new ArrayList<>();
		RealInterval bounds = null;
		alignmentAccess.setPosition(firstSliceIndex, 0);

		/* transform weights */
		final ArrayList<RealRandomAccessible<FloatType>> transformedRealWeights = new ArrayList<>();

		for ( int i = 0; i < realSlices.getA().getA().size(); ++i )
		{
			final RealRandomAccessible<T> slice = realSlices.getA().getA().get( i );
			final RealRandomAccessible<FloatType> weight = realSlices.getA().getB().get( i );

			final AffineTransform2D combinedTransform = camTransform.copy();
			combinedTransform.preConcatenate(alignmentAccess.get());
			final FinalRealInterval sliceBounds = combinedTransform.estimateBounds(realSlices.getB());
			if (bounds == null)
				bounds = sliceBounds;
			else
				bounds = Intervals.union(bounds, sliceBounds);
			transformedRealSlices.add(RealViews.affineReal(slice, combinedTransform));
			transformedRealWeights.add(RealViews.affineReal(weight, combinedTransform));
			alignmentAccess.fwd(0);
		}

		final Interpolant<T, ArrayList<RealRandomAccessible<T>>> interpolatedStack = new Interpolant<>(
				transformedRealSlices,
				interpolationMethod == Interpolation.NLINEAR ?
						new LinearRealRandomAccessibleStackInterpolatorFactory<>() :
						new NearestNeighborRealRandomAccessibleStackInterpolatorFactory<>(),
				3);

		final Interpolant<FloatType, ArrayList<RealRandomAccessible<FloatType>>> interpolatedWeights = new Interpolant<>(
				transformedRealWeights,
				interpolationMethod == Interpolation.NLINEAR ?
						new LinearRealRandomAccessibleStackInterpolatorFactory<>() :
						new NearestNeighborRealRandomAccessibleStackInterpolatorFactory<>(),
				3);

		return new ValuePair<>( new ValuePair<>(interpolatedStack, interpolatedWeights), bounds);
	}

	public static < T extends RealType<T> & NativeType<T> > ValuePair<RandomAccessibleInterval< T >, RandomAccessibleInterval< T > > openRandomAccessibleIntervals(
			final List< Slice > slices,
			final T background,
			final Interpolation interpolation,
			final AffineTransform2D camTransform,
			final RandomAccessible<AffineTransform2D> alignments,
			final int firstSliceIndex,
			final int lastSliceIndex ) throws FormatException, IOException
	{
		ValuePair<ValuePair<RealRandomAccessible<T>,RealRandomAccessible<FloatType>>, RealInterval> alignedStackWeightBounds =
				openAlignedWeightedStack(
						slices,
						background,
						interpolation,
						camTransform,
						alignments,
						firstSliceIndex,
						lastSliceIndex);

		RealRandomAccessible<T> alignedStack = alignedStackWeightBounds.getA().getA();
		RealRandomAccessible<FloatType> alignedWeights = alignedStackWeightBounds.getA().getB();
		final RealInterval realBounds2D = alignedStackWeightBounds.getB();

		// the stack is sitting at z=0, independent of the firstslice index
		if ( firstSliceIndex != 0 )
		{
			alignedStack =  RealViews.transform( alignedStack, new Translation3D(0, 0, firstSliceIndex ) );
			alignedWeights =  RealViews.transform( alignedWeights, new Translation3D(0, 0, firstSliceIndex ) );
		}

		final RealInterval realBounds3D = Intervals.createMinMax(
				(long)Math.floor(realBounds2D.realMin(0)),
				(long)Math.floor(realBounds2D.realMin(1)),
				firstSliceIndex,
				(long)Math.ceil(realBounds2D.realMax(0)),
				(long)Math.ceil(realBounds2D.realMax(1)),
				lastSliceIndex);

		/*final ValuePair<RealRandomAccessible<UnsignedShortType>, RealInterval> transformedStackBounds =
				ViewISPIMStack.transform(
						alignedStackBounds.getA(),
						realBounds3D,
						stretchTransform);

		final RealInterval realBounds = transformedStackBounds.getB();
		final Interval bounds = Intervals.smallestContainingInterval(realBounds);*/

		final Interval displayInterval = Intervals.smallestContainingInterval(realBounds3D);

		/* interpolated pixels get a weight of 0.0 */
		RealRandomAccessible<T> alignedWeightsConv = Converters.convert( alignedWeights, (a,b) -> b.setReal( a.get() >= 0.99999f ? 1 : 0 ), background.copy() );

		return new ValuePair<>(
				FusionTools.cacheRandomAccessibleInterval(
						Views.interval( Views.raster( alignedStack ), displayInterval ),
						Integer.MAX_VALUE,
						background.copy(),
						10, 10, 10 ),
				FusionTools.cacheRandomAccessibleInterval(
						Views.interval( Views.raster( alignedWeightsConv ), displayInterval ),
						Integer.MAX_VALUE,
						background.copy(),
						10, 10, 10 ) );
	}

	public static < T extends NumericType<T> & NativeType<T> > RandomAccessibleInterval< T > openRandomAccessibleInterval(
			final List< Slice > slices,
			final T background,
			final Interpolation interpolation,
			final AffineTransform2D camTransform,
			final RandomAccessible<AffineTransform2D> alignments,
			final int firstSliceIndex,
			final int lastSliceIndex,
			final boolean maxBounds ) throws FormatException, IOException
	{
		ValuePair<RealRandomAccessible<T>, RealInterval> alignedStackBounds =
				ViewISPIMStack.openAlignedStack(
						slices,
						background,
						interpolation,
						camTransform,
						alignments,
						firstSliceIndex,
						lastSliceIndex,
						maxBounds );

		// the stack is sitting at z=0, independent of the firstslice index
		if ( firstSliceIndex != 0 )
			alignedStackBounds = new ValuePair<>(
					RealViews.transform(
							alignedStackBounds.getA(),
							new Translation3D(0, 0, firstSliceIndex ) ),
					alignedStackBounds.getB() );

		final RealInterval realBounds2D = alignedStackBounds.getB();
		final RealInterval realBounds3D;

		if ( maxBounds )
			realBounds3D = Intervals.createMinMax(
				(long)Math.floor(realBounds2D.realMin(0)),
				(long)Math.floor(realBounds2D.realMin(1)),
				firstSliceIndex,
				(long)Math.ceil(realBounds2D.realMax(0)),
				(long)Math.ceil(realBounds2D.realMax(1)),
				lastSliceIndex);
		else
			realBounds3D = Intervals.createMinMax(
					(long)Math.ceil(realBounds2D.realMin(0)),
					(long)Math.ceil(realBounds2D.realMin(1)),
					firstSliceIndex,
					(long)Math.floor(realBounds2D.realMax(0)),
					(long)Math.floor(realBounds2D.realMax(1)),
					lastSliceIndex);

		/*final ValuePair<RealRandomAccessible<UnsignedShortType>, RealInterval> transformedStackBounds =
				ViewISPIMStack.transform(
						alignedStackBounds.getA(),
						realBounds3D,
						stretchTransform);

		final RealInterval realBounds = transformedStackBounds.getB();
		final Interval bounds = Intervals.smallestContainingInterval(realBounds);*/

		final Interval displayInterval = Intervals.smallestContainingInterval(realBounds3D);

		return Views.interval( Views.raster( alignedStackBounds.getA() ), displayInterval );
	}

}

