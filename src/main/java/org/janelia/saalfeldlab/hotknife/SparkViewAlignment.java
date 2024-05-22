package org.janelia.saalfeldlab.hotknife;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import ij.ImagePlus;
import ij.io.FileSaver;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.Views;

/**
 * Visualizes an alignment created by {@link SparkPairAlignSIFTAverage}. This class processes the images in parallel
 * using Spark and automatically saves the images to disk as TIFF files.
 *
 * @author Stephan Preibisch
 */
public class SparkViewAlignment
{
	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path;

		@Option(name = "-i", aliases = {"--n5Group"}, required = false, usage = "N5 group, e.g. /align-0")
		private String group = "/";

		@Option(name = "--scaleIndex", required = true, usage = "scale index for visualization, e.g. 4 (means scale = 1.0 / 2^4)")
		private int transformScaleIndex;

		@Option(name = "-o", aliases = {"--saveDir"}, required = true, usage = "directory for saving images")
		private String saveDir;

		@Option(name = "--ignoreTransforms", required = false, usage = "do not load transforms, instead use identity transforms")
		private boolean ignoreTransforms = false;

		@Option(name = "--localSparkBindAddress", required = false, usage = "specify Spark bind address as localhost")
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

		// create save directory if necessary
		final File saveDir = new File(options.saveDir());
		final boolean directoryExists = saveDir.exists() || saveDir.mkdirs();
		if (!directoryExists) {
			throw new RuntimeException("Could not create directory " + saveDir.getAbsolutePath());
		}

		final int showScaleIndex = options.getScaleIndex();
		final double showScale = 1.0 / (1 << showScaleIndex);

		final SparkConf conf = new SparkConf().setAppName("SparkViewAlignment");
		if (options.localSparkBindAddress)
			conf.set("spark.driver.bindAddress", "127.0.0.1");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");

		long t = System.currentTimeMillis();

		final List<Integer> zIndex = IntStream.range(0, datasetNames.length).boxed().collect(Collectors.toList());

		final JavaRDD<Integer> rdd = sparkContext.parallelize( zIndex );
		rdd.repartition( zIndex.size() );

		rdd.foreach( z ->
		{
			final N5Reader n5 = new N5FSReader(n5Path);

			String datasetName = datasetNames[ z ];
			String transformDatasetName = transformDatasetNames[ z ];

			System.out.println( new Date( System.currentTimeMillis() ) + ": z=" + z + " >>> " + transformDatasetName );

			final List<RealTransform> realTransforms = Collections.singletonList(Transform.loadScaledTransform(n5, group + "/" + transformDatasetName));

			final RandomAccessibleInterval<UnsignedByteType> stack = Transform.createTransformedStackUnsignedByteType(
					options.getN5Path(),
					Collections.singletonList(datasetName),
					showScaleIndex,
					realTransforms,
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

			final String title = "z=" + z;
			final ImagePlus imp = ImageJFunctions.wrap(Views.hyperSlice(stack, 2, 0), title);
			new FileSaver(imp).saveAsTiff(new File(options.saveDir(), title + ".tif").getAbsolutePath());
			imp.close();
			n5.close();

			System.out.println( new Date( System.currentTimeMillis() ) + ": SAVED z=" + z + " >>> " + transformDatasetName );
		});

		sparkContext.close();

		System.out.println( "took " + (( System.currentTimeMillis() - t )/1000) + " secs.");
	}
}
