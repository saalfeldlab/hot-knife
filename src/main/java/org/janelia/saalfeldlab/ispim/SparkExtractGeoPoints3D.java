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
package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.Block;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;

import com.google.common.reflect.TypeToken;
import com.google.gson.GsonBuilder;

import bdv.viewer.Interpolation;
import loci.formats.FormatException;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.list.ListImg;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.downsampling.Downsample;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import scala.Tuple2;

/**
 * Align a 3D N5 dataset.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command(
		name = "SparkPaiwiseAlignChannelsGeoAll",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Align all pairs of channels using geometric local descriptor matching")
public class SparkExtractGeoPoints3D implements Callable<Void>, Serializable {

	private static final long serialVersionUID = 6708886268386777152L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--minIntensity", required = false, description = "min intensity")
	private double minIntensity = 0;

	@Option(names = "--maxIntensity", required = false, description = "max intensity")
	private double maxIntensity = 4096;

	@Option(names = "--sigma", required = false, description = "DoG Sigma (default: 2.0)")
	private double sigma = 2.0;

	@Option(names = "--threshold", required = false, description = "DoG threshold (default: 0.004)")
	private double threshold = 0.004;

	@Option(names = "--downsample", required = false, description = "downsampling (default: 1.0 - no downsampling)")
	private int downsample = 1;

	@Option(names = "--channel", required = true, description = "Channel key, e.g. Ch488+561+647nm")
	private String channel = null;

	@Option(names = "--cam", required = true, description = "Cam key, e.g. cam1")
	private String cam = null;

	@Option(names = {"-b", "--blocksize"}, required = false, description = "blocksize in z for point extraction (default: 20)")
	private int blocksize = 20;

	@Option(names = "--excludeIds", split=",", required = false, description = "ids to be exluded")
	private HashSet<String> excludeIds = new HashSet<>();

	@SuppressWarnings("serial")
	public static List<String> getIds(final N5Reader n5,final HashSet<String> excludeIds) throws IOException {

		List< String > ids = n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());

		return ids.stream().filter( id -> excludeIds.contains( id ) ).collect( Collectors.toList() );
	}

	public static ArrayList< Block > assembleBlocks(
			final String n5Path,
			final String id,
			final String channel,
			final String cam,
			final int blockSize,
			final double sigma, // 2.0
			final double threshold, // /*0.02*/0.004
			final double minIntensity,
			final double maxIntensity,
			final int downsampling ) throws IOException
	{
		return assembleBlocks( SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id ), id, channel, cam, blockSize, sigma, threshold, minIntensity, maxIntensity, downsampling );
	}

	public static ArrayList< Block > assembleBlocks(
			final N5Data n5data,
			final String id,
			final String channel,
			final String cam,
			final int blockSize,
			final double sigma, // 2.0
			final double threshold, // /*0.02*/0.004
			final double minIntensity,
			final double maxIntensity,
			final int downsampling )
	{
		final int lastSliceIndex = n5data.lastSliceIndex;
		final int numSlices = lastSliceIndex - + 1;
		final int numBlocks = numSlices / blockSize + (numSlices % blockSize > 0 ? 1 : 0);

		final ArrayList<Block> blocks = new ArrayList<>();

		System.out.println( "("+id+"/"+channel+"/" +cam + "):" + " numblocks = " + numBlocks + " from " + n5data.lastSliceIndex + " slices." );

		for ( int i = 0; i < numBlocks; ++i )
		{
			final int from  = i * blockSize;
			final int to = Math.min( lastSliceIndex, from + blockSize - 1 );

			final Block block = new Block(from, to, n5data.n5path, id, channel, cam, n5data.camTransforms.get( channel ).get( cam ), sigma, threshold, minIntensity, maxIntensity, downsampling );

			blocks.add( block );
	
			System.out.println( "block " + i + ": " + from + " >> " + to + " for id=" + id + ", channel=" + channel + ", cam=" + cam );

			/*
			// visible error: from=200, to=219, ch=Ch515+594nm (cam=cam1) Pos012
			if ( blockChannelB.from == 200 )
			{
				new ImageJ();
				viewBlock( stacks.get( blockChannelB.channel ), blockChannelB, firstSliceIndex, localLastSlice, n5Path, id );
				SimpleMultiThreading.threadHaltUnClean();
			}*/
		}

		return blocks;
	}

	public static void extractPoints(
			final JavaSparkContext sc,
			final String n5Path,
			final ArrayList< Block > blocks ) throws IOException
	{
		final JavaRDD<Block> rddSlices = sc.parallelize( blocks );

		final JavaPairRDD<Block, ArrayList< InterestPoint >> rddFeatures = rddSlices.mapToPair(
			block ->
			{
				final N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( block.n5Path, block.id );

				final HashMap<String, List<Slice>> ch = n5data.stacks.get( block.channel );
				final List< Slice > slices = ch.get( block.cam );

				/* this is the inverse */
				final AffineTransform2D camtransform = block.getTransform();//camTransforms.get( block.channel ).get( block.cam );

				final N5FSReader n5Local = new N5FSReader(
						n5data.n5path,
						new GsonBuilder().registerTypeAdapter(
								AffineTransform2D.class,
								new AffineTransform2DAdapter()));

				final ArrayList<AffineTransform2D> transforms = n5Local.getAttribute(
						block.id + "/" + block.channel,
						"transforms",
						new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

				//if ( block.from != 200 || block.channel.equals( "Ch488+561+647nm" ) )
				//	return new Tuple2<>(block, new ArrayList<>());

				final RandomAccessible<AffineTransform2D> alignmentTransforms = Views.extendBorder(new ListImg<>(transforms, transforms.size()));

				System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + ", ch=" + block.channel + " (cam=" + block.cam + "): opening images for " + block.id );

				final Pair<RandomAccessibleInterval< UnsignedShortType >, RandomAccessibleInterval< UnsignedShortType >> imgs =
						PairwiseAlignChannelsUtil.openRandomAccessibleIntervals(
								slices,
								new UnsignedShortType(0),
								Interpolation.NLINEAR,
								camtransform.inverse(), // pass the forward transform
								alignmentTransforms,//alignments.get( channelA ),
								Math.max( 0, block.from  - block.gaussOverhead ),
								Math.min( n5data.lastSliceIndex, block.to + block.gaussOverhead ) );

				System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + ", ch=" + block.channel + " (cam=" + block.cam + "): finding points for " + block.id );

				//System.out.println( Util.printInterval( imgs.getA() ) );

				final int zBlockSize = (int)( imgs.getA().dimension( 2 ) / 2  + imgs.getA().dimension( 2 ) % 2 );

				final ExecutorService service = Executors.newFixedThreadPool( 1 );

				RandomAccessibleInterval<UnsignedShortType> input =
						FusionTools.cacheRandomAccessibleInterval( imgs.getA(), new UnsignedShortType(), new int[] { 64, 64, zBlockSize } );

				RandomAccessibleInterval<UnsignedShortType> mask =
						FusionTools.cacheRandomAccessibleInterval( imgs.getB(), new UnsignedShortType(), new int[] { 64, 64, zBlockSize } );

				final long[] min = new long[ input.numDimensions() ];
				input.min( min );

				final ImgFactory< UnsignedShortType > f = new CellImgFactory<>( new UnsignedShortType() );

				final int downsample = block.downsampling;

				for ( int dsx = downsample; dsx > 1; dsx /= 2 )
				{
					input = Downsample.simple2x( Views.zeroMin( input ), f, new boolean[]{ true, false, false }, service );
					mask = Downsample.simple2x( Views.zeroMin( mask ), f, new boolean[]{ true, false, false }, service );
				}

				for ( int dsy = downsample; dsy > 1; dsy /= 2 )
				{
					input = Downsample.simple2x( Views.zeroMin( input ), f, new boolean[]{ false, true, false }, service );
					mask = Downsample.simple2x( Views.zeroMin( mask ), f, new boolean[]{ false, true, false }, service );
				}

				for ( int dsz = downsample; dsz > 1; dsz /= 2 )
				{
					input = Downsample.simple2x( Views.zeroMin( input ), f, new boolean[]{ false, false, true }, service );
					mask = Downsample.simple2x( Views.zeroMin( mask ), f, new boolean[]{ false, false, true }, service );
				}

				final ArrayList< InterestPoint > initialPoints =
						DoGImgLib2.computeDoG(
								input,
								mask,
								block.sigma,
								block.threshold,
								1, /*localization*/
								false, /*findMin*/
								true, /*findMax*/
								block.minIntensity, /* min intensity */
								block.maxIntensity, /* max intensity */
								new int[] { 512/downsample, 512/downsample, zBlockSize/downsample }, // choose good blocksize in z
								service,
								1 );

				service.shutdown();

				// exclude points that lie within the Gauss overhead
				final ArrayList< InterestPoint > points = new ArrayList<>();

				if ( downsample > 1 )
				{
					// if image was downsampled correct for downsampling and offset
					for ( final InterestPoint ip : initialPoints )
					{
						for ( int d = 0; d < ip.getL().length; ++d )
						{
							ip.getL()[ d ] = ip.getL()[ d ] * downsample + min[ d ];
							ip.getW()[ d ] = ip.getW()[ d ] * downsample + min[ d ];
						}
					}
				}

				for ( final InterestPoint ip : initialPoints )
					if ( ip.getDoublePosition( 2 ) > block.from - 0.5 && ip.getDoublePosition( 2 ) < block.to + 0.5 )
						points.add( ip );

				System.out.println( new Date(System.currentTimeMillis() ) + ": from=" + block.from + ", to=" + block.to + ", ch=" + block.channel + " (cam=" + block.cam + "): " + points.size() + " points for " + block.id );

				/*if ( block.from == 200 )
				{
					final ImagePlus impA = ImageJFunctions.wrap(imgs.getA(), block.channel, Executors.newFixedThreadPool( 8 ) ).duplicate();
					impA.setDimensions( 1, impA.getStackSize(), 1 );
					impA.resetDisplayRange();
					impA.show();
	
					//final ImagePlus impAw = ImageJFunctions.wrap(imgs.getB(), block.channel + "_w", Executors.newFixedThreadPool( 8 ) ).duplicate();
					//impAw.setDimensions( 1, impAw.getStackSize(), 1 );
					//impAw.resetDisplayRange();
					//impAw.show();

					final long[] dim = new long[ imgs.getA().numDimensions() ];
					final long[] min = new long[ imgs.getA().numDimensions() ];
					imgs.getA().dimensions( dim );
					imgs.getA().min( min );

					final RandomAccessibleInterval< FloatType > dots = Views.translate( ArrayImgs.floats( dim ), min );
					final RandomAccess< FloatType > rDots = dots.randomAccess();

					for ( final InterestPoint ip : points )
					{
						for ( int d = 0; d < dots.numDimensions(); ++d )
							rDots.setPosition( Math.round( ip.getFloatPosition( d ) ), d );

						rDots.get().setOne();
					}

					Gauss3.gauss( 1, Views.extendZero( dots ), dots );
					final ImagePlus impP = ImageJFunctions.wrap( dots, "detections_" + block.channel, Executors.newFixedThreadPool( 8 ) ).duplicate();
					impP.setDimensions( 1, impP.getStackSize(), 1 );
					impP.setSlice( impP.getStackSize() / 2 );
					impP.resetDisplayRange();
					impP.show();
				}*/

				return new Tuple2<>(block, points);
			});

		/* cache the booleans, so features aren't regenerated every time */
		rddFeatures.cache();

		/* collect the results */
		final List<Tuple2<Block, ArrayList< InterestPoint >>> results = rddFeatures.collect();

		final HashMap< ValuePair< String, String >, ArrayList< InterestPoint > > allPoints = new HashMap<>();

		for ( final Tuple2<Block, ArrayList< InterestPoint >> tuple : results )
		{
			final ValuePair< String, String > key = new ValuePair<>( tuple._1().id, tuple._1().channel );
			allPoints.putIfAbsent( key, new ArrayList<>() );
			allPoints.get( key ).addAll( tuple._2() );

			if ( tuple._2().size() == 0 )
				System.out.println( "Warning: block " + tuple._1.from + " has 0 detections (id=" + tuple._1().id + ", ch=" + tuple._1().channel + ")" );
		}

		//System.out.println( "fixing ids (they were duplicate due to paralell processing) ... " );

		for ( final ArrayList< InterestPoint > points : allPoints.values() )
			for ( int i = 0; i < points.size(); ++i )
				points.set( i, new InterestPoint( i, points.get( i ).getL() ) );

		//System.exit( 0 );

		final N5FSWriter n5Writer = new N5FSWriter(n5Path);

		for ( final Entry< ValuePair< String, String >, ArrayList< InterestPoint > > entry : allPoints.entrySet() )
		{
			if (entry.getValue().size() > 0)
			{
				System.out.println( "saving " + entry.getValue().size() + " points for id=" + entry.getKey().getA() + ", channel=" + entry.getKey().getB() + " ... " );

				final String featuresGroupName = n5Writer.groupPath( entry.getKey().getA() + "/" + entry.getKey().getB(), "Stack-DoG-detections"); // id, channel
	
				if (n5Writer.exists(featuresGroupName))
					n5Writer.remove(featuresGroupName);
				
				n5Writer.createDataset(
						featuresGroupName,
						new long[] {1},
						new int[] {1},
						DataType.OBJECT,
						new GzipCompression());
	
				final String datasetName = entry.getKey().getA() + "/" + entry.getKey().getB() + "/Stack-DoG-detections";
				final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);
	
				n5Writer.writeSerializedBlock(
						entry.getValue(),
						datasetName,
						datasetAttributes,
						new long[] {0});
			}
		}
	}

	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException {

		System.out.println(Arrays.toString(excludeIds.toArray()));

		final N5Reader n5 = new N5FSReader(n5Path);

		final SparkConf conf = new SparkConf().setAppName("SparkExtractGeoPoints3D");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		final ArrayList< Block > blocks = new ArrayList<>();

		getIds(n5,excludeIds).stream().forEach(
				id -> {
					try {
						blocks.addAll(
								assembleBlocks(
										n5Path, id, channel, cam, blocksize, sigma, /* 0.02 */threshold, minIntensity, maxIntensity, downsample) );
					} catch (IOException e) {
						e.printStackTrace();
					}
				});

		System.out.println( "total number of blocks: " + blocks.size() );

		extractPoints( sc, n5Path, blocks );

		sc.close();

		System.out.println("Done.");

		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new SparkExtractGeoPoints3D()).execute(args));
	}
}
