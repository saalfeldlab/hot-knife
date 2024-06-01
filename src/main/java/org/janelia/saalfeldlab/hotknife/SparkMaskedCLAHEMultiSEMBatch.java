package org.janelia.saalfeldlab.hotknife;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class SparkMaskedCLAHEMultiSEMBatch
{

	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput",
				required = true,
				usage = "Input N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5")
		private String n5PathInput = null;

		// /render/slab_000_to_009/s002_m395_align_no35_horiz_avgshd_ic___20240504_084955_norm-layer/s0,/render/slab_000_to_009/s002_m395_align_no35_horiz_avgshd_ic___20240504_084955_norm-layer-clahe/s0,/heightfields_fix/slab_000_to_009/s002_m395/max
		@Option(name = "--datasetCsv",
				required = true,
				usage = "File containing comma-separated input,max values")
		private String datasetCsv = null;

		@Option(name = "--blockFactorXY",
				usage = "how much bigger the compute blocks in XY are than the blocks saved on disc")
		private int blockFactorXY = 8;

		@Option(name = "--blockFactorZ",
				usage = "how much bigger the compute blocks in Z are than the blocks saved on disc")
		private int blockFactorZ = 1;

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

	public static void main(final String... args) throws Exception {

		final Options options = new Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final List<String[]> datasetValuesList = new ArrayList<>();
		final Path datasetCsvPath = Paths.get(options.datasetCsv);
		final List<String> datasetCsvLines = Files.readAllLines(datasetCsvPath);
		for (int i = 0; i < datasetCsvLines.size(); ++i) {
			final String line = datasetCsvLines.get(i);
			final String[] values = line.split(",");
			if (values.length != 3) {
				throw new IllegalArgumentException("Expected 3 values per line in " + datasetCsvPath +
												   " but line " + i + " has " + values.length + " values");
			}
			datasetValuesList.add(values);
		}

		final SparkConf conf = new SparkConf().setAppName("SparkMaskedCLAHEMultiSEMBatch");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");
		
		for (final String[] datasetValues : datasetValuesList) {
			final String n5DatasetInput = datasetValues[0];
			final String n5DatasetOutput = datasetValues[1];
			final String n5FieldMax = datasetValues[2];
			SparkMaskedCLAHEMultiSEM.process(sparkContext,
											 options.n5PathInput,
											 n5DatasetInput,
											 n5DatasetOutput,
											 n5FieldMax,
											 options.blockFactorXY,
											 options.blockFactorZ,
											 options.overwrite);
		}

		sparkContext.close();
	}
}
