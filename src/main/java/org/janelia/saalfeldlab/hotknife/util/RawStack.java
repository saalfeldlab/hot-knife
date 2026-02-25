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

    public static String DEFAULT_IC2D_SUFFIX = "_gc_par_align_ic2d";
    public static String DEFAULT_NORM_LAYER_SUFFIX = DEFAULT_IC2D_SUFFIX + "___norm-layer-v2-mb";
    public static String DEFAULT_CLAHE_SUFFIX = DEFAULT_NORM_LAYER_SUFFIX + "_clahe";
    public static String DEFAULT_COST_VERSION = "b250";
    public static String DEFAULT_HEIGHTFIELDS_VERSION = DEFAULT_COST_VERSION + "_smd_p1_p1";

    private final String rawStack;

    private final String project;
    private final String ic2dSuffix;
    private final String normLayerSuffix;
    private final String claheSuffix;
    private final String costVersion;
    private final String heightfieldsVersion;

    public RawStack(final String rawStack) {
        this(rawStack,
             DEFAULT_IC2D_SUFFIX,
             DEFAULT_NORM_LAYER_SUFFIX,
             DEFAULT_CLAHE_SUFFIX,
             DEFAULT_COST_VERSION,
             DEFAULT_HEIGHTFIELDS_VERSION);
    }

    public RawStack(final String rawStack,
                    final String ic2dSuffix,
                    final String normLayerSuffix,
                    final String claheSuffix,
                    final String costVersion,
                    final String heightfieldsVersion) {
        this.rawStack = rawStack;
        this.project = buildProjectName(rawStack);
        this.ic2dSuffix = ic2dSuffix;
        this.normLayerSuffix = normLayerSuffix;
        this.claheSuffix = claheSuffix;
        this.costVersion = costVersion;
        this.heightfieldsVersion = heightfieldsVersion;
    }

    public String getRawStack() {
        return rawStack;
    }

    /** @return the render project name (e.g. w61_serial_070_to_079 for rawStack w61_s076_r00) */
    public String getProject() {
        return project;
    }

    /** @return the 2D intensity corrected stack name (e.g. w61_s076_r00_gc_par_align_ic2d) */
    public String getIC2DStack() {
        return rawStack + ic2dSuffix;
    }

    /** @return the 2D intensity corrected dataset (e.g. /render/w61_serial_070_to_079/w61_s076_r00_gc_par_align_ic2d) */
    @SuppressWarnings("unused")
    public String getIC2DDataset() {
        return "/render/" + project + "/" + getIC2DStack();
    }

    /** @return the normalized layer stack name (e.g. w61_s076_r00_gc_par_align_ic2d___norm-layer-v2-mb) */
    public String getNormLayerStack() {
        return rawStack + normLayerSuffix;
    }

    /** @return the normalized layer dataset (e.g. /render/w61_serial_070_to_079/w61_s076_r00_gc_par_align_ic2d___norm-layer-v2-mb) */
    public String getNormLayerDataset() {
        return "/render/" + project + "/" + getNormLayerStack();
    }

    /** @return the CLAHE stack name (e.g. w61_s076_r00_gc_par_align_ic2d___norm-layer-v2-mb_clahe) */
    public String getCLAHEStack() {
        return rawStack + claheSuffix;
    }

    /** @return the CLAHE dataset (e.g. /render/w61_serial_070_to_079/w61_s076_r00_gc_par_align_ic2d___norm-layer-v2-mb_clahe) */
    public String getCLAHEDataset() {
        return "/render/" + project + "/" + getCLAHEStack();
    }

    /** @return the cost dataset (e.g. /cost_b250/w61_serial_070_to_079/w61_s076_r00_gc_par_align_ic2d___norm-layer-v2-mb) */
    public String getCostDataset() {
        return "/cost_" + costVersion + "/" + project + "/" + getNormLayerStack();
    }

    /** @return the heightfields dataset (e.g. /heightfields_b250_smd_p1_p1/w61_serial_070_to_079/w61_s076_r00_gc_par_align_ic2d___norm-layer-v2-mb) */
    public String getHeightfieldsDataset() {
        return "/heightfields_" + heightfieldsVersion + "/" + project + "/" + getNormLayerStack();
    }

    /** @return the flat dataset (e.g. /flat_v2_mb/w61_serial_070_to_079/w61_s076_r00) */
    public String getFlatDataset() {
        return "/flat_v2_mb/" + project + "/" + rawStack;
    }

    /** @return the flat raw dataset (e.g. /flat/w61_serial_070_to_079/w61_s076_r00/raw) */
    public String getFlatRawDataset() {
        return getFlatDataset() + "/raw";
    }

    /** @return the flat raw dataset (e.g. /flat/w61_serial_070_to_079/w61_s076_r00/raw/s0) */
    public String getFlatRawS0Dataset() {
        return getFlatRawDataset() + "/s0";
    }

    /** @return the flat top or bottom dataset (e.g. /flat/w61_serial_070_to_079/w61_s076_r00/top) */
    public String getFlatEdgeDataset(final boolean isTop)
            throws IllegalArgumentException {
        final String edge = isTop ? "/top" : "/bot";
        return getFlatDataset() + edge;
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
