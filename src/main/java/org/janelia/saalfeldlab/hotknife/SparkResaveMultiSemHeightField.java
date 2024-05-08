package org.janelia.saalfeldlab.hotknife;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.tools.ResaveMultiSemHeightField;

import picocli.CommandLine;
import picocli.CommandLine.Option;

public class SparkResaveMultiSemHeightField
        implements Callable<Void> {

	@Option(names = "--n5",
			required = true,
			description = "N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5")
	private String n5Path;

	@Option(names = "--dataSetJsonFile",
			required = true,
			description = "JSON file containing array of entries with from and to dataset names")
	private String dataSetJsonFile;

	@Option(names = "--scale",
			required = true,
			split=",",
			description = "downsampling factors, e.g. 6,6,1")
	private int[] downsamplingFactors;

	@Override
	public Void call() throws IllegalArgumentException, FileNotFoundException {

		final DataSetFromAndTo[] dataSetFromAndToArray =
				readDataSetFromAndTos(dataSetJsonFile);

		final SparkConf conf = new SparkConf().setAppName(getClass().getCanonicalName());
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");

		final List<ResaveMultiSemHeightField> fieldGroupList = new ArrayList<>();
		for (final DataSetFromAndTo dataSetFromAndTo : dataSetFromAndToArray) {
			final File fromDir = Paths.get(n5Path, dataSetFromAndTo.from).toFile();
			if (! fromDir.exists() || (! fromDir.isDirectory())) {
				throw new IllegalArgumentException("From dataset " + fromDir + " does not exist or is not a directory.");
			}
			fieldGroupList.add(new ResaveMultiSemHeightField(n5Path,
															 n5Path,
															 dataSetFromAndTo.from,
															 dataSetFromAndTo.to,
															 downsamplingFactors));
		}

		final JavaRDD<ResaveMultiSemHeightField> rddSlices = sparkContext.parallelize(fieldGroupList);
		rddSlices.foreach(parameters -> ResaveMultiSemHeightField.saveSmoothedHeightField(parameters,
																						  null));

		sparkContext.close();
		return null;
	}

	private static DataSetFromAndTo[] readDataSetFromAndTos(final String dataSetJsonFile)
			throws FileNotFoundException {
		final Gson gson = new Gson();
		final FileReader fileReader = new FileReader(dataSetJsonFile);
		return gson.fromJson(fileReader, DataSetFromAndTo[].class);
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new SparkResaveMultiSemHeightField()).execute(args);
	}

    // {
	//   "from": "/heightfields_v4/slab_070_to_079/s071_m331_align_no35_horiz_avgshd_ic___20240504_085310_norm-layer",
	//   "to": "/heightfields_fix/slab_070_to_079/s071_m331"
	// }
	@SuppressWarnings("unused")
	public static class DataSetFromAndTo {
		private String from;
		private String to;
	}
}

