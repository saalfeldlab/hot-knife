package org.janelia.saalfeldlab.hotknife;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.DownsampleHelper;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.hotknife.util.RawStack;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

public class SparkMaskedCLAHEMultiSEMBatch
{

    public static class Options extends AbstractOptions implements Serializable {

        @Option(name = "--n5PathInput",
                required = true,
                usage = "Input N5 path, e.g. gs://janelia-spark-test/hess_wafers_60_61_export")
        private String n5PathInput = null;

        @Option(name = "--raw",
                required = true,
                usage = "Raw names for dataset(s), repeat for multiple datasets e.g. --raw w61_s079_r00 --raw w61_s080_r00 ...")
        private List<String> rawNameList = new ArrayList<>();

        @Option(name = "--blockFactorXY",
                usage = "how much bigger the compute blocks in XY are than the blocks saved on disc")
        private int blockFactorXY = 8;

        @Option(name = "--blockFactorZ",
                usage = "how much bigger the compute blocks in Z are than the blocks saved on disc")
        private int blockFactorZ = 1;

        @Option(name = "--downsample",
                usage = "Downsample output volume by 2 in XY and 1 in Z")
        private boolean downsample = false;

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

        public List<RawStack> buildRawStackList() {
            return rawNameList.stream().map(RawStack::new).collect(Collectors.toList());
        }
    }

    public static void main(final String... args) throws Exception {

        final Options batchOptions = new Options(args);
        if (! batchOptions.parsedSuccessfully) {
            throw new IllegalArgumentException("Options were not parsed successfully");
        }

        final N5Reader n5Input = N5Util.createN5Reader(batchOptions.n5PathInput);
        final List<RawStack> rawStackList = batchOptions.buildRawStackList();
        for (final RawStack rawStack : rawStackList) {
            SparkMaskedCLAHEMultiSEM.validateDatasets(n5Input, rawStack);
        }

        final SparkConf conf = new SparkConf().setAppName("SparkMaskedCLAHEMultiSEMBatch");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);
        sparkContext.setLogLevel("ERROR");

        for (final RawStack rawStack : rawStackList) {

            final long start = System.currentTimeMillis();
            logMessage("main: start processing " + rawStack.getRawStack());

            final SparkMaskedCLAHEMultiSEM.Options claheOptions =
                    new SparkMaskedCLAHEMultiSEM.Options(batchOptions.n5PathInput,
                                                         rawStack,
                                                         batchOptions.blockFactorXY,
                                                         batchOptions.blockFactorZ,
                                                         batchOptions.overwrite);
            SparkMaskedCLAHEMultiSEM.process(sparkContext, claheOptions);

            final long elapsedMillis = System.currentTimeMillis() - start;
            logMessage("main: processed " + rawStack.getRawStack() + " in " + (elapsedMillis / 60000) + " minutes");

            if (batchOptions.downsample) {
                new DownsampleHelper(batchOptions.n5PathInput, claheOptions.getN5DatasetOutput()).run(sparkContext);
            }

        }

        sparkContext.close();
    }

    private static void logMessage(final String message) {
        Util.logMessage(SparkMaskedCLAHEMultiSEMBatch.class.getName(), message);
    }
}
