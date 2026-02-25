package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.hotknife.util.RawStack;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import static org.janelia.saalfeldlab.hotknife.util.Util.logMessage;

public class SparkComputeCostMultiSemBatch
{

    public static class BatchOptions extends AbstractOptions implements Serializable {

        @Option(name = "--n5PathInput",
                required = true,
                usage = "Input N5 path, e.g. gs://janelia-spark-test/hess_wafers_60_61_export")
        private String n5PathInput = null;

        @Option(name = "--raw",
                required = true,
                usage = "Raw names for dataset(s), repeat for multiple datasets e.g. --raw w61_s079_r00 --raw w61_s080_r00 ...")
        private List<String> rawNameList = new ArrayList<>();

        public BatchOptions(final String[] args) {
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

        final BatchOptions batchOptions = new BatchOptions(args);
        if (! batchOptions.parsedSuccessfully) {
            throw new IllegalArgumentException("Options were not parsed successfully");
        }

        final List<RawStack> rawStacks = batchOptions.buildRawStackList();
        final List<String[]> listOfArgsForEachRawStack = buildListOfArgsForEachRawStack(batchOptions.n5PathInput,
                                                                                        rawStacks);
        final SparkConf conf = new SparkConf().setAppName("SparkComputeCostMultiSemBatch");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);


        for (int i = 0; i < rawStacks.size(); i++) {

            final RawStack rawStack = rawStacks.get(i);
            final String[] rawStackArgs = listOfArgsForEachRawStack.get(i);

            logMessage(SparkComputeCostMultiSem.class.getName(),
                       "process " + rawStack.getRawStack() + " with args " + Arrays.toString(rawStackArgs));

            final SparkComputeCostMultiSem.Options stackOptions = new SparkComputeCostMultiSem.Options(rawStackArgs);

            if (stackOptions.parsedSuccessfully) {
                SparkComputeCostMultiSem.computeCostAndSurfaceFit(stackOptions, sparkContext);
            } else {
                logMessage(SparkComputeCostMultiSem.class.getName(),
                           "failed to parse args");
            }

        }

        sparkContext.close();
    }

    private static List<String[]> buildListOfArgsForEachRawStack(final String n5PathInput,
                                                                 final List<RawStack> rawStackList)
            throws IOException {

        final List<String[]> listOfArgsForEachRawStack = new ArrayList<>();

        try (final N5Reader n5Input = N5Util.createN5Reader(n5PathInput)) {

            for (final RawStack rawStack : rawStackList) {

                final String normLayerDatasetS0 = rawStack.getNormLayerDataset() + "/s0";
                Util.checkDatasetExistence(n5Input, normLayerDatasetS0, true);

                final String maskDatasetS0 = rawStack.getIC2DDataset() + "___mask/s0";
                Util.checkDatasetExistence(n5Input, maskDatasetS0, true);

                // skip check that cost dataset does not exist because this might be a re-run for heightfields

                final String hfDataset = rawStack.getHeightfieldsDataset();
                Util.checkDatasetExistence(n5Input, hfDataset, false);

                final String[] args = {
                        "--inputN5Path", n5PathInput,
                        "--inputN5Group", normLayerDatasetS0,
                        "--outputN5Path", n5PathInput,
                        "--costN5Group", rawStack.getCostDataset(),
                        "--maskN5Group", maskDatasetS0,
                        "--firstStepScaleNumber", "1",
                        "--costSteps", "2,2,1",
                        "--costSteps", "2,2,1",
                        "--costSteps", "2,2,1",
                        "--costSteps", "2,2,1",
                        "--costSteps", "2,2,1",
                        "--costSteps", "2,2,1",
                        "--costSteps", "2,2,1",
                        "--costSteps", "2,2,1",
                        "--costSteps", "2,2,1",
                        "--topLayerCost", "105",
                        "--bottomLayerCost", "250",
                        "--surfaceN5Output", rawStack.getHeightfieldsDataset(),
                        "--surfaceMinDistance", "15",
                        "--surfaceMaxDistance", "0",
                        "--surfaceBlockSize", "1024,1024",
                        "--surfaceFirstScale", "8",
                        "--surfaceLastScale", "1",
                        "--surfaceInitMaxDeltaZ", "0.1",
                        "--surfaceMaxDeltaZ", "0.1",
                        "--finalMaxDeltaZ", "0.2",
                        "--median",
                        "--smoothCost"
                };

                listOfArgsForEachRawStack.add(args);
            }
        }
        return listOfArgsForEachRawStack;
    }
}
