package org.janelia.saalfeldlab.hotknife;

import ij.ImageJ;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaFutureAction;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
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
import java.util.RandomAccess;
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

		printLogMessage("Start processing task " + path);
		final N5Reader n5Reader = new N5FSReader(n5Path);
		final RandomAccessibleInterval<FloatType> source = N5Utils.open(n5Reader, path.getInput());
		final long[] dimensions = Arrays.copyOfRange(source.dimensionsAsLongArray(), 0, 2);

		final RandomAccessibleInterval<UnsignedByteType> mask = N5Utils.open(n5Reader, path.getMask());
		final RandomAccessibleInterval<UnsignedByteType> flattenedMask = flattenMask(mask, projectionType);
		final RandomAccessibleInterval<FloatType> output = applyMask(source, flattenedMask);

		new ImageJ();
		final RandomAccessibleInterval<FloatType> sourceDup = N5Utils.open(n5Reader, path.getInput());
		ImageJFunctions.show(sourceDup);
		ImageJFunctions.show(output);


//		final RandomAccessibleInterval<UnsignedByteType> stackMasks = N5Utils.open(n5Reader, task.getMask());
//		final RandomAccessibleInterval<UnsignedByteType> flattenedMask = flattenMask(stackMasks, projectionType);

		n5Reader.close();
		printLogMessage("Done processing task " + path);
		return null;
	}

	private static void printLogMessage(final String msg) {
		final Date timeStamp = new Date(System.currentTimeMillis());
		System.out.println("[" + timeStamp + "]: " + msg);
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


//	private static void saveFullScaleBlock(final String n5PathInput,
//										   final String n5PathOutput,
//										   final String datasetName,
//										   final String datasetNameOutput,
//										   final long[] dimensions,
//										   final int[] blockSize,
//										   final long[][] gridBlock,
//										   final ProjectionType projectionType,
//										   final int scaleIndex) {
//
//		final N5Reader n5Input = new N5FSReader(n5PathInput);
//		final N5Writer n5Output = new N5FSWriter(n5PathOutput);
//
//		final FinalInterval gridBlockInterval;
//
//		if (blockSize.length == 3)
//			gridBlockInterval = Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
//								gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);
//		else
//			gridBlockInterval = Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1],
//								gridBlock[1][0], gridBlock[1][1]);
//
//		final RandomAccessibleInterval<?> sourceRawRaw = N5Utils.open(n5Input, datasetName);
//
//		//new ImageJ();
//		//ImageJFunctions.show(sourceRaw);
//
//		final RealType<?> t = (RealType<?>) sourceRawRaw.getAt(sourceRawRaw.minAsPoint());
//
//		if (t instanceof FloatType)
//		{
//			final RandomAccessibleInterval<FloatType> sourceRaw = (RandomAccessibleInterval<FloatType>)sourceRawRaw;
//			final RandomAccessibleInterval<FloatType> source;
//
//			source = sourceRaw;
//
//			final RandomAccessibleInterval<FloatType> filteredSource = normalizeContrast(source, new FloatType(), projectionType, scaleIndex, blockSize);
//
//			N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
//					  n5Output,
//					  datasetNameOutput,
//					  new DatasetAttributes(dimensions, blockSize, DataType.FLOAT32, new GzipCompression()),
//					  gridBlock[2],
//					  new FloatType());
//		}
//		else if (t instanceof UnsignedByteType)
//		{
//			final RandomAccessibleInterval<UnsignedByteType> sourceRaw = (RandomAccessibleInterval<UnsignedByteType>)sourceRawRaw;
//			final RandomAccessibleInterval<UnsignedByteType> source;
//
//			source = sourceRaw;
//
//			final RandomAccessibleInterval<UnsignedByteType> filteredSource = normalizeContrast(source,  new UnsignedByteType(), projectionType, scaleIndex, blockSize);
//
//			N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
//					  n5Output,
//					  datasetNameOutput,
//					  new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
//					  gridBlock[2],
//					  new UnsignedByteType());
//		}
//		else
//			throw new IllegalArgumentException("Unsupported input type: " + t.getClass().getName());
//
//		//ImageJFunctions.show(filteredSource);
//		//SimpleMultiThreading.threadHaltUnClean();
//
//	}
//
//	protected static <T extends RealType<T> & NativeType<T>> RandomAccessibleInterval<T> normalizeContrast(
//			RandomAccessibleInterval<T> sourceRaw,
//			final T type,
//			final ProjectionType projectionType,
//			final int scaleIndex,
//			int[] blocksize)
//	{
//		final int n = sourceRaw.numDimensions();
//
//		final int scale = 1 <<scaleIndex;
//		final double inverseScale = 1.0 / scale;
//
//		final int blockRadius = (int)Math.round(511 * inverseScale); //1023
//
//		if (n == 2)
//		{
//			sourceRaw = Views.addDimension(sourceRaw, 0, 0);
//			blocksize = new int[] { blocksize[0], blocksize[1], 1 };
//		}
//
//		final ImageJStackOp<T> filter;
//
//		if (projectionType == NormalizationMethod.LOCAL_CONTRAST)
//		{
//			filter = new ImageJStackOp<>(
//					Views.extendMirrorSingle(sourceRaw),
//					(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 50, 0.5f, true, true, true),
//					blockRadius,
//					0,
//					255,
//					true); // do nothing if all black
//		}
//		else if (projectionType == NormalizationMethod.CLAHE)
//		{
//			filter = new ImageJStackOp<>(
//					Views.extendMirrorSingle(sourceRaw),
//					fp -> Flat.getFastInstance().run(new ImagePlus("", fp), blockRadius, 256, 10f, null, false),
//					blockRadius,
//					0,
//					255,
//					true); // do nothing if all black
//		}
//		else
//			throw new IllegalArgumentException("Unknown normalization method: " + projectionType);
//
//		final CachedCellImg<T, ?> out = Lazy.process(
//				sourceRaw,
//				blocksize,
//				type,
//				AccessFlags.setOf(AccessFlags.VOLATILE),
//				filter);
//
//		if (n == 2)
//			return Views.hyperSlice(out, 2, 0);
//		else
//			return out;
//	}
//
//	public static JavaFutureAction<Void> runWithSparkContext(
//			final JavaSparkContext sparkContext,
//			final String n5PathInput,
//			final String n5DatasetInput,
//			final String n5DatasetOutput,
//			final int scaleIndex,
//			final ProjectionType projectionType,
//			final boolean overwrite) {
//		final N5Reader n5Input = new N5FSReader(n5PathInput);
//
//		final int[] blockSize = n5Input.getAttribute(n5DatasetInput, "blockSize", int[].class);
//		final long[] dimensions = n5Input.getAttribute(n5DatasetInput, "dimensions", long[].class);
//		final DataType dataType = n5Input.getAttribute(n5DatasetInput, "dataType", DataType.class);
//
//		final List<long[][]> grid = Grid.create(dimensions, blockSize, blockSize);
//
//		final N5Writer n5Output = new N5FSWriter(n5PathInput);
//
//		if (n5Output.exists(n5DatasetOutput)) {
//			if (overwrite) {
//				n5Output.remove(n5DatasetOutput);
//			} else {
//				n5Input.close();
//				n5Output.close();
//				throw new IllegalArgumentException("Output data set already exists: " + n5PathInput + n5DatasetOutput);
//			}
//		}
//
//		n5Output.createDataset(n5DatasetOutput, dimensions, blockSize, dataType, new GzipCompression());
//
//		n5Output.close();
//		n5Input.close();
//
//		final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid);
//
//		return pGrid.foreachAsync(
//				gridBlock -> saveFullScaleBlock(n5PathInput,
//												n5PathInput,
//												n5DatasetInput,
//												n5DatasetOutput,
//												dimensions,
//												blockSize,
//												gridBlock,
//												projectionType,
//												scaleIndex));
//	}

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

}
