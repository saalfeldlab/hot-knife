package org.janelia.saalfeldlab.hotknife.util;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;

import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;

/**
 * Validated information for volume flattening.
 */
public class FlatteningInfo
        implements Serializable {

    private final N5PathAndDataset rawPathAndDataset;
    private final int[] rawBlockSize;
    private final DataType rawDataType;

    private final N5Path fieldPath;
    private final String minFieldDataset;
    private final String maxFieldDataset;
    private final double[] factors;
    private final double min;
    private final double max;
    private final long[] dimensions;
    private final boolean isMultiSEMData;
    private final int padding;

    private final N5PathAndDataset flatPathAndDataset;
    private final int[] flatBlockSize;

    public FlatteningInfo(final N5PathAndDataset rawPathAndDataset,
                          final N5PathAndDataset fieldPathAndDataset,
                          final boolean isMultiSEMData,
                          final int padding,
                          final N5PathAndDataset flatPathAndDataset,
                          final int[] flatBlockSize) throws IOException {

        this.rawPathAndDataset = rawPathAndDataset;
        final String rawDataset = rawPathAndDataset.getDataset();

        final N5Reader rawPathReader = rawPathAndDataset.openReader();
        this.rawBlockSize = Util.readRequiredAttribute(rawPathReader, rawDataset, DatasetAttributes.BLOCK_SIZE_KEY, int[].class);

        // read compression to make sure it exists, but don't store it because it is not Serializable
        Util.readRequiredAttribute(rawPathReader, rawDataset, DatasetAttributes.COMPRESSION_KEY, Compression.class);

        this.rawDataType = Util.readRequiredAttribute(rawPathReader, rawDataset, DatasetAttributes.DATA_TYPE_KEY, DataType.class);

        this.fieldPath = fieldPathAndDataset.getN5Path();
        final String fieldDataset = fieldPathAndDataset.getDataset();
        this.minFieldDataset = fieldDataset + "/min";
        this.maxFieldDataset = fieldDataset + "/max";

        final N5Reader fieldPathReader = fieldPath.openReader();
        final Double minAvg = Util.readRequiredAttribute(fieldPathReader, minFieldDataset, AVG_KEY, Double.class);
        final Double maxAvg = Util.readRequiredAttribute(fieldPathReader, maxFieldDataset, AVG_KEY, Double.class);

        this.factors = Util.readRequiredAttribute(fieldPathReader, fieldDataset, FACTORS_KEY, double[].class);

        this.min = (minAvg + 0.5) * factors[2] - 0.5;
        this.max = (maxAvg + 0.5) * factors[2] - 0.5;

        if (this.min >= this.max) {
            throw new IllegalStateException(
                    "heightfield volume has negative dimension because scaled min " + min + " >= scaled max " + max +
                    ", min " + AVG_KEY + " " + minAvg + " and " + FACTORS_KEY + " " + Arrays.toString(factors) +
                    " read from " + Util.getAttributesJsonPath(fieldPath.getPath(), minFieldDataset) +
                    ", max " + AVG_KEY + " " + maxAvg + " and " + FACTORS_KEY + " " + Arrays.toString(factors) +
                    " read from " + Util.getAttributesJsonPath(fieldPath.getPath(), maxFieldDataset));
        }

        final long[] rawDimensions = Util.readRequiredAttribute(rawPathReader, rawDataset, "dimensions", long[].class);
        if (isMultiSEMData) {
            this.dimensions = new long[]{
                    rawDimensions[0],
                    rawDimensions[1],
                    Math.round(this.max + padding) - Math.round(this.min - padding)
            };
        } else {
            this.dimensions = new long[]{
                    rawDimensions[0],
                    rawDimensions[2],
                    Math.round(this.max + padding) - Math.round(this.min - padding)
            };
        }

        this.isMultiSEMData = isMultiSEMData;
        this.padding = padding;

        this.flatPathAndDataset = flatPathAndDataset;

        final N5Reader flatPathReader = flatPathAndDataset.openReader();
        Util.checkDatasetExistence(flatPathReader, flatPathAndDataset.getDataset(), false);

        this.flatBlockSize = flatBlockSize;

        System.out.println("FlatteningInfo: constructed and validated info for " + rawPathAndDataset);
    }

    public N5PathAndDataset getRawPathAndDataset() {
        return rawPathAndDataset;
    }

    public int[] getRawBlockSize() {
        return rawBlockSize;
    }

    public Compression getCompression()
            throws IOException {
        final N5Reader rawPathReader = rawPathAndDataset.openReader();
        final String rawDataset = rawPathAndDataset.getDataset();
        return Util.readRequiredAttribute(rawPathReader,
                                          rawDataset,
                                          DatasetAttributes.COMPRESSION_KEY,
                                          Compression.class);
    }

    public DataType getRawDataType() {
        return rawDataType;
    }

    public N5Path getFieldPath() {
        return fieldPath;
    }

    public String getMinFieldDataset() {
        return minFieldDataset;
    }

    public String getMaxFieldDataset() {
        return maxFieldDataset;
    }

    public double[] getFactors() {
        return factors;
    }

    public double getMin() {
        return min;
    }

    public int getMinWithPadding() {
        return (int) Math.round(min - padding);
    }

    public double getMax() {
        return max;
    }

    public int getMaxWithPadding() {
        return (int) Math.round(max + padding);
    }

    public long[] getDimensions() {
        return dimensions;
    }

    public boolean isMultiSEMData() {
        return isMultiSEMData;
    }

    public int getPadding() {
        return padding;
    }

    public N5PathAndDataset getFlatPathAndDataset() {
        return flatPathAndDataset;
    }

    public int[] getFlatBlockSize() {
        return flatBlockSize;
    }

    @Override
    public String toString() {
        return "FlatteningInfo{" +
               "rawPathAndDataset=" + rawPathAndDataset +
               ", rawBlockSize=" + Arrays.toString(rawBlockSize) +
               ", rawDataType=" + rawDataType +
               ", fieldPath=" + fieldPath +
               ", minFieldDataset='" + minFieldDataset + '\'' +
               ", maxFieldDataset='" + maxFieldDataset + '\'' +
               ", factors=" + Arrays.toString(factors) +
               ", min=" + min +
               ", max=" + max +
               ", dimensions=" + Arrays.toString(dimensions) +
               ", isMultiSEMData=" + isMultiSEMData +
               ", padding=" + padding +
               ", flatPathAndDataset=" + flatPathAndDataset +
               ", flatBlockSize=" + Arrays.toString(flatBlockSize) +
               '}';
    }

    public static final String AVG_KEY = "avg";
    public static final String FACTORS_KEY = "downsamplingFactors";
}
