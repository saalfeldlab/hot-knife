package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.n5.N5FSReader;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bdv.util.ConstantRandomAccessible;
import bdv.viewer.Interpolation;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import loci.formats.FormatException;
import loci.formats.in.TiffReader;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.list.ListImg;
import net.imglib2.interpolation.Interpolant;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.interpolation.stack.LinearRealRandomAccessibleStackInterpolatorFactory;
import net.imglib2.interpolation.stack.NearestNeighborRealRandomAccessibleStackInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.outofbounds.OutOfBoundsConstantValueFactory;
import net.imglib2.outofbounds.OutOfBoundsFactory;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class AlignChannels implements Callable<Void>, Serializable {

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--id", required = true, description = "Stack key, e.g. Pos012")
	private String id = null;

	@Option(names = "--channelA", required = true, description = "Channel A key, e.g. Ch488+561+647nm")
	private String channelA = null;

	@Option(names = "--channelB", required = true, description = "Channel B key, e.g. Ch405nm")
	private String channelB = null;

	@Option(names = "--camA", required = true, description = "CamA key, e.g. cam1")
	private String camA = null;

	@Option(names = "--camB", required = true, description = "CamB key, e.g. cam1")
	private String camB = null;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new AlignChannels()).execute(args);
	}

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException
	{
		System.out.println( new Date(System.currentTimeMillis() ) + ": Opening N5." );

		final N5FSReader n5 = new N5FSReader(
				n5Path,
				new GsonBuilder().registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final HashMap<String, HashMap<String, AffineTransform2D>> camTransforms = n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, AffineTransform2D>>>() {}.getType());
		final ArrayList<String> ids = n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());

		if (!ids.contains(id))
			return null;

		System.out.println( new Date(System.currentTimeMillis() ) + ": Loading alignments." );

		final HashMap<String, HashMap<String, List<Slice>>> stacks = new HashMap<>();
		final HashMap<String, RandomAccessible<AffineTransform2D>> alignments = new HashMap<>();

		int localLastSliceIndex = Integer.MAX_VALUE;

		for (final Entry<String, HashMap<String, AffineTransform2D>> channel : camTransforms.entrySet()) {
			final HashMap<String, List<Slice>> channelStacks = new HashMap<>();
			stacks.put(channel.getKey(), channelStacks);

			/* stack alignment transforms */
			final ArrayList<AffineTransform2D> transforms = n5.getAttribute(
					id + "/" + channel.getKey(),
					"transforms",
					new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

			final RandomAccessible<AffineTransform2D> alignmentTransforms= Views.extendBorder(new ListImg<>(transforms, transforms.size()));

			alignments.put(channel.getKey(), alignmentTransforms);

			/* add all camera stacks that exist */
			for (final String camKey : channel.getValue().keySet()) {
				final String groupName = id + "/" + channel.getKey() + "/" + camKey;
				if (n5.exists(groupName)) {
					final ArrayList<Slice> stack = n5.getAttribute(
							groupName,
							"slices",
							new TypeToken<ArrayList<Slice>>(){}.getType());
					channelStacks.put(
							camKey,
							stack);

					localLastSliceIndex = Math.min(localLastSliceIndex, stack.size() - 1);
				}
			}
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": localLastSliceIndex=" + localLastSliceIndex );

		final Gson gson = new GsonBuilder().registerTypeAdapter(
				AffineTransform2D.class,
				new AffineTransform2DAdapter()).create();

		System.out.println(gson.toJson(camTransforms));
		System.out.println(gson.toJson(ids));
		// System.out.println(new Gson().toJson(stacks));

		final Scale3D stretchTransform = new Scale3D(0.2, 0.2, 0.85);

		final int gaussOverhead = DoGImgLib2.radiusDoG( 2.0 );
		System.out.println( new Date(System.currentTimeMillis() ) + ": gauss overhead: " + gaussOverhead );

		// testing
		final int firstSliceIndex = 510 - gaussOverhead;
		final int lastSliceIndex = 510 + gaussOverhead;

		System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + firstSliceIndex );
		System.out.println( new Date(System.currentTimeMillis() ) + ": to=" + lastSliceIndex );

		final HashMap<String, List<Slice>> chA = stacks.get( channelA );
		final HashMap<String, List<Slice>> chB = stacks.get( channelB );

		final List< Slice > slicesA = chA.get( camA );
		final List< Slice > slicesB = chB.get( camB );

		/* this is the inverse */
		final AffineTransform2D camAtransform = camTransforms.get( channelA ).get( camA );
		final AffineTransform2D camBtransform = camTransforms.get( channelB ).get( camB );

		new ImageJ();
		final ExecutorService service = Executors.newFixedThreadPool( Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 ) );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Loading imgsA." );

		final Pair<RandomAccessibleInterval< UnsignedShortType >, RandomAccessibleInterval< FloatType >> imgsA =
				openRandomAccessibleIntervals(
						slicesA,
						new UnsignedShortType(0),
						Interpolation.NLINEAR,
						camAtransform,
						alignments.get( channelA ),
						firstSliceIndex,
						lastSliceIndex );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Showing imgsA." );

		final ImagePlus impA = ImageJFunctions.wrap(imgsA.getA(), "imgA", service ).duplicate();
		impA.setDimensions( 1, impA.getStackSize(), 1 );
		impA.resetDisplayRange();
		impA.show();

		final ImagePlus impAw = ImageJFunctions.wrap(imgsA.getB(), "imgAw", service ).duplicate();
		impAw.setDimensions( 1, impA.getStackSize(), 1 );
		impAw.resetDisplayRange();
		impAw.show();

		System.out.println( new Date(System.currentTimeMillis() ) + ": Loading imgsB." );

		final Pair<RandomAccessibleInterval< UnsignedShortType >, RandomAccessibleInterval< FloatType >> imgsB =
				openRandomAccessibleIntervals(
						slicesB,
						new UnsignedShortType(0),
						Interpolation.NLINEAR,
						camBtransform,
						alignments.get( channelB ),
						firstSliceIndex,
						lastSliceIndex );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Showing imgsB." );

		final ImagePlus impB = ImageJFunctions.wrap(imgsB.getA(), "imgB", service ).duplicate();
		impB.setDimensions( 1, impB.getStackSize(), 1 );
		impB.resetDisplayRange();
		impB.show();

		final ImagePlus impBw = ImageJFunctions.wrap(imgsB.getB(), "imgBw", service ).duplicate();
		impBw.setDimensions( 1, impB.getStackSize(), 1 );
		impBw.resetDisplayRange();
		impBw.show();

		//IJ.run("Merge Channels...", "c2=DUP_imgA c6=DUP_imgB create");

		// cam4 (Ch488+561+647nm) vs cam4 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch488+561+647nm)**

		
		//ImageJFunctions.show( imgA );
		SimpleMultiThreading.threadHaltUnClean();
		return null;
	}

	public static <T extends NumericType<T> & NativeType<T>> ValuePair<ValuePair<List<RealRandomAccessible<T>>,List<RealRandomAccessible<FloatType>>>, RealInterval> openWeightedStack(
			final List<Slice> slices,
			final OutOfBoundsFactory<T, RandomAccessibleInterval<T>> outOfBoundsFactory,
			final InterpolatorFactory<T, RandomAccessible<T>> interpolatorFactory,
			final OutOfBoundsFactory<FloatType, RandomAccessibleInterval<FloatType>> outOfBoundsWeightFactory,
			final InterpolatorFactory<FloatType, RandomAccessible<FloatType>> interpolatorWeightFactory,
			final int firstSliceIndex,
			final int lastSliceIndex) throws FormatException, IOException {

		try (TiffReader reader = new TiffReader()) {
			reader.setId(slices.get(0).path);
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

	public static < T extends NumericType<T> & NativeType<T> > ValuePair<RandomAccessibleInterval< T >, RandomAccessibleInterval< FloatType > > openRandomAccessibleIntervals(
			final List< Slice > slices,
			final T background,
			final Interpolation interpolation,
			final AffineTransform2D camTransform,
			final RandomAccessible<AffineTransform2D> alignments,
			final int firstSliceIndex,
			final int lastSliceIndex ) throws FormatException, IOException
	{
		System.out.println( new Date(System.currentTimeMillis() ) + ": Opening images and weight stacks" );

		ValuePair<ValuePair<RealRandomAccessible<T>,RealRandomAccessible<FloatType>>, RealInterval> alignedStackWeightBounds =
				openAlignedWeightedStack(
						slices,
						background,
						interpolation,
						camTransform,
						alignments,
						firstSliceIndex,
						lastSliceIndex);

		System.out.println( new Date(System.currentTimeMillis() ) + ": Finalizing images and weight stacks" );

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
		alignedWeights = Converters.convert( alignedWeights, (a,b) -> b.set( a.get() >= 0.99999f ? 1.0f : 0.0f ), new FloatType() );

		return new ValuePair<>(
				Views.interval( Views.raster( alignedStack ), displayInterval ),
				Views.interval( Views.raster( alignedWeights ), displayInterval ) );
	}

	public static < T extends NumericType<T> & NativeType<T> > RandomAccessibleInterval< T > openRandomAccessibleInterval(
			final List< Slice > slices,
			final T background,
			final Interpolation interpolation,
			final AffineTransform2D camTransform,
			final RandomAccessible<AffineTransform2D> alignments,
			final int firstSliceIndex,
			final int lastSliceIndex ) throws FormatException, IOException
	{
		ValuePair<RealRandomAccessible<T>, RealInterval> alignedStackBounds =
				ViewISPIMStack.openAlignedStack(
						slices,
						background,
						interpolation,
						camTransform,
						alignments,
						firstSliceIndex,
						lastSliceIndex);

		// the stack is sitting at z=0, independent of the firstslice index
		if ( firstSliceIndex != 0 )
			alignedStackBounds = new ValuePair<>(
					RealViews.transform(
							alignedStackBounds.getA(),
							new Translation3D(0, 0, firstSliceIndex ) ),
					alignedStackBounds.getB() );

		final RealInterval realBounds2D = alignedStackBounds.getB();
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

		return Views.interval( Views.raster( alignedStackBounds.getA() ), displayInterval );
	}
}
