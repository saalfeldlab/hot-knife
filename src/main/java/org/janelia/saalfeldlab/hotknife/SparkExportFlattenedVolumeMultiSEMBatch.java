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

import java.io.IOException;
import java.io.Serializable;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.DownsampleHelper;
import org.janelia.saalfeldlab.hotknife.util.FlatteningInfo;
import org.janelia.saalfeldlab.hotknife.util.N5PathAndDataset;
import org.janelia.saalfeldlab.hotknife.util.RawStack;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import static org.janelia.saalfeldlab.hotknife.SparkExportFlattenedVolume.flattenVolume;

@SuppressWarnings("FieldMayBeFinal")
public class SparkExportFlattenedVolumeMultiSEMBatch {


    public static class Options extends AbstractOptions implements Serializable {

        @Option(name = "--n5RootPath",
                required = true,
                usage = "N5 root path for raw input, height field, and output, e.g. gs://janelia-spark-test/hess_wafers_60_61_export")
        private String n5RootPathName = null;

        @Option(name = "--raw",
                required = true,
                usage = "Raw names for dataset(s), repeat for multiple datasets e.g. --raw w61_s079_r00 --raw w61_s080_r00 ...")
        private List<String> rawNameList = new ArrayList<>();

        @Option(name = "--padding",
                usage = "padding beyond flattening field min and max in px, e.g. 20")
        private int padding = 3;

        @Option(name = "--blockSize",
                usage = "Size of output blocks, e.g. 128,128,128")
        private String blockSize = "128,128,128";

        @Option(name = "--downsample",
                usage = "Downsample output volume by 2 in XY and 1 in Z")
        private boolean downsample = false;

        @Option(name = "--debugMode",
                usage = "enable debug mode to process only specific blocks")
        private SparkExportFlattenedVolume.DebugMode debugMode = SparkExportFlattenedVolume.DebugMode.OFF;

        @Option(name = "--debugBlockX",
                usage = "X coordinate of block to process in debug mode")
        private Long debugBlockX = null;

        @Option(name = "--debugBlockY",
                usage = "Y coordinate of block to process in debug mode")
        private Long debugBlockY = null;

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

        public List<SparkExportFlattenedVolume> buildExporters()
                throws IOException {

            final List<SparkExportFlattenedVolume> exporterList = new ArrayList<>();
            final int[] blockSizeArray = Arrays.stream(blockSize.split(","))
                    .map(Integer::parseInt)
                    .mapToInt(i -> i)
                    .toArray();

            String debugSuffix = "";
            if (! SparkExportFlattenedVolume.DebugMode.OFF.equals(debugMode)) {
                // 2007-12-03T10:15:30 -> 20071203_101530
                final ZoneId easternTimeZone = ZoneId.of("America/New_York");
                debugSuffix = "_debug_" + java.time.LocalDateTime.now(easternTimeZone)
                        .truncatedTo(ChronoUnit.SECONDS)
                        .toString()
                        .replace("T", "_")
                        .replace(":", "")
                        .replace("-", "");

                if (SparkExportFlattenedVolume.DebugMode.INTERACTIVE.equals(debugMode)) {
                    System.out.println("WARNING: running SparkExportFlattenedVolumeMultiSEMBatch with INTERACTIVE debug " +
                                       "mode will only work if launched as a local job (e.g. -Dspark.master=local[1])");
                }
            }

            for (final String rawStackName : rawNameList) {

                final RawStack rawStack = new RawStack(rawStackName);

                final String rawDataset = rawStack.getHistogramDataset() + "/s0";
                final String fieldGroup = rawStack.getHeightfieldsDataset() + "/s1";
                final String outDataset = rawStack.getFlatRawDataset() + debugSuffix + "/s0";
                final SparkExportFlattenedVolume exporter =
                        new SparkExportFlattenedVolume(n5RootPathName,
                                                       n5RootPathName,
                                                       n5RootPathName,
                                                       rawDataset,
                                                       fieldGroup,
                                                       outDataset,
                                                       padding,
                                                       blockSizeArray,
                                                       true,
                                                       debugMode,
                                                       debugBlockX,
                                                       debugBlockY);

                System.out.println("SparkExportFlattenedVolumeMultiSEMBatch: created " + exporter);
                exporter.buildFlatteningInfo(); // build info here to validate everything upfront
                exporterList.add(exporter);
            }

            return exporterList;
        }
    }

    public static void main(final String... args) throws Exception {

        logMessage("main: entry, args=" + Arrays.toString(args));

        final Options batchOptions = new Options(args);
        final List<SparkExportFlattenedVolume> exporterList = batchOptions.buildExporters();

        final SparkConf conf = new SparkConf().setAppName("SparkExportFlattenedVolumeMultiSEMBatch");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);
        sparkContext.setLogLevel("ERROR");

        logMessage("main: processing " + exporterList.size() + " datasets");

        for (int exporterIndex = 0; exporterIndex < exporterList.size(); exporterIndex++) {

            final long start = System.currentTimeMillis();

            final SparkExportFlattenedVolume exporter = exporterList.get(exporterIndex);
            final FlatteningInfo info = exporter.buildFlatteningInfo(); // re-build for actual usage
            final N5PathAndDataset flatPathAndDataset = info.getFlatPathAndDataset();
            final String flatDataset = flatPathAndDataset.getDataset();

            logMessage("main: building " + flatDataset);

            flattenVolume(sparkContext,
                          info,
                          batchOptions.debugMode,
                          batchOptions.debugBlockX,
                          batchOptions.debugBlockY);

            if (batchOptions.downsample) {
                new DownsampleHelper(batchOptions.n5RootPathName, flatDataset).run(sparkContext);
            }

            final long end = System.currentTimeMillis();

            logMessage("main: completed " + flatDataset +
                               " (dataset " + (exporterIndex + 1) + " of " + exporterList.size() + ") in " +
                               ((end - start) / 60000) + " minutes");
        }

        sparkContext.close();
    }

    private static void logMessage(final String message) {
        org.janelia.saalfeldlab.hotknife.util.Util.logMessage(SparkExportFlattenedVolumeMultiSEMBatch.class.getName(),
                                                              message);
    }
}
