package org.janelia.saalfeldlab.ispim;

import java.awt.Rectangle;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.MultiConsensusFilter;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bdv.util.ConstantRandomAccessible;
import bdv.viewer.Interpolation;
import ij.CompositeImage;
import ij.ImageJ;
import ij.ImagePlus;
import ij.gui.PointRoi;
import ij.gui.Roi;
import loci.formats.FormatException;
import loci.formats.in.TiffReader;
import mpicbg.imglib.wrapper.ImgLib1;
import mpicbg.imglib.wrapper.ImgLib2;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.TranslationModel3D;
import mpicbg.stitching.PairWiseStitchingImgLib;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.StitchingParameters;
import mpicbg.stitching.fusion.OverlayFusion;
import mpicbg.trakem2.transform.AffineModel3D;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImgFactory;
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
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.downsampling.Downsample;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.fastrgldm.FRGLDMMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMMatcher;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import scala.Tuple2;

public class SparkPaiwiseAlignChannelsPCM implements Callable<Void>, Serializable {

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

	@Option(names = {"-b", "--blocksize"}, required = false, description = "blocksize in z for point extraction (default: 40)")
	private int blocksize = 20;

	@Option(names = "--first", required = false, description = "First slice index, e.g. 0 (default 0)")
	private int firstSliceIndex = 0;

	@Option(names = "--last", required = false, description = "Last slice index, e.g. 1000 (default MAX)")
	private int lastSliceIndex = Integer.MAX_VALUE;

	@Option(names = "--rThreshold", required = false, description = "correlation threshold (default: 0.5)")
	private double rThreshold = 0.5;

	@Option(names = "--tryLoadingCandidates", required = false, description = "Try to load previously identified PCM candidate points (default: false)")
	private boolean tryLoadingCandidates = false;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new SparkPaiwiseAlignChannelsPCM()).execute(args);
	}

	public static class Block implements Serializable
	{
		private static final long serialVersionUID = -5770229677154496831L;

		final int from, to;
		final String channelA, camA, channelB, camB;
		final double rThreshold;
		final double[] transformA, transformB;
		final ArrayList< Tuple2<long[], long[]> > blocksToTest;

		public Block(
				final int from, final int to,
				final String channelA, final String camA, final AffineTransform2D camtransformA,
				final String channelB, final String camB, final AffineTransform2D camtransformB,
				final double rThreshold )
		{
			this.from = from;
			this.to = to;
			this.channelA = channelA;
			this.camA = camA;
			this.transformA = camtransformA.getRowPackedCopy();
			this.channelB = channelB;
			this.camB = camB;
			this.transformB = camtransformB.getRowPackedCopy();
			this.rThreshold = rThreshold;

			blocksToTest = new ArrayList<>();
		}

		public AffineTransform2D getTransformA()
		{
			final AffineTransform2D t = new AffineTransform2D();
			t.set( transformA );
			return t;
		}

		public AffineTransform2D getTransformB()
		{
			final AffineTransform2D t = new AffineTransform2D();
			t.set( transformB );
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
			final int blockSize,
			final double rThreshold,
			final boolean tryLoadingCandidates ) throws FormatException, IOException, ClassNotFoundException
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

		int localLastSliceIndex = lastSliceIndex;

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

		ArrayList<InterestPoint> pointsChA = null;
		ArrayList<InterestPoint> pointsChB = null;

		if ( tryLoadingCandidates )
		{
			System.out.println( "trying to load PCM candidates ... " );

			try
			{
				final String datasetNameA = id + "/" + channelA + "/Stack-PCM-candidates";
				final DatasetAttributes datasetAttributesA = n5.getDatasetAttributes(datasetNameA);
	
				final String datasetNameB = id + "/" + channelB + "/Stack-PCM-candidates";
				final DatasetAttributes datasetAttributesB = n5.getDatasetAttributes(datasetNameB);
	
				pointsChA = n5.readSerializedBlock(datasetNameA, datasetAttributesA, new long[] {0});
				pointsChB = n5.readSerializedBlock(datasetNameB, datasetAttributesB, new long[] {0});
			}
			catch ( Exception e ) // java.nio.file.NoSuchFileException
			{
				pointsChA = pointsChB = null;
				e.printStackTrace();
			}
		}

		if ( pointsChA == null || pointsChB == null )
		{
			if ( tryLoadingCandidates )
				System.out.println( "could not load PCM candidates ... " );

			System.out.println( "extracting PCM candidates ... " );

			final int numSlices = localLastSliceIndex - firstSliceIndex + 1;
			final int numBlocks = numSlices / blockSize + (numSlices % blockSize > 0 ? 1 : 0);
			final int localLastSlice = localLastSliceIndex;
			final ArrayList<Block> blocks = new ArrayList<>();
	
			for ( int i = 0; i < numBlocks; ++i )
			{
				final int from  = i * blockSize + firstSliceIndex;
				final int to = Math.min( localLastSliceIndex, from + blockSize - 1 );
	
				final Block block = new Block(
						from, to,
						channelA, camA, camTransforms.get( channelA ).get( camA ),
						channelB, camB, camTransforms.get( channelB ).get( camB ),
						rThreshold );
	
				blocks.add( block );
		
				System.out.println( "block " + i + ": " + from + " >> " + to );
			}
	
			final JavaRDD<Block> rddSlices = sc.parallelize( blocks );
	
			final JavaPairRDD<Block, ArrayList< PointMatch >> rddFeatures = rddSlices.mapToPair(
				block ->
				{
					//if ( block.from != 400 )
					//	return null;

					final HashMap<String, List<Slice>> chA = stacks.get( block.channelA );
					final List< Slice > slicesA = chA.get( block.camA );

					final HashMap<String, List<Slice>> chB = stacks.get( block.channelB );
					final List< Slice > slicesB = chB.get( block.camB );

					/* this is the inverse */
					final AffineTransform2D camtransformA = block.getTransformA();//camTransforms.get( block.channel ).get( block.cam );
					final AffineTransform2D camtransformB = block.getTransformB();//camTransforms.get( block.channel ).get( block.cam );
	
					final N5FSReader n5Local = new N5FSReader(
							n5Path,
							new GsonBuilder().registerTypeAdapter(
									AffineTransform2D.class,
									new AffineTransform2DAdapter()));
	
					final ArrayList<AffineTransform2D> transformsA = n5Local.getAttribute(
							id + "/" + block.channelA,
							"transforms",
							new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

					final ArrayList<AffineTransform2D> transformsB = n5Local.getAttribute(
							id + "/" + block.channelB,
							"transforms",
							new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

					//if ( block.from != 200 )
					//	return new Tuple2<>(block, new ArrayList<>());
	
					final RandomAccessible<AffineTransform2D> alignmentTransformsA = Views.extendBorder(new ListImg<>(transformsA, transformsA.size()));
					final RandomAccessible<AffineTransform2D> alignmentTransformsB = Views.extendBorder(new ListImg<>(transformsB, transformsB.size()));

					System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + "): opening images." );
	
					ValuePair<RealRandomAccessible<UnsignedShortType>, RealInterval> alignedStackBoundsA =
							ViewISPIMStack.openAlignedStack(
									slicesA,
									new UnsignedShortType(),
									Interpolation.NLINEAR,
									camtransformA,
									alignmentTransformsA,
									block.from,
									block.to,
									false );

					// the stack is sitting at z=0, independent of the firstslice index
					if ( block.from != 0 )
						alignedStackBoundsA = new ValuePair<>(
								RealViews.transform(
										alignedStackBoundsA.getA(),
										new Translation3D(0, 0, block.from ) ),
								alignedStackBoundsA.getB() );

					ValuePair<RealRandomAccessible<UnsignedShortType>, RealInterval> alignedStackBoundsB =
							ViewISPIMStack.openAlignedStack(
									slicesB,
									new UnsignedShortType(),
									Interpolation.NLINEAR,
									camtransformB,
									alignmentTransformsB,
									block.from,
									block.to,
									false );

					// the stack is sitting at z=0, independent of the firstslice index
					if ( block.from != 0 )
						alignedStackBoundsB = new ValuePair<>(
								RealViews.transform(
										alignedStackBoundsB.getA(),
										new Translation3D(0, 0, block.from ) ),
								alignedStackBoundsB.getB() );

					final RealInterval realBoundsA2D = alignedStackBoundsA.getB();
					final RealInterval realBoundsA3D = Intervals.createMinMax(
								(long)Math.ceil(realBoundsA2D.realMin(0)),
								(long)Math.ceil(realBoundsA2D.realMin(1)),
								block.from,
								(long)Math.floor(realBoundsA2D.realMax(0)),
								(long)Math.floor(realBoundsA2D.realMax(1)),
								block.to);

					final RealInterval realBoundsB2D = alignedStackBoundsB.getB();
					final RealInterval realBoundsB3D = Intervals.createMinMax(
								(long)Math.ceil(realBoundsB2D.realMin(0)),
								(long)Math.ceil(realBoundsB2D.realMin(1)),
								block.from,
								(long)Math.floor(realBoundsB2D.realMax(0)),
								(long)Math.floor(realBoundsB2D.realMax(1)),
								block.to);

					final Interval displayInterval = Intervals.smallestContainingInterval( Intervals.intersect( realBoundsA3D, realBoundsB3D));

					final RandomAccessibleInterval< UnsignedShortType > imgA = Views.interval( Views.raster( alignedStackBoundsA.getA() ), displayInterval );
					final RandomAccessibleInterval< UnsignedShortType > imgB = Views.interval( Views.raster( alignedStackBoundsB.getA() ), displayInterval );

					final ImagePlus impA = ImageJFunctions.wrap(imgA, block.channelA, Executors.newFixedThreadPool( 8 ) ).duplicate();
					impA.setDimensions( 1, impA.getStackSize(), 1 );
					impA.resetDisplayRange();
					impA.show();

					final ImagePlus impB = ImageJFunctions.wrap(imgB, block.channelB, Executors.newFixedThreadPool( 8 ) ).duplicate();
					impB.setDimensions( 1, impB.getStackSize(), 1 );
					impB.resetDisplayRange();
					impB.show();

					System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + "): performing cross correlations." );

					final int blockSizeX = 400;
					final int blockSizeY = 400;

					final int blocksX = (int)(displayInterval.dimension( 0 ) / blockSizeX);
					final int blocksY = (int)(displayInterval.dimension( 1 ) / blockSizeX);
					final long[] downsampling = new long[] { 4, 4, 1 };

					final ExecutorService service = Executors.newFixedThreadPool( 1 );
					final ArrayList< PointMatch > candidates = new ArrayList<>();

					for ( double blockY = 0; blockY <= blocksY - 1; blockY += 0.5)
						for ( double blockX = 0; blockX <= blocksX - 1; blockX += 0.5 )
						{
							final Interval blockInterval = new FinalInterval(
									new long[] { displayInterval.min( 0 ) + Math.round( blockX * blockSizeX ), displayInterval.min( 1 ) + Math.round( blockY * blockSizeY ), displayInterval.min( 2 ) },
									new long[] { displayInterval.min( 0 ) + Math.round( blockX * blockSizeX + blockSizeX - 1 ), displayInterval.min( 1 ) + Math.round( blockY * blockSizeY + blockSizeY - 1 ), displayInterval.max( 2 ) });

							final RandomAccessibleInterval< UnsignedShortType > blockA = Downsample.downsample( Views.interval( imgA, blockInterval ), downsampling );
							final RandomAccessibleInterval< UnsignedShortType > blockB = Downsample.downsample( Views.interval( imgB, blockInterval ), downsampling );

							final PairWiseStitchingResult result = PCMHelper.computePhaseCorrelation(
									ImgLib2.wrapArrayUnsignedShortToImgLib1( FusionTools.copyImgNoTranslation(blockA, new ArrayImgFactory<>( new UnsignedShortType()), new UnsignedShortType(), service) ),
									ImgLib2.wrapArrayUnsignedShortToImgLib1( FusionTools.copyImgNoTranslation(blockB, new ArrayImgFactory<>( new UnsignedShortType()), new UnsignedShortType(), service) ),
									15, // peaks
									true, // subpixel localization
									1 // numThreads
									);

							if ( result.getCrossCorrelation() > block.rThreshold )
							{
								final Point p1 = new Point( new double[] {
										blockInterval.min( 0 ) + blockSizeX / 2,
										blockInterval.min( 1 ) + blockSizeY / 2,
										block.from + (block.from + block.to ) / 2} );
								final Point p2 = new Point( new double[] {
										p1.getL()[ 0 ] + result.getOffset( 0 ) * downsampling[ 0 ],
										p1.getL()[ 1 ] + result.getOffset( 1 ) * downsampling[ 1 ],
										p1.getL()[ 2 ] + result.getOffset( 2 ) * downsampling[ 2 ] } );

								candidates.add( new PointMatch( p1, p2 ) );
								}
						}

					service.shutdown();

					System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + "): candidates: " + candidates.size() );

					/*
					final MultiConsensusFilter filter = new MultiConsensusFilter<>(
//							new Transform.InterpolatedAffineModel2DSupplier(
//							(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
//							(Supplier<RigidModel2D> & Serializable)RigidModel2D::new, 0.25),
							(Supplier<TranslationModel2D> & Serializable)TranslationModel2D::new,
//							(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
							10000,
							5,
							0,
							5 );

					final ArrayList<PointMatch> matches = filter.filter(candidates);

					TranslationModel3D model = new TranslationModel3D();
					model.fit(matches);

					System.out.println( "matches: " + matches.size() + " model: " + model );

					final ArrayList<Point> sourcePoints = new ArrayList<>();
					PointMatch.sourcePoints(matches, sourcePoints);
					for ( final Point p : sourcePoints )
					{
						p.getL()[ 0 ] -= displayInterval.min( 0 );
						p.getL()[ 1 ] -= displayInterval.min( 1 );
					}
					impA.setRoi(mpicbg.ij.util.Util.pointsToPointRoi(sourcePoints));

					final ArrayList<InvertibleBoundable> models = new ArrayList<>();
					models.add( new mpicbg.trakem2.transform.TranslationModel3D() );
					models.add( model );
			
					final ArrayList<ImagePlus> images = new ArrayList< ImagePlus >();
					images.add( impA );
					images.add( impB );
			
					final CompositeImage overlay = OverlayFusion.createOverlay( new FloatType(), images, models, 3, 1, new NLinearInterpolatorFactory<FloatType>() );
					overlay.show();

					// TODO: return candidates, run RANSAC over all blocks

					SimpleMultiThreading.threadHaltUnClean();
					*/
	
					return new Tuple2<>(block, candidates );
				});
	
			/* cache the booleans, so features aren't regenerated every time */
			rddFeatures.cache();
	
			/* collect the results */
			final List<Tuple2<Block, ArrayList< PointMatch >>> results = rddFeatures.collect();
	
			pointsChA = new ArrayList<>();
			pointsChB = new ArrayList<>();
	
			final ArrayList< PointMatch > candidates = new ArrayList<>();

			for ( final Tuple2<Block, ArrayList< PointMatch >> tuple : results )
			{
				candidates.addAll(tuple._2() );

				if ( tuple._2().size() == 0 )
					System.out.println( "Warning: block " + tuple._1.from + " has 0 candidates");
			}

			System.out.println( "candidates: " + candidates.size()  );

			final MultiConsensusFilter filter = new MultiConsensusFilter<>(
//					new Transform.InterpolatedAffineModel2DSupplier(
					(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
//					(Supplier<RigidModel2D> & Serializable)RigidModel2D::new, 0.25),
//					(Supplier<TranslationModel2D> & Serializable)TranslationModel2D::new,
//					(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
					10000,
					5,
					0,
					50 );

			final ArrayList<PointMatch> matches = filter.filter(candidates);

			TranslationModel3D model = new TranslationModel3D();
			try {
				model.fit(matches);
			} catch (NotEnoughDataPointsException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			System.out.println( "matches: " + matches.size() + " model: " + model );

			SimpleMultiThreading.threadHaltUnClean();

			System.out.println( "saving points ... " );

			final N5FSWriter n5Writer = new N5FSWriter(n5Path);

			if (pointsChA.size() > 0)
			{
				final String featuresGroupName = n5Writer.groupPath(id + "/" + channelA, "Stack-PCM-candidates");
	
				if (n5Writer.exists(featuresGroupName))
					n5Writer.remove(featuresGroupName);
				
				n5Writer.createDataset(
						featuresGroupName,
						new long[] {1},
						new int[] {1},
						DataType.OBJECT,
						new GzipCompression());
	
				final String datasetName = id + "/" + channelA + "/Stack-PCM-candidates";
				final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);
	
				n5Writer.writeSerializedBlock(
						pointsChA,
						datasetName,
						datasetAttributes,
						new long[] {0});
			}
	
			if (pointsChB.size() > 0)
			{
				final String featuresGroupName = n5Writer.groupPath(id + "/" + channelB, "Stack-PCM-candidates");
	
				if (n5Writer.exists(featuresGroupName))
					n5Writer.remove(featuresGroupName);
				
				n5Writer.createDataset(
						featuresGroupName,
						new long[] {1},
						new int[] {1},
						DataType.OBJECT,
						new GzipCompression());
	
				final String datasetName = id + "/" + channelB + "/Stack-PCM-candidates";
				final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);
	
				n5Writer.writeSerializedBlock(
						pointsChB,
						datasetName,
						datasetAttributes,
						new long[] {0});
			}
		}

		System.out.println( new Date(System.currentTimeMillis() ) + ": channelA: " + pointsChA.size() + " points" );
		System.out.println( new Date(System.currentTimeMillis() ) + ": channelB: " + pointsChB.size() + " points" );

		//
		// alignment
		//

		final int numNeighbors = 3;
		final int redundancy = 0;
		final double ratioOfDistance = 2.0;
		final double differenceThreshold = Double.MAX_VALUE;
		final int numIterations = 10000;
		final double maxEpsilon = 5;
		final int minNumInliers = 25;

		// not enough points to build a descriptor
		if ( pointsChA.size() < numNeighbors + redundancy + 1 || pointsChB.size() < numNeighbors + redundancy + 1 )
			return;

		final List< PointMatch > candidates = 
				new FRGLDMMatcher<>().extractCorrespondenceCandidates(
						pointsChA,
						pointsChB,
						redundancy,
						ratioOfDistance ).stream().map( v -> (PointMatch)v).collect( Collectors.toList() );

		double minZ = localLastSliceIndex;
		double maxZ = firstSliceIndex;

		for ( final PointMatch pm : candidates )
		{
			//Mon Oct 19 20:26:19 EDT 2020: channelA: 60121 points
			//Mon Oct 19 20:26:19 EDT 2020: channelB: 86909 points
			minZ = Math.min( minZ, Math.min( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
			maxZ = Math.max( maxZ, Math.max( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
		}

		System.out.println( "candidates: " + candidates.size() + " from(z) " + minZ + " to(z) " + maxZ );

		final MultiConsensusFilter filter = new MultiConsensusFilter<>(
//				new Transform.InterpolatedAffineModel2DSupplier(
				(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
//				(Supplier<RigidModel2D> & Serializable)RigidModel2D::new, 0.25),
//				(Supplier<TranslationModel2D> & Serializable)TranslationModel2D::new,
//				(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
				numIterations,
				maxEpsilon,
				0,
				minNumInliers);

		final ArrayList<PointMatch> matches = filter.filter(candidates);

		minZ = localLastSliceIndex;
		maxZ = firstSliceIndex;

		for ( final PointMatch pm : matches )
		{
			minZ = Math.min( minZ, Math.min( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
			maxZ = Math.max( maxZ, Math.max( pm.getP1().getL()[ 2 ], pm.getP2().getL()[ 2 ] ) );
		}

		System.out.println( "matches: " + matches.size() + " from(z) " + minZ + " to(z) " + maxZ );

		try {
			final AffineModel3D affine = new AffineModel3D();
			affine.fit( matches );
			System.out.println( "affine (" + PointMatch.meanDistance( matches ) + "): " + affine );

			final TranslationModel3D translation = new TranslationModel3D();
			translation.fit( matches );
			System.out.println( "translation(" + PointMatch.meanDistance( matches ) + ")" + translation );

		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			e.printStackTrace();
		}

		/*
		final List< PointMatch > candidates = 
				new RGLDMMatcher<>().extractCorrespondenceCandidates(
						pointsChA,
						pointsChB,
						numNeighbors,
						redundancy,
						ratioOfDistance,
						differenceThreshold ).stream().map( v -> (PointMatch)v).collect( Collectors.toList() );
		*/
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

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException, ClassNotFoundException
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
				blocksize,
				rThreshold,
				tryLoadingCandidates );

		sc.close();

		return null;
	}
}
