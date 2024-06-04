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
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import static org.janelia.saalfeldlab.hotknife.SparkGenerateFaceScaleSpace.generateFace;

@SuppressWarnings("FieldMayBeFinal")
public class SparkGenerateFaceScaleSpaceMultiSEMBatch {


	public static class BatchOptions extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path",
				required = true,
				usage = "N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5")
		private String n5Path = null;

		// /flat_clahe/s036_m252/raw/s0,/flat_clahe/s036_m252/top4,4,34
		// /flat_clahe/s036_m252/raw/s0,/flat_clahe/s036_m252/bot4,-4,-34
		@Option(name = "--datasetCsv",
				required = true,
				usage = "File containing comma-separated n5DatasetInput,n5GroupOutput,minZ,sizeZ values")
		private String datasetCsv = null;

		@Option(name = "--blockSize",
				usage = "Size of output blocks, e.g. 1024,1024")
		private String blockSize = "1024,1024";

		@Option(name = "--invert", usage = "MultiSem datasets might be inverted")
		private boolean invert = false;

		@Option(name = "--normalizeContrast", usage = "Perform contast normalization on the input data")
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
	}

	public static void main(final String... args) throws Exception {
		final BatchOptions batchOptions = new BatchOptions(args);
		if (! batchOptions.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final List<String[]> datasetValuesList = new ArrayList<>();
		final Path datasetCsvPath = Paths.get(batchOptions.datasetCsv);
		final List<String> datasetCsvLines = Files.readAllLines(datasetCsvPath);
		for (int i = 0; i < datasetCsvLines.size(); ++i) {
			final String line = datasetCsvLines.get(i);
			final String[] values = line.split(",");
			if (values.length != 4) {
				throw new IllegalArgumentException("Expected 4 values per line in " + datasetCsvPath +
												   " but line " + i + " has " + values.length + " values");
			}
			try {
				Integer.parseInt(values[2]);
				Integer.parseInt(values[3]);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Failed to parse minZ,sizeZ values on line " + i +
												   " of " + datasetCsvPath, e);
			}
			datasetValuesList.add(values);
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

		for (final String[] datasetValues : datasetValuesList) {

			final List<String> optionValues = new ArrayList<>(commonOptions);
			optionValues.add("--n5DatasetInput=" + datasetValues[0]);
			optionValues.add("--n5GroupOutput=" + datasetValues[1]);
			optionValues.add("--min=0,0," + datasetValues[2]);
			optionValues.add("--size=0,0," + datasetValues[3]);

			// --n5Path=/nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5
			// --n5DatasetInput=/flat_clahe/s036_m252/raw/s0
			// --n5GroupOutput=/flat_clahe/s036_m252/bot4
			// --min=0,0,-4
			// --size=0,0,-34
			// --blockSize=1024,1024
			// --invert

			final SparkGenerateFaceScaleSpace.Options options =
					new SparkGenerateFaceScaleSpace.Options(optionValues.toArray(new String[0]));

			generateFace(sparkContext, options);
		}

		sparkContext.close();
	}

}
