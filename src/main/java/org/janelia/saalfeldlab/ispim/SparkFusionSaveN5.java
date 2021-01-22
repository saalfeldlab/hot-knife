package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import com.google.gson.GsonBuilder;

import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class SparkFusionSaveN5 implements Callable<Void>, Serializable 
{
	private static final long serialVersionUID = 7514919638900822732L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--channel", required = true, description = "Channel key, e.g. Ch488+561+647nm")
	private String channel = null;

	@Option(names = "--cam", required = true, description = "Cam key, e.g. cam1")
	private String cam = null;

	@SuppressWarnings("unchecked")
	public static void saveN5(
			final JavaSparkContext sc,
			final Interval fused,
			final String n5Path,
			final int[] outBlockSize,
			final ArrayList<String> ids, final String channel, final String cam) throws IOException
	{
		final N5Writer n5 = new N5FSWriter(n5Path);

		final String outDatasetName = "maxfusion_"+channel+"_"+cam + "/s0";

		final long[] dimensions = new long[ fused.numDimensions() ];
		fused.dimensions( dimensions );

		final long[] min = new long[ fused.numDimensions() ];
		fused.min( min );

		n5.createDataset(
				outDatasetName,
				dimensions,
				outBlockSize,
				DataType.UINT16,
				new GzipCompression( 1 ) );

		n5.setAttribute( outDatasetName, "min", min);

		final JavaRDD<long[][]> rdd =
				sc.parallelize(
						Grid.create(
								dimensions,
								outBlockSize));

		System.out.println( "numBlocks = " + Grid.create( dimensions, outBlockSize).size() );

		rdd.foreach(
				gridBlock -> {
					final N5Writer n5Writer = new N5FSWriter(n5Path);
					final RandomAccessibleInterval<?> source = Views.zeroMin( RenderFullStack.fuseMax( n5Path, ids, channel, cam ) );
					@SuppressWarnings("rawtypes")
					final RandomAccessibleInterval sourceGridBlock = Views.offsetInterval(source, gridBlock[0], gridBlock[1]);
					N5Utils.saveBlock(sourceGridBlock, n5Writer, outDatasetName, gridBlock[2]);
				});
	}

	@Override
	public Void call() throws Exception
	{
		final N5Writer n5 = new N5FSWriter(
				n5Path,
				new GsonBuilder().
					registerTypeAdapter(
						AffineTransform3D.class,
						new AffineTransform3DAdapter()).
					registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final ArrayList<String> allIds = new ArrayList<String>();
		allIds.addAll( SparkPaiwiseAlignChannelsGeoAll.getIds(n5) );
		Collections.sort( allIds );

		/*
		ArrayList< String > list = new ArrayList<>();
		list.add( allIds.get( 0 ) );
		list.add( allIds.get( 1 ) );
		*/

		RandomAccessibleInterval< UnsignedShortType > fused = RenderFullStack.fuseMax( n5Path, allIds, channel, cam );

		System.out.println( "bounding box: " + Util.printInterval( fused ) );

		final SparkConf conf = new SparkConf().setAppName("SparkFusionSaveN5");

		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		saveN5( sc, fused, n5Path, new int[] { 2048, 2048, 32 }, allIds, channel, cam );

		sc.close();

		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new SparkFusionSaveN5()).execute(args));
	}
}
