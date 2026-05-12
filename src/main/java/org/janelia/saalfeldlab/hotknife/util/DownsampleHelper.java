package org.janelia.saalfeldlab.hotknife.util;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;

import static org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark.downsample;

/**
 * Helper for downsampling a dataset.
 */
public class DownsampleHelper
        implements Serializable {

    private final String rootPath;
    private final String sZeroDatasetPath;

    public DownsampleHelper(final String rootPath,
                            final String sZeroDatasetPath)
            throws IllegalArgumentException {

        this.rootPath = rootPath;
        this.sZeroDatasetPath = sZeroDatasetPath;

        if (! sZeroDatasetPath.endsWith("/s0")) {
            throw new IllegalArgumentException("sZeroDatasetPath must end with '/s0'");
        }

    }

    public void run(final JavaSparkContext sparkContext)
            throws IOException {

        final List<String> datasetPathList = new ArrayList<>();
        datasetPathList.add(sZeroDatasetPath);

        // /flat/w61_serial_070_to_079/w61_s076_r00/raw_clahe
        final String parentDatasetPath = sZeroDatasetPath.substring(0, sZeroDatasetPath.length() - 3);

        // /flat/w61_serial_070_to_079/w61_s076_r00/raw_clahe/s
        final String datasetWithSPrefix = parentDatasetPath + "/s";

        final int numberOfDownsampledDatasets = 9;
        for (int sLevel = 1; sLevel <= numberOfDownsampledDatasets; sLevel++) {
            datasetPathList.add(datasetWithSPrefix + sLevel);
        }

        final int[] downsampleFactors = new int[] { 2, 2, 1 };
        final N5WriterSupplier n5Supplier = () -> N5Util.createN5Writer(rootPath);

        for (int i = 1; i < datasetPathList.size(); i++) {

            final String fromDataset = datasetPathList.get(i - 1);
            final String toDataset = datasetPathList.get(i);

            logMessage("run: " + fromDataset + " to " + toDataset);

            downsample(sparkContext,
                       n5Supplier,
                       fromDataset,
                       toDataset,
                       downsampleFactors,
                       null);
        }

        final NeuroglancerAttributes ng = new NeuroglancerAttributes(numberOfDownsampledDatasets, downsampleFactors);
        ng.write(n5Supplier.get(), Paths.get(sZeroDatasetPath));
    }

    private static void logMessage(final String message) {
        org.janelia.saalfeldlab.hotknife.util.Util.logMessage(DownsampleHelper.class.getName(),
                                                              message);
    }

}
