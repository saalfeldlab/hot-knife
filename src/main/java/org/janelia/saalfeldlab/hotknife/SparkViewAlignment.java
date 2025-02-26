package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;
import org.janelia.scicomp.n5.zstandard.ZstandardCompression;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import ij.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
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

		@Option(name = "--zFrom", usage = "surface slice index to start with, inclusive (default: 0)")
		private Integer zFrom = null;

		@Option(name = "--zTo", usage = "surface slice index to end with, exclusive (default: datasetNames.size() as defined in the N5)")
		private Integer zTo = null;

		@Option(name = "-o", aliases = {"--zarrFolder"}, required = true, usage = "ZARR container for saving images (will be created or appended)")
		private String zarrOut;

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
		public String zarrOut() { return zarrOut; }
		public String getGroup() { return group; }
	}

	final static int[] blockSize = new int[] { 512, 512 };

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException
	{

		final SparkViewAlignment.Options options = new SparkViewAlignment.Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final String n5Path = options.getN5Path();
		final String group = options.getGroup();
		final N5Reader n5global = new N5Factory().openReader( StorageFormat.N5, n5Path );

		final String[] datasetNames = n5global.getAttribute(group, "datasets", String[].class);
		final String[] transformDatasetNames = n5global.getAttribute(group, "transforms", String[].class);
		final double[] boundsMin = n5global.getAttribute(group, "boundsMin", double[].class);
		final double[] boundsMax = n5global.getAttribute(group, "boundsMax", double[].class);

		n5global.close();

		// create/open output ZARR
		final int showScaleIndex = options.getScaleIndex();
		final double showScale = 1.0 / (1 << showScaleIndex);

		final Interval interval = getInterval( boundsMin, boundsMax, showScale );

		System.out.println( "Export interval: " + Util.printInterval( interval ) + ", scale=" + showScale );
		System.out.println( datasetName( datasetNames[ 0 ] ) );

		final int zFrom, zTo;
		final String zarrPath = options.zarrOut();

		try
		{
			if (options.zFrom == null) zFrom = 0; else zFrom = options.zFrom;
			if (options.zTo == null) zTo = datasetNames.length; else zTo = options.zTo;

			final N5Writer zarrWriterGlobal = new N5Factory().openWriter( StorageFormat.ZARR, zarrPath );

			for ( int i = zFrom; i < zTo; ++i )
			{
				System.out.println( "Creating dataset '" + datasetName( datasetNames[ i ] ) + "' ...");
				zarrWriterGlobal.createDataset(
						datasetName( datasetNames[ i ] ),
						new DatasetAttributes( interval.dimensionsAsLongArray(), blockSize, DataType.UINT8, new ZstandardCompression() ) );
			}

			zarrWriterGlobal.close();
		}
		catch ( Exception e )
		{
			throw new RuntimeException( "Could create/open zarr " + options.zarrOut() );
		}

		final List<long[][]> grid = Grid.create( interval.dimensionsAsLongArray(), blockSize );
		final List<Integer> zIndices = IntStream.range( zFrom, zTo ).boxed().collect(Collectors.toList());

		System.out.println( "Grid size: " + grid.size() );
		System.out.println( "Z index size: " + zIndices.size() );

		final ArrayList< long[][][] > allTasks = new ArrayList<>();

		zIndices.forEach( z ->
			grid.forEach( block ->
			{
				final long[][][] gridFull = new long[ 2 ][][];

				gridFull[ 0 ] = block;
				gridFull[ 1 ] = new long[][]{{z}};

				allTasks.add( gridFull );
			})
		);

		System.out.println( "Total number of tasks: " + allTasks.size() );

		final SparkConf conf = new SparkConf().setAppName("SparkViewAlignment");

		if (options.localSparkBindAddress)
			conf.set("spark.driver.bindAddress", "127.0.0.1");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);

		sparkContext.setLogLevel("ERROR");

		long t = System.currentTimeMillis();

		final JavaRDD<long[][][]> rdd = sparkContext.parallelize( allTasks );
		rdd.repartition( allTasks.size() );

		final long[] bbMin = interval.minAsLongArray();

		//new ImageJ();

		rdd.foreach( job ->
		{
			final int z = (int)job[ 1 ][0][0];
			final long[][] gridBlock = job[ 0 ];

			// The min coordinates of the block that this job renders (in pixels)
			final int n = gridBlock[ 0 ].length;
			final long[] blockOffset = new long[ n ];
			Arrays.setAll( blockOffset, d -> gridBlock[ 0 ][ d ] + bbMin[ d ] );

			// The size of the block that this job renders (in pixels)
			final long[] blockSize = gridBlock[ 1 ];

			// The offset of this block for saving
			final long[] gridOffset = gridBlock[2];

			final N5Reader n5 = new N5Factory().openReader( StorageFormat.N5, n5Path );
			final N5Writer zarrWriter = new N5Factory().openWriter( StorageFormat.ZARR, zarrPath );

			final String datasetName = datasetNames[ z ];
			final String transformDatasetName = transformDatasetNames[ z ];

			System.out.println( new Date( System.currentTimeMillis() ) + ": z=" + z + " >>> " + transformDatasetName + " block: "  );

			final List<RealTransform> realTransforms = Collections.singletonList(Transform.loadScaledTransform(n5, group + "/" + transformDatasetName));

			final RandomAccessibleInterval<UnsignedByteType> stack = Transform.createTransformedStackUnsignedByteType(
					options.getN5Path(),
					Collections.singletonList(datasetName),
					showScaleIndex,
					realTransforms,
					getInterval(boundsMin, boundsMax, showScale) );

			final RandomAccessibleInterval<UnsignedByteType> view2d = Views.hyperSlice( stack, 2, 0 );

			final Interval block = Intervals.translate( new FinalInterval( blockSize ), blockOffset );
	
			final RandomAccessibleInterval source = Views.zeroMin( Views.interval( view2d, block ) );

			//ImageJFunctions.show( source );
			//final RandomAccessibleInterval sourceGridBlock = Views.offsetInterval(source, blockOffset, blockSize);

			N5Utils.saveBlock( source, zarrWriter, datasetName( datasetName ), gridOffset );

			/*
			// Potential speedup with local multithreading
			final ExecutorService service = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
			final RandomAccessibleInterval<UnsignedByteType> slice = Views.hyperSlice( stack, 2, z );
			final RandomAccessibleInterval<UnsignedByteType> copy = Views.translate( new ArrayImgFactory<>( new UnsignedByteType() ).create( slice.dimensionsAsLongArray() ), slice.minAsLongArray() );
			Util.copy(slice, copy, service, false);
			final ImagePlus imp = ImageJFunctions.wrap( copy, "z=" + z );
			service.shutdown();
			*/

			/*
			final String title = "z=" + z;
			final ImagePlus imp = ImageJFunctions.wrap(Views.hyperSlice(stack, 2, 0), title);
			new FileSaver(imp).saveAsTiff(new File(options.saveDir(), title + ".tif").getAbsolutePath());
			imp.close();
			*/

			zarrWriter.close();
			n5.close();

			//System.out.println( new Date( System.currentTimeMillis() ) + ": SAVED z=" + z + " >>> " + transformDatasetName );
		});

		sparkContext.close();

		System.out.println( "took " + (( System.currentTimeMillis() - t )/1000) + " secs.");
	}

	public static String datasetName( final String dataset )
	{
		return dataset.substring( 1, dataset.length() ).replaceAll( "/", "-" ) + ".zarr";
	}

	public static Interval getInterval( final double[] boundsMin, final double[] boundsMax, final double showScale )
	{
		return new FinalInterval(
				Grid.floorScaled(boundsMin, showScale),
				Grid.ceilScaled(boundsMax, showScale));
	}
}
