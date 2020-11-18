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
import java.util.function.Supplier;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.MultiConsensusFilter;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.Interpolation;
import ij.CompositeImage;
import ij.ImageJ;
import ij.ImagePlus;
import loci.formats.FormatException;
import mpicbg.imglib.wrapper.ImgLib2;
import mpicbg.models.AffineModel2D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InvertibleBoundable;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.TranslationModel3D;
import mpicbg.stitching.PairWiseStitchingResult;
import mpicbg.stitching.fusion.OverlayFusion;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.list.ListImg;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.downsampling.Downsample;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
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

		// reset cam transforms to see if that is the reason
		//for ( final  HashMap<String, AffineTransform2D> c : camTransforms.values() )
		//	for ( final String key : c.keySet() )
		//		c.put( key, new AffineTransform2D() );
		
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

		ArrayList< PointMatch > candidates = null;

		if ( tryLoadingCandidates )
		{
			System.out.println( "trying to load PCM candidates ... " );

			try
			{
				final String datasetNameA = id + "/" + channelA + "/Stack-PCM-candidates";
				final DatasetAttributes datasetAttributesA = n5.getDatasetAttributes(datasetNameA);
	
				final String datasetNameB = id + "/" + channelB + "/Stack-PCM-candidates";
				final DatasetAttributes datasetAttributesB = n5.getDatasetAttributes(datasetNameB);
	
				//pointsChA = n5.readSerializedBlock(datasetNameA, datasetAttributesA, new long[] {0});
				///pointsChB = n5.readSerializedBlock(datasetNameB, datasetAttributesB, new long[] {0});
			}
			catch ( Exception e ) // java.nio.file.NoSuchFileException
			{
				candidates = null;
				e.printStackTrace();
			}
		}

		if ( candidates == null )
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
									camtransformA.inverse(), // pass the forward transform
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
									camtransformB.inverse(), // pass the forward transform
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

					//System.out.println( realBoundsA3D.realMin( 0 ) + ", " +realBoundsA3D.realMin( 1 ) + ", "+realBoundsA3D.realMin( 2 ) + " --- " + realBoundsA3D.realMax( 0 ) + ", " +realBoundsA3D.realMax( 1 ) + ", "+realBoundsA3D.realMax( 2 ) );
					//System.out.println( realBoundsB3D.realMin( 0 ) + ", " +realBoundsB3D.realMin( 1 ) + ", "+realBoundsB3D.realMin( 2 ) + " --- " + realBoundsB3D.realMax( 0 ) + ", " +realBoundsB3D.realMax( 1 ) + ", "+realBoundsB3D.realMax( 2 ) );
					
					final RealInterval intersectionInterval = Intervals.intersect( realBoundsA3D, realBoundsB3D );

					//System.out.println( intersectionInterval.realMin( 0 ) + ", " +intersectionInterval.realMin( 1 ) + ", "+intersectionInterval.realMin( 2 ) + " --- " + intersectionInterval.realMax( 0 ) + ", " +intersectionInterval.realMax( 1 ) + ", "+intersectionInterval.realMax( 2 ) );
					
					final Interval displayInterval = Intervals.smallestContainingInterval( intersectionInterval );

					//System.out.println( Util.printInterval( displayInterval ) );

					final RandomAccessibleInterval< UnsignedShortType > imgA = Views.interval( Views.raster( alignedStackBoundsA.getA() ), displayInterval );
					final RandomAccessibleInterval< UnsignedShortType > imgB = Views.interval( Views.raster( alignedStackBoundsB.getA() ), displayInterval );
					/*
					final ImagePlus impA = ImageJFunctions.wrap(imgA, block.channelA, Executors.newFixedThreadPool( 8 ) ).duplicate();
					impA.setDimensions( 1, impA.getStackSize(), 1 );
					impA.resetDisplayRange();
					impA.show();

					final ImagePlus impB = ImageJFunctions.wrap(imgB, block.channelB, Executors.newFixedThreadPool( 8 ) ).duplicate();
					impB.setDimensions( 1, impB.getStackSize(), 1 );
					impB.resetDisplayRange();
					impB.show();
					*/
					System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + "): performing cross correlations." );

					final int blockSizeX = 400;
					final int blockSizeY = 400;

					final int blocksX = (int)(displayInterval.dimension( 0 ) / blockSizeX);
					final int blocksY = (int)(displayInterval.dimension( 1 ) / blockSizeX);
					final long[] downsampling = new long[] { 4, 4, 1 };

					final ExecutorService service = Executors.newFixedThreadPool( 1 );
					final ArrayList< PointMatch > candidatesLocal = new ArrayList<>();

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
										block.from + (block.to - block.from ) / 2} );
								final Point p2 = new Point( new double[] {
										p1.getL()[ 0 ] + result.getOffset( 0 ) * downsampling[ 0 ],
										p1.getL()[ 1 ] + result.getOffset( 1 ) * downsampling[ 1 ],
										p1.getL()[ 2 ] + result.getOffset( 2 ) * downsampling[ 2 ] } );

								//System.out.println( Util.printCoordinates( p1.getL() ) + " - " + Util.printCoordinates( p2.getL() ) );
								candidatesLocal.add( new PointMatch( p1, p2 ) );
							}
						}

					service.shutdown();

					System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + "): candidates: " + candidatesLocal.size() );

					return new Tuple2<>(block, candidatesLocal);
					/*
					final MultiConsensusFilter filter = new MultiConsensusFilter<>(
							(Supplier<TranslationModel3D> & Serializable)TranslationModel3D::new,
							10000,
							5,
							0,
							10 );

					final ArrayList<PointMatch> matches = filter.filter(candidatesLocal);

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
					models.add( new TranslationModel3D() );
					models.add( model );
			
					final ArrayList<ImagePlus> images = new ArrayList< ImagePlus >();
					images.add( impA );
					images.add( impB );
			
					final CompositeImage overlay = OverlayFusion.createOverlay( new FloatType(), images, models, 3, 1, new NLinearInterpolatorFactory<FloatType>() );
					overlay.show();

					final RealRandomAccessible< UnsignedShortType > tImgA = RealViews.affineReal( Views.interpolate( Views.extendZero( imgA ), new NLinearInterpolatorFactory<UnsignedShortType>() ), TransformationTools.getAffineTransform( model ).inverse() );
					BdvOptions options = new BdvOptions();
					BdvStackSource<?> bdv = BdvFunctions.show( tImgA, imgA, "imgA_t" );
					//bdv = BdvFunctions.show(imgB, "imgB", options.addTo( bdv ) );
					BdvFunctions.show(Views.extendZero( imgB ), imgB, "imgB", options.addTo( bdv ) );
					//SimpleMultiThreading.threadHaltUnClean();
					*/
	
					//return new Tuple2<>( block, candidatesLocal );
					//return new Tuple2<>( block, matches );
				});
	
			/* cache the booleans, so features aren't regenerated every time */
			rddFeatures.cache();
	
			/* collect the results */
			final List<Tuple2<Block, ArrayList< PointMatch >>> results = rddFeatures.collect();

			candidates = new ArrayList<>();

			for ( final Tuple2<Block, ArrayList< PointMatch >> tuple : results )
			{
				candidates.addAll(tuple._2() );

				if ( tuple._2().size() == 0 )
					System.out.println( "Warning: block " + tuple._1.from + " has 0 matches");
			}

			/*
			System.out.println( "saving candidates ... " );

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
			}*/
		}

		//System.out.println( new Date(System.currentTimeMillis() ) + ": channelA: " + pointsChA.size() + " points" );
		//System.out.println( new Date(System.currentTimeMillis() ) + ": channelB: " + pointsChB.size() + " points" );

		//
		// alignment
		//

		final int numIterations = 10000;
		final double maxEpsilon = 5;
		final int minNumInliers = 25;

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
				(Supplier<AffineModel3D> & Serializable)AffineModel3D::new,
//				(Supplier<RigidModel3D> & Serializable)RigidModel3D::new, 0.25),
//				(Supplier<TranslationModel3D> & Serializable)TranslationModel3D::new,
//				(Supplier<RigidModel3D> & Serializable)RigidModel3D::new,
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

		try
		{
			final TranslationModel3D translation = new TranslationModel3D();
			translation.fit( matches );
			System.out.println( "translation(" + PointMatch.meanDistance( matches ) + ")" + translation );
			
			final AffineModel3D affine = new AffineModel3D();
			affine.fit( matches );
			System.out.println( "affine (" + PointMatch.meanDistance( matches ) + "): " + affine );

			BdvStackSource<?> bdv = null;

			final AffineTransform3D transformB_a = TransformationTools.getAffineTransform( affine );//.inverse();
			System.out.println( transformB_a );

			bdv = SparkPaiwiseAlignChannelsGeo.displayOverlap( bdv, channelA, camA, stacks.get( channelA ).get( camA ), alignments.get( channelA ), camTransforms.get( channelA ).get( camA ), new AffineTransform3D(), firstSliceIndex, localLastSliceIndex );
			bdv = SparkPaiwiseAlignChannelsGeo.displayOverlap( bdv, channelB, camB, stacks.get( channelB ).get( camB ), alignments.get( channelB ), camTransforms.get( channelB ).get( camB ), transformB_a, firstSliceIndex, localLastSliceIndex );

			System.out.println( "done" );


		} catch ( Exception e ) {
			e.printStackTrace();
		}
		
		// cam4 (Ch488+561+647nm) vs cam4 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch488+561+647nm)**
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
