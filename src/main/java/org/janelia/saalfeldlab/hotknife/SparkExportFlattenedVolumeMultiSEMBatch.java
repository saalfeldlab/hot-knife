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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5FSWriter;
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
				usage = "N5 root path for raw input, height field, and output, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5")
		private String n5RootPath = null;

		// /render/slab_000_to_009/s002_m395_align_no35_horiz_avgshd_ic___20240504_084955_norm-layer-clahe/s0,/heightfields_fix/slab_000_to_009/s002_m395,/flat/s002_m395/raw/s0
		@Option(name = "--datasetCsv",
				required = true,
				usage = "File containing comma-separated n5RawDataset,n5FieldGroup,n5OutDataset values")
		private String datasetCsv = null;

		@Option(name = "--padding",
				usage = "padding beyond flattening field min and max in px, e.g. 20")
		private int padding = 0;

		@Option(name = "--blockSize",
				usage = "Size of output blocks, e.g. 128,128,128")
		private String blockSize = "128,128,128";

		public int[] buildBlockSizeArray() {
			return Arrays.stream(blockSize.split(",")).map(Integer::parseInt).mapToInt(i -> i).toArray();
		}

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
	}

	public static void main(final String... args) throws Exception {
		final Options options = new Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}
		final int[] blockSizeArray = options.buildBlockSizeArray();

		final List<String[]> datasetValuesList = new ArrayList<>();
		final Path datasetCsvPath = Paths.get(options.datasetCsv);
		final List<String> datasetCsvLines = Files.readAllLines(datasetCsvPath);
		for (int i = 0; i < datasetCsvLines.size(); ++i) {
			final String line = datasetCsvLines.get(i);
			final String[] values = line.split(",");
			if (values.length != 3) {
				throw new IllegalArgumentException("Expected 3 values per line in " + datasetCsvPath +
												   " but line " + i + " has " + values.length + " values");
			}
			datasetValuesList.add(values);
		}

		final SparkConf conf = new SparkConf().setAppName("SparkExportFlattenedVolumeMultiSEMBatch");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");

		for (final String[] datasetValues : datasetValuesList) {

			final String n5RawDataset = datasetValues[0];
			final String n5FieldGroup = datasetValues[1];
			final String n5OutDataset = datasetValues[2];

			final int numberOfDownsampleLevels = 9; // s1 ... s9
			final List<String> downsampleOutputDatasetPaths = new ArrayList<>();
			if (options.downsample) {
				if (n5OutDataset.endsWith("/s0")) {
					final String datasetWithSPrefix = n5OutDataset.substring(0, n5OutDataset.length() - 1);
					for (int sLevel = 1; sLevel <= numberOfDownsampleLevels; sLevel++) {
						downsampleOutputDatasetPaths.add(datasetWithSPrefix + sLevel);
					}
				} else {
					System.out.println("WARNING: will skip downsample for " + n5OutDataset + " because it does not end with /s0");
				}
			}

			flattenVolume(sparkContext,
						  options.n5RootPath,
						  options.n5RootPath,
						  options.n5RootPath,
						  n5RawDataset,
						  n5FieldGroup,
						  n5OutDataset,
						  blockSizeArray,
						  options.padding,
						  true);

			if (! downsampleOutputDatasetPaths.isEmpty()) {

				final int[] downsampleFactors = new int[] { 2, 2, 1 };
				final N5WriterSupplier n5Supplier = () -> new N5FSWriter(options.n5RootPath);

				downsample(sparkContext,
						   n5Supplier,
						   n5OutDataset,                        // .../s0
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
