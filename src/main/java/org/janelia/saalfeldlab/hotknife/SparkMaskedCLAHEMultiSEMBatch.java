package org.janelia.saalfeldlab.hotknife;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import static org.janelia.saalfeldlab.hotknife.SparkMaskedCLAHEMultiSEM.FACTORS_KEY;

public class SparkMaskedCLAHEMultiSEMBatch
{

	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput",
				required = true,
				usage = "Input N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5")
		private String n5PathInput = null;

		// /render/slab_000_to_009/s002_m395_align_no35_horiz_avgshd_ic___20240504_084955_norm-layer/s0,/render/slab_000_to_009/s002_m395_align_no35_horiz_avgshd_ic___20240504_084955_norm-layer-clahe/s0,/heightfields_fix/slab_000_to_009/s002_m395/max
		@Option(name = "--datasetCsv",
				usage = "File containing comma-separated input,output,max values")
		private String datasetCsv = null;

        @Option(name = "--raw",
                usage = "Raw names for input dataset(s) to read (repeat for multiple datasets, " +
                        "e.g. --raw w61_s079_r00 --raw w61_s080_r00 ...)")
        private List<String> rawList = new ArrayList<>();

        @Option(name = "--rawDatasetSuffix",
                usage = "Suffix to append to each raw name for input and output datasets")
        private String rawDatasetSuffix = "_gc_par_align_ic2d___norm-layer";

        @Option(name = "--maxDatasetRoot",
                usage = "Root name for all max datasets")
        private String maxDatasetRoot = "heightfields_v3";

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
        if (options.rawList.isEmpty()) {

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

        } else if (options.datasetCsv != null) {

            throw new IllegalArgumentException("must specify either --datasetCsv or --raw but not both");

        } else {

            for (int i = 0; i < options.rawList.size(); ++i) {
                final String rawName = options.rawList.get(i);             // w61_s079_r00
                final String project = Util.getRenderProjectName(rawName); // w61_serial_070_to_079

                // w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer
                final String projectAndStack = project + "/" + rawName + options.rawDatasetSuffix;

                // 0: /render/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s0
                // 1: /render/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer-clahe/s0
                // 2: /heightfields_v3/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max
                datasetValuesList.add(new String[] {
                        "/render/" + projectAndStack + "/s0",
                        "/render/" + projectAndStack + "-clahe/s0",
                        "/" + options.maxDatasetRoot + "/" + projectAndStack + "/s1/max",
                });
            }

        }

        // make sure all input data exists and all output datasets do not exist before starting processing
        final N5Reader n5Input = N5Util.createN5Reader(options.n5PathInput);
        for (final String[] datasetValues : datasetValuesList) {
            final String n5DatasetInput = datasetValues[0];
            final String n5DatasetOutput = datasetValues[1];
            final String n5FieldMax = datasetValues[2];

            Util.checkDatasetExistence(n5Input, n5DatasetInput, true);
            Util.checkDatasetExistence(n5Input, n5DatasetOutput, false);
            Util.checkDatasetExistence(n5Input, n5FieldMax, true);

            Util.readRequiredAttribute(n5Input, n5DatasetInput, "blockSize", int[].class);
            Util.readRequiredAttribute(n5Input, n5DatasetInput, "dimensions", long[].class);

            final String n5FieldMaxParent = n5FieldMax.substring(0, n5FieldMax.lastIndexOf('/'));
            Util.readRequiredAttribute(n5Input, n5FieldMaxParent, FACTORS_KEY, double[].class);

            System.out.println("SparkMaskedCLAHEMultiSEMBatch: verified datasets and max field factors for " + n5DatasetInput);
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
