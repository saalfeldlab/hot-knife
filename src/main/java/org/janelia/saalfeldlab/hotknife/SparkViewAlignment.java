package org.janelia.saalfeldlab.hotknife;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import ij.ImagePlus;
import ij.io.FileSaver;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.Views;

public class SparkViewAlignment
{
	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "-i", aliases = {"--n5Group"}, required = false, usage = "N5 group, e.g. /align-0")
		private String group = null;

		@Option(name = "--scaleIndex", required = true, usage = "scale index for visualization, e.g. 4 (means scale = 1.0 / 2^4)")
		private int transformScaleIndex = 0;

		@Option(name = "--saveDir", required = true, usage = "directory for saving images")
		private String saveDir = "";

		@Option(name = "--ignoreTransforms", required = false, usage = "do not load transforms, instead use identity transforms")
		private boolean ignoreTransforms = false;

		@Option(name = "--localSparkBindAddress", usage = "specify Spark bind address as localhost")
		private boolean localSparkBindAddress = false;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);

				parsedSuccessfully = true;

			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		public String getN5Path() { return n5Path; }
		public int getScaleIndex() { return transformScaleIndex; }
		public String saveDir() { return saveDir; }
		public String getGroup() { return group; }
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException
	{

		final SparkViewAlignment.Options options = new SparkViewAlignment.Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final String n5Path = options.getN5Path();
		final String group = options.getGroup();
		final N5Reader n5global = new N5FSReader(n5Path);

		final String[] datasetNames = n5global.getAttribute(group, "datasets", String[].class);
		final String[] transformDatasetNames = n5global.getAttribute(group, "transforms", String[].class);
		final double[] boundsMin = n5global.getAttribute(group, "boundsMin", double[].class);
		final double[] boundsMax = n5global.getAttribute(group, "boundsMax", double[].class);

		n5global.close();

		final int showScaleIndex = options.getScaleIndex();
		final double showScale = 1.0 / (1 << showScaleIndex);

		final SparkConf conf = new SparkConf().setAppName("SparkViewAlignment");
		if (options.localSparkBindAddress)
			conf.set("spark.driver.bindAddress", "127.0.0.1");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");

		long t = System.currentTimeMillis();

		final ArrayList< Integer > zIndex = new ArrayList<>();
		for ( int z = 0; z < datasetNames.length; ++z )
			zIndex.add( z );

		final JavaRDD<Integer> rdd = sparkContext.parallelize( zIndex );
		rdd.repartition( zIndex.size() );

		rdd.foreach( z ->
		{
			final N5Reader n5 = new N5FSReader(n5Path);

			String datasetName = datasetNames[ z ];
			String transformDatasetName = transformDatasetNames[ z ];

			System.out.println( new Date( System.currentTimeMillis() ) + ": z=" + z + " >>> " + transformDatasetName );

			final RealTransform[] realTransforms =
					new RealTransform[] { Transform.loadScaledTransform( n5, group + "/" + transformDatasetName) };

			final RandomAccessibleInterval<UnsignedByteType> stack = Transform.createTransformedStackUnsignedByteType(
					options.getN5Path(),
					Arrays.asList(datasetName),
					showScaleIndex,
					Arrays.asList(realTransforms),
					new FinalInterval(
							Grid.floorScaled(boundsMin, showScale),
							Grid.ceilScaled(boundsMax, showScale)));

			/*
			// Potential speedup with local multithreading
			final ExecutorService service = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
			final RandomAccessibleInterval<UnsignedByteType> slice = Views.hyperSlice( stack, 2, z );
			final RandomAccessibleInterval<UnsignedByteType> copy = Views.translate( new ArrayImgFactory<>( new UnsignedByteType() ).create( slice.dimensionsAsLongArray() ), slice.minAsLongArray() );
			Util.copy(slice, copy, service, false);
			final ImagePlus imp = ImageJFunctions.wrap( copy, "z=" + z );
			service.shutdown();
			*/

			final ImagePlus imp = ImageJFunctions.wrap( Views.hyperSlice( stack, 2, 0 ), "z=" + z );
			new FileSaver(imp).saveAsTiff( new File( options.saveDir(), imp.getTitle() ).getAbsolutePath() );
			imp.close();
			n5.close();

			System.out.println( new Date( System.currentTimeMillis() ) + ": SAVED z=" + z + " >>> " + transformDatasetName );
		});

		sparkContext.close();

		System.out.println( "took " + (( System.currentTimeMillis() - t )/1000) + " secs.");
	}
}
