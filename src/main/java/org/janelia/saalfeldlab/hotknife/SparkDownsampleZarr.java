package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.janelia.saalfeldlab.n5.spark.util.CmdUtils;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrWriter;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

public class SparkDownsampleZarr
{


	public static void main( final String... args ) throws IOException, CmdLineException
	{
		final Arguments parsedArgs = new Arguments( args );

		try ( final JavaSparkContext sparkContext = new JavaSparkContext( new SparkConf()
				.setAppName( "SparkDownsampleZarr" )
				.set( "spark.serializer", "org.apache.spark.serializer.KryoSerializer" )
			) )
		{
			final N5WriterSupplier n5Supplier = () -> new N5ZarrWriter(parsedArgs.getZarrPath() );

			final String[] outputDatasetPath = parsedArgs.getOutputDatasetPath();
			final int[][] downsamplingFactors = parsedArgs.getDownsamplingFactors();

			if ( outputDatasetPath.length != downsamplingFactors.length )
				throw new IllegalArgumentException( "Number of output datasets does not match downsampling factors!" );

			N5DownsamplerSpark.downsample(
					sparkContext,
					n5Supplier,
					parsedArgs.getInputDatasetPath(),
					outputDatasetPath[ 0 ],
					downsamplingFactors[ 0 ],
					parsedArgs.getBlockSize()
				);

			for ( int i = 1; i < downsamplingFactors.length; i++ )
			{
				N5DownsamplerSpark.downsample(
						sparkContext,
						n5Supplier,
						outputDatasetPath[ i - 1 ],
						outputDatasetPath[ i ],
						downsamplingFactors[ i ],
						parsedArgs.getBlockSize()
					);
			}
		}
		System.out.println( "Done" );
	}

	@SuppressWarnings("unused")
	private static class Arguments implements Serializable
	{
		@Option(name = "-n", aliases = { "--zarrPath" }, required = true,
				usage = "Path to a Zarr container.")
		private String zarrPath;

		@Option(name = "-i", aliases = { "--inputDatasetPath" }, required = true,
				usage = "Path to the input dataset within the container (e.g. data/group/s0).")
		private String inputDatasetPath;

		@Option(name = "-o", aliases = { "--outputDatasetPath" }, required = true, handler = StringArrayOptionHandler.class,
				usage = "Path(s) to the output dataset to be created (e.g. data/group/s1).")
		private String[] outputDatasetPath;

		@Option(name = "-f", aliases = { "--factors" }, required = true, handler = StringArrayOptionHandler.class,
				usage = "Downsampling factors. If using multiple, each factor builds on the last.")
		private String[] downsamplingFactors;

		@Option(name = "-b", aliases = { "--blockSize" },
				usage = "Block size for the output dataset (by default same as for input dataset).")
		private String blockSize;

		public Arguments( final String... args ) throws IllegalArgumentException
		{
			final CmdLineParser parser = new CmdLineParser( this );
			try
			{
				parser.parseArgument( args );
			}
			catch ( final CmdLineException e )
			{
				System.err.println( e.getMessage() );
				parser.printUsage( System.err );
				System.exit( 1 );
			}
		}

		public String getZarrPath() { return zarrPath; }
		public String getInputDatasetPath() { return inputDatasetPath; }
		public String[] getOutputDatasetPath() { return outputDatasetPath; }
		public int[][] getDownsamplingFactors() { return CmdUtils.parseMultipleIntArrays( downsamplingFactors ); }
		public int[] getBlockSize() { return CmdUtils.parseIntArray( blockSize ); }
	}
}
