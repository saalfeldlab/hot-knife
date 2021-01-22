package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.hotknife.MultiConsensusFilter;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;

import bdv.util.BdvStackSource;
import bdv.viewer.Interpolation;
import loci.formats.FormatException;
import mpicbg.imglib.wrapper.ImgLib2;
import mpicbg.models.AffineModel3D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel3D;
import mpicbg.stitching.PairWiseStitchingResult;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Intervals;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.downsampling.Downsample;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import scala.Tuple2;

public class SparkPaiwiseAlignChannelsPCM implements Callable<Void>, Serializable {

	private static final long serialVersionUID = 7771836820788861570L;

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

	@Option(names = "--rThreshold", required = false, description = "correlation threshold (default: 0.5)")
	private double rThreshold = 0.5;

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

	public static ArrayList<PointMatch> alignChannels(
			final String n5Path,
			final String id,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final int blockSize,
			final double rThreshold,
			final int numThreads ) throws FormatException, IOException, ClassNotFoundException, InterruptedException, ExecutionException
	{
		System.out.println( new Date(System.currentTimeMillis() ) + ": Opening N5." );

		final N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id );

		System.out.println( new Date(System.currentTimeMillis() ) + ": localLastSliceIndex=" + n5data.lastSliceIndex );

		final int localLastSliceIndex = n5data.lastSliceIndex ;
		final int firstSliceIndex = 0;

		System.out.println( "extracting PCM candidates ... " );

		ArrayList< PointMatch > candidates = new ArrayList<>();

		final int numSlices = localLastSliceIndex - firstSliceIndex + 1;
		final int numBlocks = numSlices / blockSize + (numSlices % blockSize > 0 ? 1 : 0);

		final ArrayList<Block> blocks = new ArrayList<>();

		for ( int i = 0; i < numBlocks; ++i )
		{
			final int from  = i * blockSize + firstSliceIndex;
			final int to = Math.min( localLastSliceIndex, from + blockSize - 1 );

			final Block block = new Block(
					from, to,
					channelA, camA, n5data.camTransforms.get( channelA ).get( camA ),
					channelB, camB, n5data.camTransforms.get( channelB ).get( camB ),
					rThreshold );

			blocks.add( block );
	
			System.out.println( "block " + i + ": " + from + " >> " + to );
		}

		//final JavaRDD<Block> rddSlices = sc.parallelize( blocks );
		//final JavaPairRDD<Block, ArrayList< PointMatch >> rddFeatures = rddSlices.mapToPair(
		//	block ->


		final ForkJoinPool pool = new ForkJoinPool( numThreads );

		List<Tuple2<Block, ArrayList< PointMatch >>> results = pool.submit( () -> blocks.parallelStream().map(block -> {
			//if ( block.from != 400 )
			//	return null;

			final HashMap<String, List<Slice>> chA = n5data.stacks.get( block.channelA );
			final List< Slice > slicesA = chA.get( block.camA );

			final HashMap<String, List<Slice>> chB = n5data.stacks.get( block.channelB );
			final List< Slice > slicesB = chB.get( block.camB );

			/* this is the inverse */
			final AffineTransform2D camtransformA = n5data.camTransforms.get( block.channelA ).get( block.camA );
			final AffineTransform2D camtransformB = n5data.camTransforms.get( block.channelB ).get( block.camB );

			//if ( block.from != 200 )
			//	return new Tuple2<>(block, new ArrayList<>());

			System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + "): opening images." );

			ValuePair<RealRandomAccessible<UnsignedShortType>, RealInterval> alignedStackBoundsA = null;
			try {
				alignedStackBoundsA = ViewISPIMStack.openAlignedStack(
						slicesA,
						new UnsignedShortType(),
						Interpolation.NLINEAR,
						camtransformA.inverse(), // pass the forward transform
						n5data.alignments.get( block.channelA ),
						block.from,
						block.to,
						false );
			} catch (FormatException | IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			// the stack is sitting at z=0, independent of the firstslice index
			if ( block.from != 0 )
				alignedStackBoundsA = new ValuePair<>(
						RealViews.transform(
								alignedStackBoundsA.getA(),
								new Translation3D(0, 0, block.from ) ),
						alignedStackBoundsA.getB() );

			ValuePair<RealRandomAccessible<UnsignedShortType>, RealInterval> alignedStackBoundsB = null;
			try {
				alignedStackBoundsB = ViewISPIMStack.openAlignedStack(
						slicesB,
						new UnsignedShortType(),
						Interpolation.NLINEAR,
						camtransformB.inverse(), // pass the forward transform
						n5data.alignments.get( block.channelB ),
						block.from,
						block.to,
						false );
			} catch (FormatException | IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

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

					final RandomAccessibleInterval< UnsignedShortType > blockA = Downsample.downsample( Views.interval( imgA, blockInterval ), downsampling, service );
					final RandomAccessibleInterval< UnsignedShortType > blockB = Downsample.downsample( Views.interval( imgB, blockInterval ), downsampling, service );

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

			//return candidatesLocal;

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
		}).collect(Collectors.toList() ) ).get();


		/* cache the booleans, so features aren't regenerated every time */
		//rddFeatures.cache();
		/* collect the results */
		//final List<Tuple2<Block, ArrayList< PointMatch >>> results = rddFeatures.collect();
		//candidates = new ArrayList<>();

		for ( final Tuple2<Block, ArrayList< PointMatch >> tuple : results )
		{
			candidates.addAll(tuple._2() );
	
			if ( tuple._2().size() == 0 )
				System.out.println( "Warning: block " + tuple._1.from + " has 0 matches");
		}

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
				new Transform.InterpolatedAffineModel2DSupplier(
				(Supplier<AffineModel3D> & Serializable)AffineModel3D::new,
//				(Supplier<RigidModel3D> & Serializable)RigidModel3D::new, 0.25),
				(Supplier<TranslationModel3D> & Serializable)TranslationModel3D::new, 0.25 ),
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

		// write matches
		System.out.println( "saving matches for " + id + " ... " );

		if ( matches.size() > 0 )
		{
			final N5FSWriter n5Writer = new N5FSWriter(n5Path);

			final String datasetName = id + "/matches_" + channelA + "_" + channelB;

			if (n5Writer.exists(datasetName))
				n5Writer.remove(datasetName);
			
			n5Writer.createDataset(
					datasetName,
					new long[] {1},
					new int[] {1},
					DataType.OBJECT,
					new GzipCompression());

			final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);

			n5Writer.writeSerializedBlock(
					matches,
					datasetName,
					datasetAttributes,
					new long[] {0});
		}

		return matches;

		// cam4 (Ch488+561+647nm) vs cam4 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch515+594nm)
		// cam1 (Ch405nm) vs cam3 (Ch488+561+647nm)**
	}

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException, ClassNotFoundException
	{
		final ArrayList< PointMatch > matches = alignChannels(
				n5Path,
				id,
				channelA,
				channelB,
				camA,
				camB,
				blocksize,
				rThreshold,
				Runtime.getRuntime().availableProcessors() );

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

			final N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id );

			bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channelA, camA, n5data.stacks.get( channelA ).get( camA ), n5data.alignments.get( channelA ), n5data.camTransforms.get( channelA ).get( camA ), new AffineTransform3D(), 0, n5data.lastSliceIndex );
			bdv = SparkPaiwiseAlignChannelsGeo.displayCam( bdv, channelB, camB, n5data.stacks.get( channelB ).get( camB ), n5data.alignments.get( channelB ), n5data.camTransforms.get( channelB ).get( camB ), transformB_a, 0, n5data.lastSliceIndex );

			System.out.println( "done" );


		} catch ( Exception e ) {
			e.printStackTrace();
		}

		return null;
	}
}
