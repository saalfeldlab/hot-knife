package org.janelia.saalfeldlab.hotknife.util;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.AbstractOptions;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/**
 * Helper for downsampling a dataset.
 */
public class DownsampleHelper
        implements Serializable {

    public static int[] DEFAULT_DOWNSAMPLE_FACTORS = {2, 2, 1};
    public static int DEFAULT_REQUIRED_S_LEVEL = 9;
    public static N5RetryUtil.RetryParameters DEFAULT_RETRY_PARAMETERS = new N5RetryUtil.RetryParameters();

    private final String basePathOrStorageUrl;
    private final String sZeroDatasetPath;
    private final int[] downsampleFactors;
    private final int requiredSLevel;
    private final N5RetryUtil.RetryParameters retryParameters;

    public DownsampleHelper(final String basePathOrStorageUrl,
                            final String sZeroDatasetPath)
            throws IOException {
        this(basePathOrStorageUrl, sZeroDatasetPath, DEFAULT_DOWNSAMPLE_FACTORS);
    }

    public DownsampleHelper(final String basePathOrStorageUrl,
                            final String sZeroDatasetPath,
                            final int[] downsampleFactors)
            throws IOException {
        this(basePathOrStorageUrl, sZeroDatasetPath, downsampleFactors, DEFAULT_REQUIRED_S_LEVEL);
    }

    public DownsampleHelper(final String basePathOrStorageUrl,
                            final String sZeroDatasetPath,
                            final int[] downsampleFactors,
                            final int requiredSLevel)
            throws IOException {
        this(basePathOrStorageUrl, sZeroDatasetPath, downsampleFactors, requiredSLevel, DEFAULT_RETRY_PARAMETERS);
    }

    /**
     * @param  basePathOrStorageUrl  base path or storage URL
     *                                 e.g. gs://janelia-spark-test/hess_wafers_60_61_export or
     *                                      /nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.n5
     *
     * @param  sZeroDatasetPath      full-resolution dataset path
     *                                 e.g. /flat/w61_serial_070_to_079/w61_s076_r00/raw_clahe/s0
     *
     * @param  downsampleFactors     per-dimension factors applied at each downsampling step (e.g. 2,2,1).
     *
     * @param  requiredSLevel        the minimum s-level that must be produced (e.g. 9).
     *
     * @param  retryParameters       parameters controlling retry behavior on failure
     *                               (specify as null if you do not want retries performed).
     *
     * @throws IOException
     *   if the sZeroDatasetPath does not end with '/s0'.
     */
    public DownsampleHelper(final String basePathOrStorageUrl,
                            final String sZeroDatasetPath,
                            final int[] downsampleFactors,
                            final int requiredSLevel,
                            final N5RetryUtil.RetryParameters retryParameters)
            throws IOException {

        this.basePathOrStorageUrl = basePathOrStorageUrl;

        this.sZeroDatasetPath = sZeroDatasetPath;
        if (! sZeroDatasetPath.endsWith("/s0")) {
            throw new IOException("sZeroDatasetPath must end with '/s0'");
        }

        this.downsampleFactors = downsampleFactors;
        this.requiredSLevel = requiredSLevel;
        this.retryParameters = retryParameters;
    }

    /**
     * Downsamples the N5 dataset iteratively, creating s1, s2, ... and writing neuroglancer attributes.
     * Downsampling will stop when the sN result contains a single block is greater than or equal to the requiredSLevel.
     *
     * @param  sparkContext  the Spark context used for distributed processing.
     *
     * @throws IOException
     *   if an N5 read or write operation fails.
     */
    public void run(final JavaSparkContext sparkContext)
            throws IOException {

        logMessage("run: entry, basePathOrStorageUrl=" + basePathOrStorageUrl +
                   ", sZeroDatasetPath=" + sZeroDatasetPath +
                   ", downsampleFactors=" + Arrays.toString(downsampleFactors) +
                   ", requiredSLevel=" + requiredSLevel +
                   ", retryParameters=" + retryParameters);

        final N5WriterSupplier n5Supplier = () -> N5Util.createN5Writer(basePathOrStorageUrl);

        final N5Writer n5 = n5Supplier.get();
        final DatasetAttributes fullScaleAttributes = n5.getDatasetAttributes(sZeroDatasetPath);
        final long[] dimensions = fullScaleAttributes.getDimensions();
        final int numberOfDimensions = dimensions.length;
        final int[] outputBlockSize = fullScaleAttributes.getBlockSize();
        final String outputGroupPath = sZeroDatasetPath.substring(0, sZeroDatasetPath.lastIndexOf('/'));

        int numberOfDownsampledDatasets = 0;
        long downsampledBlockCount = 2;
        for (int scale = 1; (downsampledBlockCount > 1) || scale <= requiredSLevel; scale++) {

            final String fromDataset = scale == 1 ? sZeroDatasetPath : outputGroupPath + "/s" + (scale - 1);
            final String toDataset = outputGroupPath + "/s" + scale;

            final int[] scaleFactors = new int[numberOfDimensions];
            for (int d = 0; d < numberOfDimensions; d++) {
                scaleFactors[d] = (int) Math.round(Math.pow(downsampleFactors[d], scale));
            }

            long blockCount = 1;
            final long[] downsampledDimensions = new long[numberOfDimensions];
            for (int d = 0; d < numberOfDimensions; d++) {
                downsampledDimensions[d] = dimensions[d] / scaleFactors[d];
                final long blocksInDim = (downsampledDimensions[d] + outputBlockSize[d] - 1) / outputBlockSize[d];
                blockCount *= blocksInDim;
            }
            downsampledBlockCount = blockCount;

            if (n5.datasetExists(toDataset)) {

                final DatasetAttributes toDatasetAttributes = n5.getDatasetAttributes(toDataset);
                final long[] toDatasetDimensions = toDatasetAttributes.getDimensions();
                for (int d = 0; d < numberOfDimensions; d++) {
                    if (toDatasetDimensions[d] != downsampledDimensions[d]) {
                        throw new IOException(
                                "existing dataset " + toDataset + " has " + toDatasetDimensions[d] +
                                " pixels in axis " + d + " instead of " + downsampledDimensions[d] +
                                " pixels (based on downsampleFactor " + downsampleFactors[d] + ")");
                    }
                }

                logMessage("run: skipping s" + scale + " because " + toDataset + " already exists");
                numberOfDownsampledDatasets++;

                continue;
            }

            final String operationDescription = "downsample " + fromDataset + " to " + toDataset;
            logMessage("run: " + operationDescription + " with " + downsampledBlockCount + " downsampled block(s)");

            if (retryParameters == null) {
                N5DownsamplerSpark.downsample(sparkContext,
                                              n5Supplier,
                                              fromDataset,
                                              toDataset,
                                              downsampleFactors,
                                              null);
            } else {

                try {
                    final RetryStats retryStats = N5RetryUtil.executeWithRetryVoid(
                            () -> N5DownsamplerSpark.downsample(sparkContext,
                                                                n5Supplier,
                                                                fromDataset,
                                                                toDataset,
                                                                downsampleFactors),
                            retryParameters,
                            operationDescription);

                    logMessage("run: " + retryStats);

                } catch (Exception e) {
                    throw new IOException(e);
                }

            }

            numberOfDownsampledDatasets++;
        }

        final NeuroglancerAttributes ng = new NeuroglancerAttributes(numberOfDownsampledDatasets, downsampleFactors);
        ng.write(n5Supplier.get(), Paths.get(sZeroDatasetPath));

        logMessage("run: exit, generated " + numberOfDownsampledDatasets + " downsampled datasets for " + outputGroupPath);
    }

    public static class Options extends AbstractOptions
            implements Serializable {

        @Option(name = "--basePathOrStorageUrl",
                required = true,
                usage = "Base path or storage URL, e.g. gs://janelia-spark-test/hess_wafers_60_61_export or " +
                        "/nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.n5")
        private String basePathOrStorageUrl = null;

        @Option(name = "--fullResolutionDataset",
                required = true,
                usage = "Full-resolution dataset path, e.g. /flat/w61_serial_070_to_079/w61_s076_r00/raw_clahe/s0")
        private String fullResolutionDataset = null;

        @Option(name = "--factors",
                usage = "Scale pyramid with given factors, e.g. 2,2,1")
        private String factors;

        public Options(final String[] args)
                throws CmdLineException {
            final CmdLineParser parser = new CmdLineParser(this);
            parser.parseArgument(args);
        }

        public int[] getDownsampleFactors() {
            return parseCSIntArray(factors);
        }
    }

    public static void main(final String[] args) throws Exception {

        logMessage("main: entry, args=" + Arrays.toString(args));

        final Options options = new Options(args);
        final SparkConf conf = new SparkConf().setAppName("DownsampleHelper");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);

        final DownsampleHelper helper = new DownsampleHelper(options.basePathOrStorageUrl,
                                                             options.fullResolutionDataset,
                                                             options.getDownsampleFactors());
        helper.run(sparkContext);
        sparkContext.close();
    }

    private static void logMessage(final String message) {
        org.janelia.saalfeldlab.hotknife.util.Util.logMessage(DownsampleHelper.class.getName(),
                                                              message);
    }

}
