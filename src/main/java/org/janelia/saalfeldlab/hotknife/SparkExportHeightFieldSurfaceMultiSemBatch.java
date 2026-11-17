package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.hotknife.util.NeuroglancerAttributes;
import org.janelia.saalfeldlab.hotknife.util.RawStack;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.ByteArrayDataBlock;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/**
 * Exports a heightfield as a 3D uint8 "surface" volume that neuroglancer can display, so the surface
 * can be reviewed over the ic3d pixel export in any XZ or YZ view.
 * <br/>
 * For each x, y column, the voxel at the heightfield's z is set to {@link #SURFACE_VALUE} and every other
 * voxel is 0 (blocks without any surface voxels are not written).  The heightfield values are z in s0
 * voxels and are converted the same way {@link org.janelia.saalfeldlab.hotknife.util.FlatteningInfo} does:
 * <pre>
 *   s0 z = (value + 0.5) * z factor - 0.5
 * </pre>
 * The surface is written as a single level (s0) with the x and y resolution of the heightfield level
 * (e.g. s1 is 2 x 2 pixel s0 voxels) and full pixel s0 z.  No coarser levels are written.
 * Neuroglancer attributes (resolution and a single scale) are written to the surface group so it lines up
 * with the pixel export.
 * <br/>
 * The surface group is kept outside of the heightfields group (see
 * {@link RawStack#getHeightfieldsSurfaceDataset}) because neuroglancer merges the attributes.json of
 * every parent group into a dataset's attributes.
 */
@SuppressWarnings("FieldMayBeFinal")
public class SparkExportHeightFieldSurfaceMultiSemBatch
        implements Serializable {

    public static final byte SURFACE_VALUE = (byte) 255;

    public static class Options extends AbstractOptions implements Serializable {

        @Option(name = "--n5RootPath",
                required = true,
                usage = "N5 root path for the pixel export, heightfields, and output, e.g. gs://janelia-spark-test/hess_wafers_60_61_export")
        private String n5RootPath = null;

        @Option(name = "--raw",
                required = true,
                usage = "Raw names for dataset(s), repeat for multiple datasets e.g. --raw w61_s079_r00 --raw w61_s080_r00 ...")
        private List<String> rawNameList = new ArrayList<>();

        @Option(name = "--fieldName",
                usage = "Heightfield to export, min (top surface) or max (bottom surface)")
        private String fieldName = "max";

        @Option(name = "--fieldScale",
                usage = "Heightfield scale level to export (s1 is the level used for flattening)")
        private int fieldScale = 1;

        @Option(name = "--blockSize",
                usage = "Size of output blocks, e.g. 256,256,128")
        private String blockSize = "256,256,128";

        @Option(name = "--pixelResolutionNm",
                usage = "Resolution of the pixel export s0 voxels in nanometers (all axes)")
        private double pixelResolutionNm = 8.0;

        public Options(final String[] args) {
            final CmdLineParser parser = new CmdLineParser(this);
            try {
                parser.parseArgument(args);
                if (! ("min".equals(fieldName) || "max".equals(fieldName))) {
                    throw new IllegalArgumentException("--fieldName must be min or max, not '" + fieldName + "'");
                }
                if (fieldScale < 1) {
                    throw new IllegalArgumentException("--fieldScale must be 1 or higher (heightfields have no s0)");
                }
                parseBlockSize();
                parsedSuccessfully = true;
            } catch (final Exception e) {
                e.printStackTrace(System.err);
                parser.printUsage(System.err);
            }
        }

        public int[] parseBlockSize() {
            final int[] size = new int[3];
            if (! parseCSIntArray(blockSize, size) || Arrays.stream(size).anyMatch(s -> s < 1)) {
                throw new IllegalArgumentException("--blockSize must be three positive integers, not '" + blockSize + "'");
            }
            return size;
        }
    }

    /** Everything needed to build the surface for one stack (validated before any Spark work starts). */
    public static class SurfaceInfo implements Serializable {

        private final String n5RootPath;
        private final String rawStack;
        private final String fieldDataset;
        private final String surfaceGroup;
        private final long[] fieldDimensions;   // x, y of the heightfield level
        private final int[] fieldBlockSize;
        private final double[] fieldFactors;    // x, y, z factors of the heightfield level relative to pixel s0
        private final long depth;               // pixel s0 z
        private final int[] blockSize;

        public SurfaceInfo(final Options options,
                           final String rawStackName)
                throws IOException {

            final RawStack rawStack = new RawStack(rawStackName);
            this.n5RootPath = options.n5RootPath;
            this.rawStack = rawStackName;

            final String fieldGroup = rawStack.getHeightfieldsDataset() + "/s" + options.fieldScale;
            this.fieldDataset = fieldGroup + "/" + options.fieldName;
            this.surfaceGroup = rawStack.getHeightfieldsSurfaceDataset(options.fieldName);
            this.blockSize = options.parseBlockSize();

            try (final N5Reader n5 = N5Util.createN5Reader(n5RootPath)) {

                final String pixelDataset = rawStack.getIC3DPixelDatasetS0();
                Util.checkDatasetExistence(n5, pixelDataset, true);
                Util.checkDatasetExistence(n5, fieldDataset, true);
                Util.checkDatasetExistence(n5, surfaceGroup + "/s0", false);

                this.depth = n5.getDatasetAttributes(pixelDataset).getDimensions()[2];

                this.fieldFactors = Util.readRequiredAttribute(n5, fieldGroup, "downsamplingFactors", double[].class);
                if (fieldFactors.length != 3) {
                    throw new IOException(fieldGroup + " downsamplingFactors has " + fieldFactors.length +
                                          " values instead of 3");
                }

                final DatasetAttributes fieldAttributes = n5.getDatasetAttributes(fieldDataset);
                if (fieldAttributes.getDataType() != DataType.FLOAT32 || fieldAttributes.getNumDimensions() != 2) {
                    throw new IOException(fieldDataset + " is " + fieldAttributes.getNumDimensions() + "D " +
                                          fieldAttributes.getDataType() + " instead of 2D float32");
                }
                this.fieldDimensions = fieldAttributes.getDimensions();
                this.fieldBlockSize = fieldAttributes.getBlockSize();
            }
        }

        /** @return x, y, z size of the surface volume (heightfield x and y, pixel s0 z). */
        public long[] getSurfaceDimensions() {
            return new long[] { fieldDimensions[0], fieldDimensions[1], depth };
        }

        public String getSurfaceDataset() {
            return surfaceGroup + "/s0";
        }

        @Override
        public String toString() {
            return "{rawStack: " + rawStack + ", fieldDataset: " + fieldDataset + ", surfaceGroup: " + surfaceGroup +
                   ", fieldDimensions: " + Arrays.toString(fieldDimensions) +
                   ", fieldFactors: " + Arrays.toString(fieldFactors) + ", depth: " + depth +
                   ", blockSize: " + Arrays.toString(blockSize) + "}";
        }
    }

    public static void main(final String... args) throws Exception {

        logMessage("main: entry, args=" + Arrays.toString(args));

        final Options options = new Options(args);
        if (! options.parsedSuccessfully) {
            throw new IllegalArgumentException("Options were not parsed successfully");
        }

        // validate everything up front so a bad stack does not fail the batch part way through
        final List<SurfaceInfo> infoList = new ArrayList<>();
        for (final String rawStackName : options.rawNameList) {
            final SurfaceInfo info = new SurfaceInfo(options, rawStackName);
            logMessage("main: validated " + info);
            infoList.add(info);
        }

        final SparkConf conf = new SparkConf().setAppName("SparkExportHeightFieldSurfaceMultiSemBatch");
        try (final JavaSparkContext sparkContext = new JavaSparkContext(conf)) {

            sparkContext.setLogLevel("ERROR");

            for (int i = 0; i < infoList.size(); i++) {
                final long start = System.currentTimeMillis();
                final SurfaceInfo info = infoList.get(i);

                exportSurface(sparkContext, info, options.pixelResolutionNm);

                logMessage("main: completed " + info.surfaceGroup + " (dataset " + (i + 1) + " of " + infoList.size() +
                           ") in " + ((System.currentTimeMillis() - start) / 60000) + " minutes");
            }
        }
    }

    public static void exportSurface(final JavaSparkContext sparkContext,
                                     final SurfaceInfo info,
                                     final double pixelResolutionNm)
            throws IOException {

        final String dataset = info.getSurfaceDataset();
        final long[] dimensions = info.getSurfaceDimensions();

        try (final N5Writer n5 = N5Util.createN5Writer(info.n5RootPath)) {
            n5.createDataset(dataset, dimensions, info.blockSize, DataType.UINT8, new GzipCompression());
            // for reference only, neuroglancer uses the resolution and scales attributes of the surface group
            n5.setAttribute(dataset, "downsamplingFactors", info.fieldFactors);
        }

        final List<long[]> xyBlocks = new ArrayList<>();
        for (long y = 0; y < dimensions[1]; y += info.blockSize[1]) {
            for (long x = 0; x < dimensions[0]; x += info.blockSize[0]) {
                xyBlocks.add(new long[] { x / info.blockSize[0], y / info.blockSize[1] });
            }
        }

        logMessage("exportSurface: building " + dataset + " with dimensions " +
                   Arrays.toString(dimensions) + " from " + xyBlocks.size() + " x, y block column(s)");

        sparkContext.parallelize(xyBlocks).foreachPartition(blockIterator -> {
            try (final N5Writer n5 = N5Util.createN5Writer(info.n5RootPath)) {
                final FieldReader fieldReader = new FieldReader(n5, info);
                while (blockIterator.hasNext()) {
                    writeBlockColumn(n5, info, fieldReader, blockIterator.next());
                }
            }
        });

        // the surface voxels are the heightfield level voxels in x and y and pixel s0 voxels in z,
        // there are no downsampled levels so the scales attribute only has s0
        final NeuroglancerAttributes ngAttributes =
                new NeuroglancerAttributes(Arrays.asList(pixelResolutionNm * info.fieldFactors[0],
                                                         pixelResolutionNm * info.fieldFactors[1],
                                                         pixelResolutionNm),
                                           "nm",
                                           0,
                                           new int[] { 2, 2, 1 },
                                           Arrays.asList(0L, 0L, 0L),
                                           NeuroglancerAttributes.NumpyContiguousOrdering.FORTRAN);
        try (final N5Writer n5 = N5Util.createN5Writer(info.n5RootPath)) {
            ngAttributes.write(n5, Paths.get(dataset));
        }
    }

    /** Writes every z block of one x, y block column of the surface (blocks without surface voxels are skipped). */
    private static void writeBlockColumn(final N5Writer n5,
                                         final SurfaceInfo info,
                                         final FieldReader fieldReader,
                                         final long[] xyBlock) {

        final String dataset = info.getSurfaceDataset();
        final long[] dimensions = info.getSurfaceDimensions();
        final DatasetAttributes attributes = new DatasetAttributes(dimensions, info.blockSize, DataType.UINT8, new GzipCompression());

        final long minX = xyBlock[0] * info.blockSize[0];
        final long minY = xyBlock[1] * info.blockSize[1];
        final int sizeX = (int) Math.min(info.blockSize[0], dimensions[0] - minX);
        final int sizeY = (int) Math.min(info.blockSize[1], dimensions[1] - minY);

        // surface s0 z of each x, y in the column (-1 where there is no surface)
        final long[] surfaceZ = new long[sizeX * sizeY];
        for (int y = 0; y < sizeY; y++) {
            for (int x = 0; x < sizeX; x++) {
                final float value = fieldReader.getValue(minX + x, minY + y);
                long z = -1;
                if (Float.isFinite(value)) {
                    z = Math.round((value + 0.5) * info.fieldFactors[2] - 0.5);
                    if ((z < 0) || (z >= info.depth)) {
                        z = -1;
                    }
                }
                surfaceZ[x + y * sizeX] = z;
            }
        }

        for (long minZ = 0; minZ < info.depth; minZ += info.blockSize[2]) {
            final int sizeZ = (int) Math.min(info.blockSize[2], info.depth - minZ);
            final byte[] data = new byte[sizeX * sizeY * sizeZ];
            boolean isEmpty = true;
            for (int i = 0; i < surfaceZ.length; i++) {
                final long z = surfaceZ[i] - minZ;
                if ((z >= 0) && (z < sizeZ)) {
                    // x fastest, then y, then z
                    data[i + (int) z * sizeX * sizeY] = SURFACE_VALUE;
                    isEmpty = false;
                }
            }
            if (! isEmpty) {
                final long[] gridPosition = { xyBlock[0], xyBlock[1], minZ / info.blockSize[2] };
                n5.writeBlock(dataset, attributes, new ByteArrayDataBlock(new int[] { sizeX, sizeY, sizeZ }, gridPosition, data));
            }
        }
    }

    /** Reads heightfield values, keeping the blocks it has read (a partition only touches a few of them). */
    private static class FieldReader {

        private final N5Reader n5;
        private final String dataset;
        private final DatasetAttributes attributes;
        private final int[] blockSize;
        private final Map<String, DataBlock<?>> blocks = new HashMap<>();

        FieldReader(final N5Reader n5,
                    final SurfaceInfo info) {
            this.n5 = n5;
            this.dataset = info.fieldDataset;
            this.attributes = n5.getDatasetAttributes(dataset);
            this.blockSize = info.fieldBlockSize;
        }

        /** @return the heightfield value at x, y or NaN if its block was never written. */
        float getValue(final long x,
                       final long y) {

            final long blockX = x / blockSize[0];
            final long blockY = y / blockSize[1];
            final String key = blockX + "," + blockY;

            // missing blocks are cached as null too, so they are only looked up once
            if (! blocks.containsKey(key)) {
                if (blocks.size() > 64) {
                    blocks.clear();
                }
                blocks.put(key, n5.readBlock(dataset, attributes, blockX, blockY));
            }

            final DataBlock<?> block = blocks.get(key);
            if (block == null) {
                return Float.NaN;
            }

            final int[] size = block.getSize();
            final long localX = x - blockX * blockSize[0];
            final long localY = y - blockY * blockSize[1];
            if ((localX >= size[0]) || (localY >= size[1])) {
                return Float.NaN;
            }
            return ((float[]) block.getData())[(int) (localX + localY * size[0])];
        }
    }

    private static void logMessage(final String message) {
        Util.logMessage(SparkExportHeightFieldSurfaceMultiSemBatch.class.getName(), message);
    }
}
