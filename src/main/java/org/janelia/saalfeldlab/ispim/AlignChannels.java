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

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bdv.BigDataViewer;
import bdv.util.BdvFunctions;
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
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
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
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import scala.Tuple2;

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

	@Option(names = {"-b", "--blocksize"}, required = false, description = "blocksize in z for point extraction (default: 20)")
	private int blocksize = 20;

	@Option(names = "--first", required = false, description = "First slice index, e.g. 0 (default 0)")
	private int firstSliceIndex = 0;

	@Option(names = "--last", required = false, description = "Last slice index, e.g. 1000 (default MAX)")
	private int lastSliceIndex = Integer.MAX_VALUE;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new AlignChannels()).execute(args);
	}

	public static class Block implements Serializable
	{
		private static final long serialVersionUID = -5770229677154496831L;

		final int from, to, gaussOverhead;
		final String channel, cam;
		final double sigma, threshold;
		final double[] transform;

		public Block( final int from, final int to, final String channel, final String cam, final AffineTransform2D camtransform, final double sigma, final double threshold )
		{
			this.from = from;
			this.to = to;
			this.channel = channel;
			this.cam = cam;
			this.sigma = sigma;
			this.threshold = threshold;
			this.gaussOverhead = DoGImgLib2.radiusDoG( 2.0 );
			this.transform = camtransform.getRowPackedCopy();
		}

		public AffineTransform2D getTransform()
		{
			final AffineTransform2D t = new AffineTransform2D();
			t.set( transform );
			return t;
		}
	}

	public static void alignChannels(
			final JavaSparkContext sc,
			final String n5Path,
			final String id,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final int firstSliceIndex,
			final int lastSliceIndex,
			final int blockSize ) throws FormatException, IOException
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
		{
			System.err.println("Id '" + id + "' does not exist in '" + n5Path + "'.");
			return;
		}
	
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

		new ImageJ();
	
		final int numSlices = localLastSliceIndex - firstSliceIndex + 1;
		final int numBlocks = numSlices / blockSize + (numSlices % blockSize > 0 ? 1 : 0);
		final ArrayList<Block> blocks = new ArrayList<>();

		for ( int i = 0; i < numBlocks; ++i )
		{
			final int from  = i * blockSize + firstSliceIndex;
			final int to = Math.min( localLastSliceIndex, from + blockSize - 1 );

			final Block blockChannelA = new Block(from, to, channelA, camA, camTransforms.get( channelA ).get( camA ), 2.0, 0.01 );
			final Block blockChannelB = new Block(from, to, channelB, camB, camTransforms.get( channelB ).get( camB ), 2.0, 0.01 );

			blocks.add( blockChannelA );
			blocks.add( blockChannelB );
	
			System.out.println( "block " + i + ": " + from + " >> " + to );
		}

		final JavaRDD<Block> rddSlices = sc.parallelize( blocks );

		final JavaPairRDD<Block, ArrayList< InterestPoint >> rddFeatures = rddSlices.mapToPair(
			block ->
			{
				final HashMap<String, List<Slice>> ch = stacks.get( block.channel );
				final List< Slice > slices = ch.get( block.cam );

				/* this is the inverse */
				final AffineTransform2D camtransform = block.getTransform();//camTransforms.get( block.channel ).get( block.cam );

				final ArrayList<AffineTransform2D> transforms = n5.getAttribute(
						id + "/" + block.channel,
						"transforms",
						new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

				final RandomAccessible<AffineTransform2D> alignmentTransforms = Views.extendBorder(new ListImg<>(transforms, transforms.size()));

				final Pair<RandomAccessibleInterval< UnsignedShortType >, RandomAccessibleInterval< UnsignedShortType >> imgs =
						openRandomAccessibleIntervals(
								slices,
								new UnsignedShortType(0),
								Interpolation.NLINEAR,
								camtransform,
								alignmentTransforms,//alignments.get( channelA ),
								block.from  - block.gaussOverhead,
								block.to + block.gaussOverhead );

				final ExecutorService service = Executors.newFixedThreadPool( 1 );
				final ArrayList< InterestPoint > initialPoints =
						DoGImgLib2.computeDoG(
								imgs.getA(),
								imgs.getB(),
								block.sigma,
								block.threshold,
								1, /*localization*/
								false, /*findMin*/
								true, /*findMax*/
								0.0, /* min intensity */
								0.0, /* max intensity */
								service );

				service.shutdown();

				// exclude points that lie within the Gauss overhead
				final ArrayList< InterestPoint > points = new ArrayList<>();

				for ( final InterestPoint ip : initialPoints )
					if ( ip.getDoublePosition( 2 ) > firstSliceIndex - 0.5 && ip.getDoublePosition( 2 ) < lastSliceIndex + 0.5 )
						points.add( ip );

				System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + firstSliceIndex + ", to=" + lastSliceIndex + ", ch=" + block.channel + " (cam=" + block.cam + "): " + points.size() + " points" );

				if ( block.from == 200 )
				{
					final ImagePlus impA = ImageJFunctions.wrap(imgs.getA(), block.channel, Executors.newFixedThreadPool( 8 ) ).duplicate();
					impA.setDimensions( 1, impA.getStackSize(), 1 );
					impA.resetDisplayRange();
					impA.show();
	
					final ImagePlus impAw = ImageJFunctions.wrap(imgs.getB(), block.channel + "w", Executors.newFixedThreadPool( 8 ) ).duplicate();
					impAw.setDimensions( 1, impA.getStackSize(), 1 );
					impAw.resetDisplayRange();
					impAw.show();

					final long[] dim = new long[ imgs.getA().numDimensions() ];
					final long[] min = new long[ imgs.getA().numDimensions() ];
					imgs.getA().dimensions( dim );
					imgs.getA().min( min );

					final RandomAccessibleInterval< FloatType > dotsA = Views.translate( ArrayImgs.floats( dim ), min );
					final RandomAccess< FloatType > rDotsA = dotsA.randomAccess();

					for ( final InterestPoint ip : points )
					{
						for ( int d = 0; d < dotsA.numDimensions(); ++d )
							rDotsA.setPosition( Math.round( ip.getFloatPosition( d ) ), d );

						rDotsA.get().setOne();
					}

					Gauss3.gauss( 1, Views.extendZero( dotsA ), dotsA );
					ImageJFunctions.show( dotsA );
				}

				return new Tuple2<>(block, points);
			});

		/* cache the booleans, so features aren't regenerated every time */
		rddFeatures.cache();

		/* collect the results */
		final List<Tuple2<Block, ArrayList< InterestPoint >>> results = rddFeatures.collect();

		final ArrayList< InterestPoint > pointsChA = new ArrayList<>();
		final ArrayList< InterestPoint > pointsChB = new ArrayList<>();

		for ( final Tuple2<Block, ArrayList< InterestPoint >> tuple : results )
		{
			if ( tuple._1().channel.equals( channelA ) )
				pointsChA.addAll( tuple._2() );
			else
				pointsChB.addAll( tuple._2() );
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": channelA: " + pointsChA.size() + " points" );
		System.out.println( new Date(System.currentTimeMillis() ) + ": channelB: " + pointsChA.size() + " points" );

		final N5FSWriter n5Writer = new N5FSWriter(n5Path);

		if (pointsChA.size() > 0)
		{
			final String datasetName = id + "/" + channelA + "/Stack-DoG-detections";
			final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);

			n5Writer.writeSerializedBlock(
					pointsChA,
					datasetName,
					datasetAttributes,
					new long[] {0});
		}

		if (pointsChA.size() > 0)
		{
			final String datasetName = id + "/" + channelB + "/Stack-DoG-detections";
			final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);

			n5Writer.writeSerializedBlock(
					pointsChB,
					datasetName,
					datasetAttributes,
					new long[] {0});
		}

		/*
		System.exit( 0 );
		//final Scale3D stretchTransform = new Scale3D(0.2, 0.2, 0.85);

		// testing
		firstSliceIndex = 510 - gaussOverhead;
		lastSliceIndex = 510 + gaussOverhead;

		System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + firstSliceIndex );
		System.out.println( new Date(System.currentTimeMillis() ) + ": to=" + lastSliceIndex );

		final HashMap<String, List<Slice>> chA = stacks.get( channelA );
		final HashMap<String, List<Slice>> chB = stacks.get( channelB );

		final List< Slice > slicesA = chA.get( camA );
		final List< Slice > slicesB = chB.get( camB );

		// this is the inverse 
		final AffineTransform2D camAtransform = camTransforms.get( channelA ).get( camA );
		final AffineTransform2D camBtransform = camTransforms.get( channelB ).get( camB );

		new ImageJ();
		final ExecutorService service = Executors.newFixedThreadPool( Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 ) );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Loading imgsA." );

		final Pair<RandomAccessibleInterval< UnsignedShortType >, RandomAccessibleInterval< UnsignedShortType >> imgsA =
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

		System.out.println( new Date(System.currentTimeMillis() ) + ": Finding points in imgA." );

		ArrayList< InterestPoint > pointsA =
				DoGImgLib2.computeDoG(
						imgsA.getA(),
						imgsA.getB(),
						2.0,
						0.01,
						1, //localization
						false, //findMin
						true, //findMax
						0.0, // min intensity 
						0.0, // max intensity 
						service );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Found " + pointsA.size() + " points." );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Rendering " + pointsA.size() + " points." );

		final long[] dim = new long[ imgsA.getA().numDimensions() ];
		final long[] min = new long[ imgsA.getA().numDimensions() ];
		imgsA.getA().dimensions( dim );
		imgsA.getA().min( min );

		final RandomAccessibleInterval< FloatType > dotsA = Views.translate( ArrayImgs.floats( dim ), min );
		final RandomAccess< FloatType > rDotsA = dotsA.randomAccess();

		for ( final InterestPoint ip : pointsA )
		{
			for ( int d = 0; d < dotsA.numDimensions(); ++d )
				rDotsA.setPosition( Math.round( ip.getFloatPosition( d ) ), d );

			rDotsA.get().setOne();
		}

		Gauss3.gauss( 1, Views.extendZero( dotsA ), dotsA );
		ImageJFunctions.show( dotsA );

		SimpleMultiThreading.threadHaltUnClean();
		final Pair<RandomAccessibleInterval< UnsignedShortType >, RandomAccessibleInterval< UnsignedShortType >> imgsB =
				openRandomAccessibleIntervals(
						slicesB,
						new UnsignedShortType(0),
						Interpolation.NLINEAR,
						camBtransform,
						alignments.get( channelB ),
						firstSliceIndex,
						lastSliceIndex );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Finding points in imgB." );

		ArrayList< InterestPoint > pointsB =
				DoGImgLib2.computeDoG(
						imgsB.getA(),
						null,
						2.0,
						0.01,
						1, //localization
						false, //findMin
						true, //findMax
						0.0, // min intensity 
						0.0, // max intensity 
						service );

		System.out.println( new Date(System.currentTimeMillis() ) + ": Found " + pointsB.size() + " points." );

		//IJ.run("Merge Channels...", "c2=DUP_imgA c6=DUP_imgB create");

		// cam4 (Ch488+561+647nm) vs cam4 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch488+561+647nm)**

		
		//ImageJFunctions.show( imgA );
		SimpleMultiThreading.threadHaltUnClean();*/
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

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException
	{
		// System property for locally calling spark has to be set, e.g. -Dspark.master=local[4]
		final String sparkLocal = System.getProperty( "spark.master" );

		// only do that if the system property is not set
		if ( sparkLocal == null || sparkLocal.trim().length() == 0 )
		{
			System.out.println( "Spark System property not set: " + sparkLocal );
			System.setProperty( "spark.master", "local[" + Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 ) + "]" );
		}

		System.out.println( "Spark System property is: " + System.getProperty( "spark.master" ) );

		final SparkConf conf = new SparkConf().setAppName("SparkAlignChannels");

		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		alignChannels(
				sc,
				n5Path,
				id,
				channelA,
				channelB,
				camA,
				camB,
				firstSliceIndex,
				lastSliceIndex,
				blocksize );

		sc.close();

		return null;
	}
}
