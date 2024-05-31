package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaFutureAction;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

public class SparkMaskedCLAHEMultiSEM
{
	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput",
				required = true,
				usage = "Input N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5")
		private String n5PathInput = null;

		@Option(name = "--n5DatasetInput",
				required = true,
				usage = "Input N5 dataset, e.g. /flat/s075_m119/top4/face")
		private String n5DatasetInput = null;

		@Option(name = "--n5DatasetOutput",
				required = true,
				usage = "Output N5 dataset, e.g. /flat/s075_m119/top4/face_local")
		private String n5DatasetOutput = null;

		@Option(name = "--n5FieldMax",
				required = true,
				usage = "Input N5 dataset, e.g. /heightfields/slab-01/max")
		private String n5FieldMax = null;

		@Option(name = "--blockFactorXY",
				usage = "how much bigger the compute blocks in XY are than the blocks saved on disc")
		private int blockFactorXY = 8;

		@Option(name = "--invert",
				usage = "Invert before saving to N5, e.g. for MultiSEM")
		private boolean invert = false;

		@Option(name = "--overwrite",
				usage = "Overwrite existing n5 datasets without asking")
		private boolean overwrite = false;

		public Options(final String[] args) {
			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				parsedSuccessfully = true;
			} catch (final Exception e) {
				e.printStackTrace(System.err);
				parser.printUsage(System.err);
			}
		}
	}

	public static void process(
			final JavaSparkContext sparkContext,
			final String n5PathInput,
			final String n5DatasetInput,
			final String n5DatasetOutput,
			final String n5FieldMax,
			final int blockFactorXY,
			final boolean overwrite ) throws IOException
	{
		final N5Reader n5Input = new N5FSReader(n5PathInput);

		final DatasetAttributes attributes = n5Input.getDatasetAttributes(n5DatasetInput);
		final int[] blockSize = attributes.getBlockSize();
		final long[] dimensions = attributes.getDimensions();
		final int[] gridBlockSize = new int[]{ blockSize[0] * blockFactorXY, blockSize[1] * blockFactorXY, blockSize[2] };

		final String factorsKey = "downsamplingFactors";
		final double[] maxFactors = Util.readRequiredAttribute(n5Input, n5FieldMax, factorsKey, double[].class);

		System.out.println("loaded " + factorsKey + " " + Arrays.toString(maxFactors) + " from " + n5FieldMax);

		final List<long[][]> grid = Grid.create(dimensions, gridBlockSize, blockSize);

		final N5Writer n5Output = new N5FSWriter(n5PathInput);

		if (n5Output.exists(n5DatasetOutput))
		{
			if ( overwrite )
			{
				n5Output.remove( n5DatasetOutput );
			}
			else
			{
				n5Input.close();
				n5Output.close();
				throw new IllegalArgumentException("Output data set exists: " + n5PathInput + n5DatasetOutput);
			}
		}

		n5Output.createDataset(n5DatasetOutput, dimensions, blockSize, DataType.UINT8, new GzipCompression());
		SparkPixelNormalizeN5.transferAttributes(n5Output, n5DatasetInput, n5DatasetOutput);

		n5Output.close();
		n5Input.close();

		final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid);

		pGrid.foreach(
				gridBlock ->
				{
					final N5Reader n5 = new N5FSReader(n5PathInput);
					final RandomAccessibleInterval<FloatType> maxField = N5Utils.open(n5, n5FieldMax);
					final RealRandomAccessible<DoubleType> maxFieldScaled = Transform.scaleAndShiftHeightFieldAndValues(maxField, maxFactors);

					final FinalInterval gridBlockInterval =
							Intervals.createMinSize(
									gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
									gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);

					final RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5Input, n5DatasetInput);

					/*
					final RandomAccessibleInterval<UnsignedByteType> filteredSource =
							normalizeContrast(source,  new UnsignedByteType(), normalizeMethod, scaleIndex, blockSize);

					N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
							  n5Output,
							  datasetNameOutput,
							  new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
							  gridBlock[2],
							  new UnsignedByteType());*/
				});

	}
	
	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final SparkMaskedCLAHEMultiSEM.Options options = new SparkMaskedCLAHEMultiSEM.Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		options.n5DatasetInput = options.n5DatasetInput.trim();
		options.n5DatasetOutput = options.n5DatasetOutput.trim();

		final SparkConf conf = new SparkConf().setAppName("SparkMaskedCLAHEMultiSEM");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");

		process( sparkContext, options.n5PathInput, options.n5DatasetInput, options.n5DatasetOutput, options.n5FieldMax, options.blockFactorXY, options.overwrite );
	}
}
