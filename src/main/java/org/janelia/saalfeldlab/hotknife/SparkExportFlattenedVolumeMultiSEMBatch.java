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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.FlatteningInfo;
import org.janelia.saalfeldlab.hotknife.util.N5PathAndDataset;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.hotknife.util.RawStack;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import static org.janelia.saalfeldlab.hotknife.SparkExportFlattenedVolume.flattenVolume;
import static org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark.downsample;

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
        private int padding = 0;

        @Option(name = "--blockSize",
                usage = "Size of output blocks, e.g. 128,128,128")
        private String blockSize = "128,128,128";

        @Option(name = "--downsample",
                usage = "Downsample output volume by 2 in XY and 1 in Z")
        private boolean downsample = false;

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

        public List<FlatteningInfo> buildFlatteningInfoList()
                throws IOException {

            final List<FlatteningInfo> flatteningInfoList = new ArrayList<>();
            final int[] blockSizeArray = Arrays.stream(blockSize.split(","))
                    .map(Integer::parseInt)
                    .mapToInt(i -> i)
                    .toArray();

            for (final String rawStackName : rawNameList) {
                final RawStack rawStack = new RawStack(rawStackName);
                final N5PathAndDataset clahePathAndDataset = new N5PathAndDataset(n5RootPathName, rawStack.getCLAHEDataset() + "/s0");
                final N5PathAndDataset heightfieldPathAndDataset = new N5PathAndDataset(n5RootPathName, rawStack.getHeightfieldsDataset() + "/s1");
                final N5PathAndDataset flatPathAndDataset = new N5PathAndDataset(n5RootPathName, rawStack.getFlatRawDataset() + "/s0");
                final FlatteningInfo info = new FlatteningInfo(clahePathAndDataset,
                                                               heightfieldPathAndDataset,
                                                               true,
                                                               padding,
                                                               flatPathAndDataset,
                                                               blockSizeArray);
                System.out.println("buildFlatteningInfoList: adding " + info);
                flatteningInfoList.add(info);
            }

            return flatteningInfoList;
        }
    }

    public static void main(final String... args) throws Exception {

        final Options batchOptions = new Options(args);
        final List<FlatteningInfo> infoList = batchOptions.buildFlatteningInfoList();

        final SparkConf conf = new SparkConf().setAppName("SparkExportFlattenedVolumeMultiSEMBatch");
        final JavaSparkContext sparkContext = new JavaSparkContext(conf);
        sparkContext.setLogLevel("ERROR");

        for (final FlatteningInfo info : infoList) {

            final N5PathAndDataset flatPathAndDataset = info.getFlatPathAndDataset();
            final String flatDataset = flatPathAndDataset.getDataset();

            final int numberOfDownsampleLevels = 9; // s1 ... s9
            final List<String> downsampleOutputDatasetPaths = new ArrayList<>();
            if (batchOptions.downsample) {
                if (flatDataset.endsWith("/s0")) {
                    final String datasetWithSPrefix = flatDataset.substring(0, flatDataset.length() - 1);
                    for (int sLevel = 1; sLevel <= numberOfDownsampleLevels; sLevel++) {
                        downsampleOutputDatasetPaths.add(datasetWithSPrefix + sLevel);
                    }
                } else {
                    System.out.println("WARNING: will skip downsample for " + flatDataset + " because it does not end with /s0");
                }
            }

            flattenVolume(sparkContext, info);

            if (! downsampleOutputDatasetPaths.isEmpty()) {

                final int[] downsampleFactors = new int[] { 2, 2, 1 };
                final N5WriterSupplier n5Supplier = () -> N5Util.createN5Writer(batchOptions.n5RootPathName);

                downsample(sparkContext,
                           n5Supplier,
                           flatDataset,                         // .../s0
                           downsampleOutputDatasetPaths.get(0), // .../s1
                           downsampleFactors,
                           null);

                for (int i = 1; i < numberOfDownsampleLevels; i++) {
                    downsample(sparkContext,
                               n5Supplier,
                               downsampleOutputDatasetPaths.get(i - 1),  // .../s1 -> .../s8
                               downsampleOutputDatasetPaths.get(i),      // .../s2 -> .../s9
                               downsampleFactors,
                               null);
                }

            }

        }

        sparkContext.close();
    }

}
