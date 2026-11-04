package org.janelia.saalfeldlab.hotknife.util;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for building standardized stack and dataset names.
 */
public class RawStack
        implements Serializable {

    public static final Pattern RAW_STACK_PATTERN = Pattern.compile("^w(\\d+)_s(\\d+)_r(\\d+)$");

    public static String DEFAULT_IC3D_SUFFIX = "_gc_bc_par_cc_aso_3d_s3i";
    public static String DEFAULT_CLAHE_SUFFIX = "_clahe";
    public static String DEFAULT_COST_HEIGHTFIELDS_AND_FLAT_VERSION = "v4";

    private final String rawStack;

    private final String project;
    private final String ic3dSuffix;
    private final String claheSuffix;
    private final String costVersion;
    private final String heightfieldsVersion;
    private final String flatVersion;

    public RawStack(final String rawStack) {
        this(rawStack,
             DEFAULT_IC3D_SUFFIX,
             DEFAULT_CLAHE_SUFFIX,
             DEFAULT_COST_HEIGHTFIELDS_AND_FLAT_VERSION,
             DEFAULT_COST_HEIGHTFIELDS_AND_FLAT_VERSION,
             DEFAULT_COST_HEIGHTFIELDS_AND_FLAT_VERSION);
    }

    public RawStack(final String rawStack,
                    final String ic3dSuffix,
                    final String claheSuffix,
                    final String costVersion,
                    final String heightfieldsVersion,
                    final String flatVersion) {
        this.rawStack = rawStack;
        this.project = buildProjectName(rawStack);
        this.ic3dSuffix = ic3dSuffix;
        this.claheSuffix = claheSuffix;
        this.costVersion = costVersion;
        this.heightfieldsVersion = heightfieldsVersion;
        this.flatVersion = flatVersion;
    }

    public String getRawStack() {
        return rawStack;
    }

    /** @return the render project name (e.g. w61_serial_070_to_079 for rawStack w61_s076_r00) */
    public String getProject() {
        return project;
    }

    /** @return the 3D intensity corrected stack name (e.g. w61_s076_r00_gc_bc_par_cc_aso_3d_s3i) */
    public String getIC3DStack() {
        return rawStack + ic3dSuffix;
    }

    /** @return the 3D intensity corrected dataset (e.g. /render/w61_serial_070_to_079/w61_s076_r00_gc_bc_par_cc_aso_3d_s3i___pixel/s0) */
    public String getIC3DPixelDatasetS0() {
        return "/render/" + project + "/" + getIC3DStack() + "___pixel/s0";
    }

    /** @return the 3D intensity corrected dataset (e.g. /render/w61_serial_070_to_079/w61_s076_r00_gc_bc_par_cc_aso_3d_s3i___mask/s0) */
    public String getIC3DMaskDatasetS0() {
        return "/render/" + project + "/" + getIC3DStack() + "___mask/s0";
    }

    /** @return the cost dataset (e.g. /cost_v4/w61_serial_070_to_079/w61_s076_r00_gc_bc_par_cc_aso_3d_s3i) */
    public String getCostDataset() {
        return "/cost_" + costVersion + "/" + project + "/" + getIC3DStack();
    }

    /** @return the heightfields dataset (e.g. /heightfields_v4/w61_serial_070_to_079/w61_s076_r00_gc_bc_par_cc_aso_3d_s3i) */
    public String getHeightfieldsDataset() {
        return "/heightfields_" + heightfieldsVersion + "/" + project + "/" + getIC3DStack();
    }

    /** @return the flat dataset (e.g. /flat_v4/w61_serial_070_to_079/w61_s076_r00) */
    public String getFlatDataset() {
        return "/flat_" + flatVersion + "/" + project + "/" + rawStack;
    }

    /** @return the flat raw dataset (e.g. /flat_v4/w61_serial_070_to_079/w61_s076_r00/raw) */
    public String getFlatRawDataset() {
        return getFlatDataset() + "/raw";
    }

    /** @return the flat raw s0 dataset (e.g. /flat_v4/w61_serial_070_to_079/w61_s076_r00/raw/s0) */
    public String getFlatRawS0Dataset() {
        return getFlatRawDataset() + "/s0";
    }

    /** @return the flat raw CLAHE dataset (e.g. /flat_v4/w61_serial_070_to_079/w61_s076_r00/raw_clahe) */
    public String getFlatRawCLAHEDataset() {
        return getFlatRawDataset() + claheSuffix;
    }

    /** @return the flat raw CLAHE s0 dataset (e.g. /flat_v4/w61_serial_070_to_079/w61_s076_r00/raw_clahe/s0) */
    public String getFlatRawCLAHES0Dataset() {
        return getFlatRawCLAHEDataset() + "/s0";
    }

    /** @return the flat raw CLAHE top or bottom dataset (e.g. /flat_v4/w61_serial_070_to_079/w61_s076_r00/raw_clahe_top) */
    public String getFlatRawClaheEdgeDataset(final boolean isTop)
            throws IllegalArgumentException {
        final String edgeSuffix = isTop ? "_top" : "_bot";
        return getFlatRawCLAHEDataset() + edgeSuffix;
    }

    public static String buildProjectName(final String rawStackName)
            throws IllegalArgumentException {

        final Matcher m = RAW_STACK_PATTERN.matcher(rawStackName);
        if (! m.matches()) {
            throw new IllegalArgumentException("invalid raw stack name '" + rawStackName + "'");
        }

        final int wafer = Integer.parseInt(m.group(1));   // e.g. 61
        final int serial = Integer.parseInt(m.group(2));  // e.g. 79, 80

        final int start = (serial / 10) * 10;  // 79 -> 70, 80 -> 80
        final int end   = start + 9;           // 70 -> 79, 80 -> 89

        return String.format("w%d_serial_%03d_to_%03d", wafer, start, end);
    }
}
