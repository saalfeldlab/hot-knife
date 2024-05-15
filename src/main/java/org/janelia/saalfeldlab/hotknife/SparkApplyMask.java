package org.janelia.saalfeldlab.hotknife;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaFutureAction;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.IntBinaryOperator;
import java.util.stream.Collectors;

public class SparkApplyMask {

	public enum ProjectionType {
		/**
		 * Minimum projection. Any pixel that is zero in any of the input masks is zero in the used mask.
		 */
		MIN(Math::min),
		/**
		 * Maximum projection. Any pixel that is zero in all the input masks is zero in the used mask.
		 */
		MAX(Math::max);

		private final IntBinaryOperator operator;

		ProjectionType(IntBinaryOperator operator) {
			this.operator = operator;
		}

		public int apply(int a, int b) {
			return operator.applyAsInt(a, b);
		}
	}

	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true,
				usage = "Path to the N5 containing all used datasets, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5")
		private String n5Path = null;

		@Option(name = "-i", aliases = {"--n5DatasetInput"}, required = true,
				usage = "Input N5 dataset, e.g. /flat/s075_m119/top4/face")
		private List<String> n5DatasetInputs = null;

		@Option(name = "-o", aliases = {"--n5DatasetOutput"}, required = true,
				usage = "Output N5 dataset, e.g. /flat/s075_m119/top4/face_local")
		private List<String> n5DatasetOutputs = null;

		@Option(name = "-m", aliases = {"--n5Mask"}, required = true,
				usage = "N5 dataset for mask of the whole stack, e.g. render/slab_070_to_079/s075_m119_align_no35_horiz_avgshd_ic___mask_20240504_145039")
		private List<String> n5Masks = null;

		@Option(name = "--scaleIndex",
				handler = StringArrayOptionHandler.class,
				usage = "the scale to normalize (multiple indices possible) " +
						"(e.g. '--scaleIndex 0 1 2 3 4 5 6 7 8 9' will automatically load s0-s9 relative to the given paths)")
		private List<String> scaleIndexList = null;

		@Option(name = "--overwrite", usage = "Overwrite existing n5 datasets without asking")
		private boolean overwrite = false;

		@Option(name = "--projection", required = true, usage = "Projection method for combining masks of different z-slices")
		private ProjectionType projectionType;


		public Options(final String[] args) {
			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				validate();
				parsedSuccessfully = true;
			} catch (final Exception e) {
				throw new IllegalArgumentException("Options were not parsed successfully", e);
			}
		}

		private void validate() {
			if (n5Path == null || n5Path.isEmpty()) {
				throw new IllegalArgumentException("Input N5 path must be specified");
			}
			if (n5DatasetInputs == null || n5DatasetInputs.isEmpty()) {
				throw new IllegalArgumentException("At least one input N5 dataset must be specified");
			}
			if (n5DatasetOutputs == null || n5DatasetOutputs.isEmpty()) {
				throw new IllegalArgumentException("At least one output N5 dataset must be specified");
			}
			if (n5Masks == null || n5Masks.isEmpty()) {
				throw new IllegalArgumentException("At least one mask N5 dataset must be specified");
			}
			if (scaleIndexList == null || scaleIndexList.isEmpty()) {
				throw new IllegalArgumentException("At least one scale index must be specified");
			}
			if (n5DatasetInputs.size() != n5DatasetOutputs.size() || n5DatasetInputs.size() != n5Masks.size()) {
				throw new IllegalArgumentException("Number of input, output, and mask datasets must match");
			}
		}

		public int getNumDatasets() {
			return n5DatasetInputs.size();
		}
	}


	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final SparkApplyMask.Options options = new SparkApplyMask.Options(args);
		printLogMessage("Operating on N5: " + options.n5Path);

		final List<Integer> scaleIndices = options.scaleIndexList.stream().map(Integer::parseInt).collect(Collectors.toList());
		final List<PathSpecification> paths = new ArrayList<>(options.getNumDatasets());

		for (int i = 0; i < options.getNumDatasets(); i++) {
			final String n5DatasetInput = options.n5DatasetInputs.get(i);
			final String n5DatasetOutput = options.n5DatasetOutputs.get(i);
			final String n5Mask = options.n5Masks.get(i);

			for (int scaleIndex : scaleIndices) {
				final PathSpecification pathSpec = new PathSpecification(n5DatasetInput, n5DatasetOutput, n5Mask, scaleIndex);
				paths.add(pathSpec);
				printLogMessage("Registering task " + pathSpec);
			}
		}

		// TODO: this is for local testing; remove for production
		final SparkConf conf = new SparkConf().setAppName("SparkPixelNormalizeN5").setMaster("local[*]");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");
		final List<JavaFutureAction<Void>> futures = new ArrayList<>(paths.size());

		for (final PathSpecification pathSpec : paths) {
			printLogMessage("Processing task " + pathSpec);
			final JavaFutureAction<Void> future = processTask(sparkContext, options.n5Path, pathSpec, options.projectionType, options.overwrite);
			futures.add(future);
		}

		for (final JavaFutureAction<Void> future : futures) {
			future.get();
		}

		sparkContext.close();
		printLogMessage("All tasks completed");
	}

	private static JavaFutureAction<Void> processTask(
			final JavaSparkContext sparkContext,
			final String n5Path,
			final PathSpecification path,
			final ProjectionType projectionType,
			final boolean overwrite) {

		final N5Reader n5Reader = new N5FSReader(n5Path);
		if (n5Reader.exists(path.getOutput()) & !overwrite) {
			n5Reader.close();
			throw new IllegalArgumentException("Output data set exists: " + n5Path + path.getOutput());
		}

		final List<Column> columns = splitIntoColumns(n5Reader, path.getInput());
		printLogMessage("Processing task " + path + " (split into " + columns.size() + " columns)");

		return sparkContext.parallelize(columns)
				.foreachAsync(column -> processColumn(n5Path, path, column, projectionType, overwrite));
	}

	private static void printLogMessage(final String msg) {
		final Date timeStamp = new Date(System.currentTimeMillis());
		System.out.println("[" + timeStamp + "]: " + msg);
	}

	private static List<Column> splitIntoColumns(final N5Reader n5Reader, final String dataset) {
		// divide the dataset into columns that form a grid in the xy-plane
		final DatasetAttributes attributes = n5Reader.getDatasetAttributes(dataset);
		final long[] dimensions = Arrays.copyOfRange(attributes.getDimensions(), 0, 2);
		final int[] blockSize = Arrays.copyOfRange(attributes.getBlockSize(), 0, 2);
		final List<long[][]> rawGrid = Grid.create(dimensions, blockSize);

		final List<Column> grid = new ArrayList<>(rawGrid.size());
		for (final long[][] block : rawGrid) {
			final long[] min = block[0];
			final long[] size = block[1];
			final long[] gridPosition = block[2];
			final Column column = new Column(min, size, gridPosition);
			grid.add(column);
		}
		return grid;
	}

	private static void processColumn(
			final String n5Path,
			final PathSpecification path,
			final Column column,
			final ProjectionType projectionType,
			final boolean overwrite) {

		final N5Writer n5Writer = new N5FSWriter(n5Path);

		RandomAccessibleInterval<FloatType> source = N5Utils.open(n5Writer, path.getInput());
		RandomAccessibleInterval<UnsignedByteType> mask = N5Utils.open(n5Writer, path.getMask());

		final RandomAccessibleInterval<FloatType> croppedSource = cropToColumn(source, column);
		final RandomAccessibleInterval<UnsignedByteType> croppedMask = cropToColumn(mask, column);

		final RandomAccessibleInterval<UnsignedByteType> flattenedMask = flattenMask(croppedMask, projectionType);
		final RandomAccessibleInterval<FloatType> output = applyMask(croppedSource, flattenedMask);

		final DatasetAttributes attributes = n5Writer.getDatasetAttributes(path.getInput());

		if (overwrite) {
			// existence has been checked before, so we can safely remove the output dataset
			n5Writer.remove(path.getOutput());
		}

		N5Utils.saveNonEmptyBlock(output, n5Writer, path.getOutput(), attributes, column.getGridPosition(), new FloatType());
		n5Writer.close();
	}

	private static <T extends RealType<T> & NativeType<T>> RandomAccessibleInterval<T> cropToColumn(
			final RandomAccessibleInterval<T> source,
			final Column column) {

		final int n = source.numDimensions();
		final long[] min = (n == 2) ? column.getMin() : new long[] { column.getMin()[0], column.getMin()[1], source.min(2) };
		final long[] max = (n == 2) ? column.getMax() : new long[] { column.getMax()[0], column.getMax()[1], source.max(2) };

		return Views.interval(source, min, max);
	}

	private static RandomAccessibleInterval<UnsignedByteType> flattenMask(
			final RandomAccessibleInterval<UnsignedByteType> maskStack,
			final ProjectionType projection) {

		if (maskStack.numDimensions() == 2) {
			// nothing to do, mask is already two-dimensional
			return maskStack;
		}

		// copy first slice into a new 2D image
		final long[] dimensions = Arrays.copyOfRange(maskStack.dimensionsAsLongArray(), 0, 2);
		final RandomAccessibleInterval<UnsignedByteType> flattenedMask = new ArrayImgFactory<>(new UnsignedByteType()).create(dimensions);

		final RandomAccessibleInterval<UnsignedByteType> firstSlice = Views.hyperSlice(maskStack, 2, 0);
		LoopBuilder.setImages(firstSlice, flattenedMask).forEachPixel((i, o) -> o.set(i.get()));

		// reduce the mask stack to the 2D mask
		for (int z = 1; z < maskStack.dimension(2); z++) {
			final RandomAccessibleInterval<UnsignedByteType> slice = Views.hyperSlice(maskStack, 2, z);
			LoopBuilder.setImages(slice, flattenedMask)
					.forEachPixel((i, o) -> o.set(projection.apply(i.get(), o.get())));
		}

		return flattenedMask;
	}

	private static <T extends RealType<T> & NativeType<T>> RandomAccessibleInterval<T> applyMask(
			RandomAccessibleInterval<T> source,
			RandomAccessibleInterval<UnsignedByteType> flattenedMask) {

		// ensure 3D input
		final int n = source.numDimensions();
		final RandomAccessibleInterval<T> output = (n == 2) ? Views.addDimension(source, 0, 0) : source;

		// apply mask pixel-wise
		for (int z = 0; z < output.dimension(2); z++) {
			final RandomAccessibleInterval<T> slice = Views.hyperSlice(output, 2, z);
			LoopBuilder.setImages(flattenedMask, slice)
					.forEachPixel((m, o) -> {
						if (m.get() == 0) {
							o.setZero();
						}
					});
		}

		// make sure that the output has the same number of dimensions as the input
		return (n == 2) ? Views.hyperSlice(output, 2, 0) : output;
	}

	private static class PathSpecification implements Serializable {
		private final String n5DatasetInput;
		private final String n5DatasetOutput;
		private final String n5Mask;
		private final int scaleIndex;

		private PathSpecification(
				final String n5DatasetInput,
				final String n5DatasetOutput,
				final String n5Mask,
				final int scaleIndex) {
			this.n5DatasetInput = sanitizeDatasetString(n5DatasetInput);
			this.n5DatasetOutput = sanitizeDatasetString(n5DatasetOutput);
			this.n5Mask = sanitizeDatasetString(n5Mask);
			this.scaleIndex = scaleIndex;
		}

		public String getInput() {
			return n5DatasetInput + "/s" + scaleIndex;
		}

		public String getOutput() {
			return n5DatasetOutput + "/s" + scaleIndex;
		}

		public String getMask() {
			return n5Mask + "/s" + scaleIndex;
		}

		public String toString() {
			return getInput() + " -> " + getOutput() + " with mask " + getMask();
		}

		private static final String LEADING_SLASHES = "^/*";
		private static final String TRAILING_SLASHES = "/*$";

		private static String sanitizeDatasetString(final String dataset) {
				String sanitizedDataset = dataset.trim();
				sanitizedDataset = sanitizedDataset.replaceAll(LEADING_SLASHES, "");
				sanitizedDataset = sanitizedDataset.replaceAll(TRAILING_SLASHES, "");
				return sanitizedDataset;
		}
	}

	private static class Column implements Serializable {
		private final long[] min;
		private final long[] max;
		private final long[] gridPosition;

		private Column(final long[] min, final long[] size, final long[] gridPosition) {
			this.min = min;
			this.max = new long[min.length];
			for (int d = 0; d < min.length; d++) {
				this.max[d] = min[d] + size[d] - 1;
			}
			this.gridPosition = gridPosition;
		}

		public long[] getMin() {
			return min;
		}

		public long[] getMax() {
			return max;
		}

		public long[] getGridPosition() {
			return gridPosition;
		}
	}
}
