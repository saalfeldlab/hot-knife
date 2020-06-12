package org.janelia.saalfeldlab.hotknife;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import static org.janelia.saalfeldlab.n5.spark.downsample.scalepyramid.N5ScalePyramidSpark.downsampleScalePyramid;

public class SparkDownsampleN5 {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path",
				required = true,
				usage = "N5 path, e.g. /nrs/flyem/tmp/VNC-align.n5")
		private final String n5Path = null;

		@Option(name = "--n5Dataset",
				required = true,
				usage = "N5 dataset, e.g. /align-v2/slab-15/raw")
		final String datasetName = null;

		@SuppressWarnings("unused")
		@Option(name = "--factors",
				required = true,
				usage = "Specifies generates a scale pyramid with given factors with relative scaling between factors, e.g. 2,2,2")
		private String downsamplingFactorsString;
		private int[] downsamplingFactors;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			downsamplingFactors = null;
			try {
				parser.parseArgument(args);

				if (downsamplingFactorsString != null) {
					int numFactors = 1;
					for (int idx = 0; (idx = downsamplingFactorsString.indexOf(",", idx)) >= 0; idx++) { numFactors++; }
					downsamplingFactors = new int[numFactors];
					parseCSIntArray(downsamplingFactorsString, downsamplingFactors);
				}

				parsedSuccessfully = true;
			} catch (final Exception e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		public String getN5Path() {
			return n5Path;
		}
		public String getDatasetName() {
			return datasetName;
		}
		public int[] getDownsamplingFactors() { return downsamplingFactors; }
	}

	public static void main(final String... args) throws IOException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName( "SparkConvertTiffSeriesToN5" );
        final JavaSparkContext sc = new JavaSparkContext(conf);

		String datasetName = options.getDatasetName();
		final String fullScaleName =Paths.get(datasetName, "s" + 0).toString();

		final File datasetDir = new File(Paths.get(options.getN5Path(), fullScaleName).toString());
		if (! datasetDir.exists()) {
			throw new IllegalArgumentException(
					"Full scale dataset " + datasetDir.getAbsolutePath() + " does not exist.");
		}

		// Now that the full resolution image is saved into n5, generate the scale pyramid
		final N5WriterSupplier n5Supplier = () -> new N5FSWriter(options.getN5Path() );

		// NOTE: no need to write full scale down-sampling factors (default is 1,1,1)

		downsampleScalePyramid(
				sc,
				n5Supplier,
				fullScaleName,
				datasetName,
				options.getDownsamplingFactors()
		);

		sc.close();

	}
}
