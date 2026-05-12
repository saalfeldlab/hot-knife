/*
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.hotknife;

import java.io.Serializable;
import java.util.ArrayList;
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

import static org.janelia.saalfeldlab.hotknife.SparkGenerateFaceScaleSpace.generateFace;

@SuppressWarnings("FieldMayBeFinal")
public class SparkGenerateFaceScaleSpaceMultiSEMBatch {


    public enum FaceEdge {
        TOP, BOTTOM, BOTH
    }

    public static class BatchOptions extends AbstractOptions implements Serializable {

        @Option(name = "--n5Path",
                required = true,
                usage = "N5 path for flat raw input and face output, e.g. gs://janelia-spark-test/hess_wafers_60_61_export")
        private String n5Path = null;

        @Option(name = "--raw",
                required = true,
                usage = "Raw names for dataset(s), repeat for multiple datasets e.g. --raw w61_s079_r00 --raw w61_s080_r00 ...")
        private List<String> rawNameList = new ArrayList<>();

        @Option(name = "--padding",
                usage = "Padding beyond flattening field in px (should match value used for exporting flattened volume)")
        private int padding = 3;

        @Option(name = "--faceEdge",
                usage = "Edge of the volume to generate face scale space for (TOP, BOTTOM, or BOTH)")
        private FaceEdge faceEdge = FaceEdge.BOTH;

        @Option(name = "--faceSize",
                usage = "Number of z-layers to include in the face")
        private int faceSize = 32;

        @Option(name = "--blockSize",
                usage = "Size of output blocks, e.g. 1024,1024")
        private String blockSize = "1024,1024";

        @Option(name = "--invert", usage = "MultiSem datasets might be inverted")
        private boolean invert = false;

        @Option(name = "--normalizeContrast", usage = "Perform contrast normalization on the input data")
        private boolean normalizeContrast = false;

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

        public List<RawStack> buildRawStacks() {
            return rawNameList.stream().map(RawStack::new).collect(Collectors.toList());
        }

    }

    private static SparkGenerateFaceScaleSpace.Options buildFaceOptions(final List<String> commonOptions,
                                                                        final BatchOptions batchOptions,
                                                                        final RawStack rawStack,
                                                                        final boolean isTopFace) {

        final String flatEdgeDataset = rawStack.getFlatRawClaheEdgeDataset(isTopFace);
        final int minZ = isTopFace ? batchOptions.padding : -batchOptions.padding - 1; // 3 or -4
        final int sizeZ = isTopFace ? batchOptions.faceSize : -batchOptions.faceSize;

        final List<String> optionValues = new ArrayList<>(commonOptions);
        optionValues.add("--n5DatasetInput=" + rawStack.getFlatRawCLAHES0Dataset());
        optionValues.add("--n5GroupOutput=" + flatEdgeDataset);
        optionValues.add("--min=0,0," + minZ);
        optionValues.add("--size=0,0," + sizeZ);

        logMessage("buildFaceOptions: " + optionValues);
        return new SparkGenerateFaceScaleSpace.Options(optionValues.toArray(new String[0]));
    }

    public static void main(final String... args) throws Exception {
        final BatchOptions batchOptions = new BatchOptions(args);
        if (! batchOptions.parsedSuccessfully) {
            throw new IllegalArgumentException("Options were not parsed successfully");
        }

        final SparkConf conf = new SparkConf().setAppName("SparkExportFlattenedVolumeMultiSEMBatch");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);
        sparkContext.setLogLevel("ERROR");

        final List<String> commonOptions = new ArrayList<>();
        commonOptions.add("--n5Path=" + batchOptions.n5Path);
        commonOptions.add("--blockSize=" + batchOptions.blockSize);
        if (batchOptions.invert) {
            commonOptions.add("--invert");
        }
        if (batchOptions.normalizeContrast) {
            commonOptions.add("--normalizeContrast");
        }

        final List<RawStack> rawStackList = batchOptions.buildRawStacks();

        // make sure all input datasets exist and all output datasets do not exist
        try (final N5Reader n5Reader = N5Util.createN5Reader(batchOptions.n5Path) ) {
            for (final RawStack rawStack : rawStackList) {

                final String flatRawS0Dataset = rawStack.getFlatRawS0Dataset();
                Util.checkDatasetExistence(n5Reader, flatRawS0Dataset, true);

                if (FaceEdge.TOP.equals(batchOptions.faceEdge) || FaceEdge.BOTH.equals(batchOptions.faceEdge)) {
                    final String flatTopDataset = rawStack.getFlatRawClaheEdgeDataset(true);
                    Util.checkDatasetExistence(n5Reader, flatTopDataset, false);
                }

                if (FaceEdge.BOTTOM.equals(batchOptions.faceEdge) || FaceEdge.BOTH.equals(batchOptions.faceEdge)) {
                    final String flatBottomDataset = rawStack.getFlatRawClaheEdgeDataset(false);
                    Util.checkDatasetExistence(n5Reader, flatBottomDataset, false);
                }

            }
        }

        // generate faces ...
        for (final RawStack rawStack : rawStackList) {

            if (FaceEdge.TOP.equals(batchOptions.faceEdge) || FaceEdge.BOTH.equals(batchOptions.faceEdge)) {
                logMessage("main: generating TOP face for " + rawStack.getRawStack());
                generateFace(sparkContext,
                             buildFaceOptions(commonOptions,
                                              batchOptions,
                                              rawStack,
                                              true));
            }

            if (FaceEdge.BOTTOM.equals(batchOptions.faceEdge) || FaceEdge.BOTH.equals(batchOptions.faceEdge)) {
                logMessage("main: generating BOTTOM face for " + rawStack.getRawStack());
                generateFace(sparkContext,
                             buildFaceOptions(commonOptions,
                                              batchOptions,
                                              rawStack,
                                              false));
            }

        }

        sparkContext.close();
    }

    private static void logMessage(final String message) {
        Util.logMessage(CLAZZ, message);
    }

    private static final String CLAZZ = SparkGenerateFaceScaleSpaceMultiSEMBatch.class.getSimpleName();
}
