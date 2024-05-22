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
import org.checkerframework.checker.units.qual.min;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.IntBinaryOperator;
import java.util.stream.Collectors;

/**
 * Apply a mask to an image. Both, the mask and the image can be 2D or 3D. If the mask is 3D, a z-projection is
 * performed before applying the mask to the image. If the image is 3D, the mask is applied to each z-slice of the
 * image. The dataset is resaved with the same shape and attributes as the input dataset.
 * <p>
 * This class is designed to be run on a Spark cluster. The parallelization is done by splitting the input dataset
 * into columns of blocks, each column is considered as a single task. A column is a list of blocks that are stacked
 * in z since the mask is the same for all of these blocks. The blocks are the physical blocks of the input dataset.
 *
 * @author Michael Innerberger
 */
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

	public static final HashSet<String> STANDARD_ATTRIBUTES = new HashSet<>(Arrays.asList("dataType", "dimensions", "blockSize", "compression"));


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
		final SparkConf conf = new SparkConf().setAppName("SparkApplyMask");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");
		final List<JavaFutureAction<Void>> futures = new ArrayList<>(paths.size());

		for (final PathSpecification pathSpec : paths) {
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

		final N5Writer n5Writer = new N5FSWriter(n5Path);
		if (n5Writer.exists(path.getOutput())) {
			if (overwrite) {
				// TODO: this can potentially be costly; consider using spark for this
				n5Writer.remove(path.getOutput());
			} else {
				n5Writer.close();
				throw new IllegalArgumentException("Output data set exists: " + n5Path + path.getOutput());
			}
		}

		final DatasetAttributes attributes = n5Writer.getDatasetAttributes(path.getInput());
		n5Writer.createDataset(path.getOutput(), attributes);
		transferAttributes(n5Writer, path);

		final List<List<Block>> columns = splitIntoColumns(attributes);
		printLogMessage("Processing task " + path + " (split into " + columns.size() + " columns)");

		return sparkContext.parallelize(columns)
				.foreachAsync(column -> processColumn(n5Path, path, column, projectionType));
	}

	private static void printLogMessage(final String msg) {
		final Date timeStamp = new Date(System.currentTimeMillis());
		System.out.println("[" + timeStamp + "]: " + msg);
	}

	private static void transferAttributes(final N5Writer n5Writer, final PathSpecification path) {
		final Map<String, Class<?>> attributeTypes = n5Writer.listAttributes(path.getInput());
		attributeTypes.forEach((name, type) -> {
			if (! ((Set<String>) STANDARD_ATTRIBUTES).contains(name)) {
				final Object value = n5Writer.getAttribute(path.getInput(), name, type);
				n5Writer.setAttribute(path.getOutput(), name, value);
			}
		});
	}

	/**
	 * Split the input dataset into columns of blocks.
	 *
	 * @param attributes the attributes of the input dataset
	 * @return a list of columns, where each column is a list of blocks
	 */
	private static List<List<Block>> splitIntoColumns(final DatasetAttributes attributes) {
		final long[] dimensions = attributes.getDimensions();
		final int[] blockSize = attributes.getBlockSize();
		final int numDimensions = attributes.getNumDimensions();

		if (numDimensions == 2) {
			// there is only one 2D-block per column
			final List<long[][]> grid2D = Grid.create(dimensions, blockSize);
			return grid2D.stream()
					.map(tile -> Collections.singletonList(new Block(tile[0], tile[1], tile[2])))
					.collect(Collectors.toCollection(ArrayList::new));
		} else if (numDimensions == 3) {
			// there are multiple 3D-blocks per column that we accumulate into a list
			final List<long[][]> grid3D = Grid.create(dimensions, blockSize);
			final Map<String, List<Block>> idToColumn = new HashMap<>();
			for (long[][] rawBlock : grid3D) {
				final long[] xyGridPosition = Arrays.copyOfRange(rawBlock[2], 0, 2);
				final String columnId = Arrays.toString(xyGridPosition);
				final List<Block> column = idToColumn.computeIfAbsent(columnId, k -> new ArrayList<>());
				column.add(new Block(rawBlock[0], rawBlock[1], rawBlock[2]));
			}
			return new ArrayList<>(idToColumn.values());
		} else {
			throw new IllegalArgumentException("Unsupported number of dimensions: " + numDimensions);
		}
	}

	private static void processColumn(
			final String n5Path,
			final PathSpecification path,
			final List<Block> column,
			final ProjectionType projectionType) {

		final N5Writer n5Writer = new N5FSWriter(n5Path);

		final RandomAccessibleInterval<UnsignedByteType> mask = N5Utils.open(n5Writer, path.getMask());
		final RandomAccessibleInterval<UnsignedByteType> croppedMask = cropToColumn(mask, column);
		final RandomAccessibleInterval<UnsignedByteType> flattenedMask = flattenMask(croppedMask, projectionType);

		final DatasetAttributes attributes = n5Writer.getDatasetAttributes(path.getInput());
		if (attributes.getDataType() == DataType.FLOAT32) {
			computeAndSaveResults(n5Writer, path, attributes, flattenedMask, column, new FloatType());
			n5Writer.close();
		} else if (attributes.getDataType() == DataType.UINT8) {
			computeAndSaveResults(n5Writer, path, attributes, flattenedMask, column, new UnsignedByteType());
			n5Writer.close();
		} else {
			n5Writer.close();
			throw new IllegalArgumentException("Unsupported data type: " + attributes.getDataType());
		}
	}

	private static <T extends RealType<T> & NativeType<T>> void computeAndSaveResults(
			final N5Writer n5Writer,
			final PathSpecification path,
			final DatasetAttributes attributes,
			final RandomAccessibleInterval<UnsignedByteType> flattenedMask,
			final List<Block> column,
			final T type) {

		final RandomAccessibleInterval<T> source = N5Utils.open(n5Writer, path.getInput());
		final RandomAccessibleInterval<T> croppedSource = cropToColumn(source, column);
		final RandomAccessibleInterval<T> output = applyMask(croppedSource, flattenedMask);

		for (final Block block : column) {
			// save each block of the column individually
			final RandomAccessibleInterval<T> croppedOutput = Views.interval(output, block.getMin(), block.getMax());
			N5Utils.saveNonEmptyBlock(croppedOutput, n5Writer, path.getOutput(), attributes, block.getGridPosition(), type);
		}
	}

	private static <T extends RealType<T> & NativeType<T>> RandomAccessibleInterval<T> cropToColumn(
			final RandomAccessibleInterval<T> source,
			final List<Block> column) {

		final int n = source.numDimensions();
		final Block firstBlock = column.get(0);
		final long[] min = (n == 2) ? firstBlock.getMin() : new long[] { firstBlock.getMin()[0], firstBlock.getMin()[1], source.min(2) };
		final long[] max = (n == 2) ? firstBlock.getMax() : new long[] { firstBlock.getMax()[0], firstBlock.getMax()[1], source.max(2) };

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

	private static class Block implements Serializable {
		private final long[] min;
		private final long[] size;
		private final long[] gridPosition;

		private Block(final long[] min, final long[] size, final long[] gridPosition) {
			this.min = min;
			this.size = size;
			this.gridPosition = gridPosition;
		}

		public long[] getMin() {
			return min;
		}

		public long[] getMax() {
			final long[] max = new long[numDimensions()];
			for (int d = 0; d < min.length; d++) {
				max[d] = min[d] + size[d] - 1;
			}
			return max;
		}

		public long[] getGridPosition() {
			return gridPosition;
		}

		public int numDimensions() {
			return min.length;
		}
	}
}
