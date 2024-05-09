package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

public class SparkPixelNormalizeN5 {

	public enum NormalizationMethod {
		/**
		 * Contrast Limited Local Contrast Normalization
		 */
		LOCAL_CONTRAST("_norm-local"),
		;

		private final String fileNameSuffix;
		NormalizationMethod(final String fileNameSuffix) {
			this.fileNameSuffix = fileNameSuffix;
		}
	}

	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput",
				required = true,
				usage = "Input N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5")
		private String n5PathInput = null;

		@Option(name = "--n5DatasetInput",
				required = true,
				usage = "Input N5 dataset, e.g. /render/slab_070_to_079/s075_m119_align_big_block_ic___20240308_072106/s0")
		private String n5DatasetInput = null;

		@Option(name = "--n5DatasetOutput",
				required = true,
				usage = "Output N5 dataset, e.g. /render/slab_070_to_079/s075_m119_align_big_block_ic___20240308_072106_norm/s0")
		private String n5DatasetOutput = null;

		@Option(name = "--scaleIndex", usage = "the scaleIndex of the image we are normalizing")
		private int scaleIndex = 0;

		@Option(name = "--invert", usage = "Invert before saving to N5, e.g. for MultiSEM")
		private boolean invert = false;

		@Option(name = "--normalizeMethod", usage = "Normalization method, e.g. LOCAL_CONTRAST, LAYER_INTENSITY")
		private NormalizationMethod normalizeMethod = null;

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

	private static void saveFullScaleBlock(final String n5PathInput,
										   final String n5PathOutput,
										   final String datasetName,
										   final String datasetNameOutput,
										   final long[] dimensions,
										   final int[] blockSize,
										   final long[][] gridBlock,
										   final NormalizationMethod normalizeMethod,
										   final int scaleIndex,
										   final boolean invert) {

		final N5Reader n5Input = new N5FSReader(n5PathInput);
		final N5Writer n5Output = new N5FSWriter(n5PathOutput);

		final RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5Input, datasetName);

		//new ImageJ();
		//ImageJFunctions.show( sourceRaw );
		final RandomAccessibleInterval<UnsignedByteType> filteredSource;
		if (normalizeMethod == NormalizationMethod.LOCAL_CONTRAST) {
			 filteredSource = SparkGenerateFaceScaleSpace.filter(sourceRaw, invert, true, scaleIndex, blockSize ); // scaleIndex defines radius of the Local contrast
		} else {
			throw new IllegalArgumentException("Unknown normalization method: " + normalizeMethod);
		}

		//ImageJFunctions.show( filteredSource );
		//SimpleMultiThreading.threadHaltUnClean();

		final FinalInterval gridBlockInterval;
		
		if ( blockSize.length == 3 )
			gridBlockInterval = Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
								gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);
		else
			gridBlockInterval = Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1],
								gridBlock[1][0], gridBlock[1][1]);

		N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
								  n5Output,
								  datasetNameOutput,
								  new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
								  gridBlock[2],
								  new UnsignedByteType());
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final SparkPixelNormalizeN5.Options options = new SparkPixelNormalizeN5.Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final N5Reader n5Input = new N5FSReader(options.n5PathInput);

		//final String fullScaleInputDataset = options.n5DatasetInput + "/s0";
		final int[] blockSize = n5Input.getAttribute(options.n5DatasetInput, "blockSize", int[].class);
		final long[] dimensions = n5Input.getAttribute(options.n5DatasetInput, "dimensions", long[].class);

		final int[] gridBlockSize = new int[blockSize.length]; //{ blockSize[0] * 8, blockSize[1] * 8, blockSize[2] };
		for ( int d = 0; d < Math.min(2, blockSize.length); ++ d)
			gridBlockSize[ d ] = blockSize[d] * 8;

		for ( int d = 2; d < blockSize.length; ++d )
			gridBlockSize[ d ] = blockSize[d];

		final List<long[][]> grid = Grid.create(dimensions, gridBlockSize, blockSize);

		final N5Writer n5Output = new N5FSWriter(options.n5PathInput);

		if (n5Output.exists(options.n5DatasetOutput)) {
			n5Input.close();
			n5Output.close();
			throw new IllegalArgumentException("Output data set exists: " + options.n5PathInput + options.n5DatasetOutput);
		}

		n5Output.createDataset(options.n5DatasetOutput, dimensions, blockSize, DataType.UINT8, new GzipCompression());

		final SparkConf conf = new SparkConf().setAppName("SparkNormalizeN5");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");

		final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid);
		pGrid.foreach(
				gridBlock -> saveFullScaleBlock(options.n5PathInput,
												options.n5PathInput,
												options.n5DatasetInput,
												options.n5DatasetOutput,
												dimensions,
												blockSize,
												gridBlock,
												options.normalizeMethod,
												options.scaleIndex,
												options.invert));
		n5Output.close();
		n5Input.close();

		sparkContext.close();
	}
}
