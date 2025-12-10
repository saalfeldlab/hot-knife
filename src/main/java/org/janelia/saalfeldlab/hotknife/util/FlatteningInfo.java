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
    private final Compression rawCompression;
    private final DataType rawDataType;

    private final N5Path fieldPath;
    private final String minFieldDataset;
    private final String maxFieldDataset;
    private final double[] minFactors;
    private final double[] maxFactors;
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
        this.rawCompression = Util.readRequiredAttribute(rawPathReader, rawDataset, DatasetAttributes.COMPRESSION_KEY, Compression.class);
        this.rawDataType = Util.readRequiredAttribute(rawPathReader, rawDataset, DatasetAttributes.DATA_TYPE_KEY, DataType.class);

        this.fieldPath = fieldPathAndDataset.getN5Path();
        this.minFieldDataset = fieldPathAndDataset.getDataset() + "/min";
        this.maxFieldDataset = fieldPathAndDataset.getDataset() + "/max";

        final N5Reader fieldPathReader = fieldPath.openReader();
        final Double minAvg = Util.readRequiredAttribute(fieldPathReader, minFieldDataset, AVG_KEY, Double.class);
        final Double maxAvg = Util.readRequiredAttribute(fieldPathReader, maxFieldDataset, AVG_KEY, Double.class);

        this.minFactors = Util.readRequiredAttribute(fieldPathReader, minFieldDataset, FACTORS_KEY, double[].class);
        this.maxFactors = Util.readRequiredAttribute(fieldPathReader, maxFieldDataset, FACTORS_KEY, double[].class);

        this.min = (minAvg + 0.5) * minFactors[2] - 0.5;
        this.max = (maxAvg + 0.5) * maxFactors[2] - 0.5;

        if (this.min >= this.max) {
            throw new IllegalStateException(
                    "heightfield volume has negative dimension because scaled min " + min + " >= scaled max " + max +
                    ", min " + AVG_KEY + " " + minAvg + " and " + FACTORS_KEY + " " + Arrays.toString(minFactors) +
                    " read from " + Util.getAttributesJsonPath(fieldPath.getPath(), minFieldDataset) +
                    ", max " + AVG_KEY + " " + maxAvg + " and " + FACTORS_KEY + " " + Arrays.toString(maxFactors) +
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
        this.flatBlockSize = flatBlockSize;

        System.out.println("FlatteningInfo: constructed and validated info for " + rawPathAndDataset);
    }

    public N5PathAndDataset getRawPathAndDataset() {
        return rawPathAndDataset;
    }

    public int[] getRawBlockSize() {
        return rawBlockSize;
    }

    public Compression getRawCompression() {
        return rawCompression;
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

    public double[] getMinFactors() {
        return minFactors;
    }

    public double[] getMaxFactors() {
        return maxFactors;
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


    public static final String AVG_KEY = "avg";
    public static final String FACTORS_KEY = "downsamplingFactors";
}
