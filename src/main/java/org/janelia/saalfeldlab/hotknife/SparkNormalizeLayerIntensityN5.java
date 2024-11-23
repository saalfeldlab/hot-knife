package org.janelia.saalfeldlab.hotknife;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.view.IntervalView;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.N5PathSupplier;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Version;

import static org.janelia.saalfeldlab.hotknife.AbstractOptions.parseCSIntArray;
import static org.janelia.saalfeldlab.n5.spark.downsample.scalepyramid.N5ScalePyramidSpark.downsampleScalePyramid;

public class SparkNormalizeLayerIntensityN5 {

	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput",
				required = true,
				usage = "Input N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5")
		private String n5PathInput = null;

		@Option(name = "--n5DatasetInput",
				required = true,
				usage = "Input N5 dataset, e.g. /render/slab_070_to_079/s075_m119_align_big_block_ic___20240308_072106")
		private String n5DatasetInput = null;

		@Option(
				name = "--factors",
				usage = "If specified, generates a scale pyramid with given factors, e.g. 2,2,1")
		public String factors;

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
										   final String datasetName, // should be s0
										   final String datasetNameOutput,
										   final List<Double> shifts,
										   final long[] dimensions,
										   final int[] blockSize,
										   final long[][] gridBlock ) {

		final N5Reader n5Input = new N5Factory().openReader( StorageFormat.N5, n5PathInput );//new N5FSReader(n5PathInput);
		final N5Writer n5Output = new N5Factory().openWriter( StorageFormat.N5, n5PathOutput ); //new N5FSWriter(n5PathOutput);

		final RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5Input, datasetName);

		final RandomAccessibleInterval<UnsignedByteType> filteredSource = applyShifts(sourceRaw, shifts);

		final FinalInterval gridBlockInterval =
				Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
										gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);

		N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
								  n5Output,
								  datasetNameOutput,
								  new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
								  gridBlock[2],
								  new UnsignedByteType());

		n5Input.close();
		n5Output.close();
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException, URISyntaxException {

		//System.out.println( "com.google.common.collect.Iterables: " + new File( com.google.common.collect.Iterables.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath() ).getName().trim() );
		//com.google.cloud.storage.StorageImpl a;
		//System.out.println( "com.google.cloud.storage.Storage: " + new File( com.google.cloud.storage.Storage.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath() ).getName().trim() );
		//System.out.println( "guava: " + new File( com.google.common.collect.Iterables.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath() ).getName().trim() );
		//System.out.println( "guava: " + new File( com.google.common.collect.Iterables.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath() ).getName().trim() );

		
		final SparkNormalizeLayerIntensityN5.Options options = new SparkNormalizeLayerIntensityN5.Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		final SparkConf conf = new SparkConf().setAppName("SparkNormalizeN5");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");

		final N5Reader n5Input = new N5Factory().openReader( StorageFormat.N5, options.n5PathInput );//new N5FSReader(options.n5PathInput);

		final String fullScaleInputDataset = options.n5DatasetInput + "/s0";
		final int[] blockSize = n5Input.getAttribute(fullScaleInputDataset, "blockSize", int[].class);
		final long[] dimensions = n5Input.getAttribute(fullScaleInputDataset, "dimensions", long[].class);

		final int[] gridBlockSize = new int[] { blockSize[0] * 8, blockSize[1] * 8, blockSize[2] };
		final List<long[][]> grid = Grid.create(dimensions, gridBlockSize, blockSize);

		final N5Writer n5Output = new N5Factory().openWriter( StorageFormat.N5, options.n5PathInput ); //new N5FSWriter(options.n5PathInput);
		final String outputDataset = options.n5DatasetInput + "_norm-layer_" + System.currentTimeMillis();
		final String fullScaleOutputDataset = outputDataset + "/s0";

		if (n5Output.exists(fullScaleOutputDataset)) {
			final String fullPath = options.n5PathInput + fullScaleOutputDataset;
			throw new IllegalArgumentException("Normalized data set exists: " + fullPath);
		}

		final String downScaledDataset = options.n5DatasetInput + "/s5";
		final Img<UnsignedByteType> downScaledImg = N5Utils.open(n5Input, downScaledDataset);

		System.out.println( new Date( System.currentTimeMillis() ) +  ": Computing shifts ... " );

		final List<Double> shifts = computeShifts(downScaledImg);

		shifts.forEach( d -> System.out.println( "\t" + d ) );

		System.out.println( new Date( System.currentTimeMillis() ) +  ": Creating " + fullScaleOutputDataset );

		n5Output.createDataset(fullScaleOutputDataset, dimensions, blockSize, DataType.UINT8, new GzipCompression());

		System.out.println( new Date( System.currentTimeMillis() ) +  ": Kicking off Spark for re-saving ... " );

		final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid);
		pGrid.foreach(
				gridBlock -> saveFullScaleBlock(options.n5PathInput,
												options.n5PathInput,
												fullScaleInputDataset,
												fullScaleOutputDataset,
												shifts,
												dimensions,
												blockSize,
												gridBlock ));

		n5Output.close();
		n5Input.close();

		final int[] downsampleFactors = parseCSIntArray(options.factors);
		if (downsampleFactors != null) {
			downsampleScalePyramid(sparkContext,
								   new N5PathSupplier(options.n5PathInput),
								   fullScaleOutputDataset,
								   outputDataset,
								   downsampleFactors);
		}

		sparkContext.close();
		System.out.println( new Date( System.currentTimeMillis() ) +  ": Done." );

	}

	private static List<Double> computeShifts(RandomAccessibleInterval<UnsignedByteType> rai) {

		// create mask from pixels that have "content" throughout the stack
		final List<IntervalView<UnsignedByteType>> downScaledStack = asZStack(rai);
		final Img<UnsignedByteType> contentMask = ArrayImgs.unsignedBytes(downScaledStack.get(0).dimensionsAsLongArray());
		for (final UnsignedByteType pixel : contentMask) {
			pixel.set(1);
		}

		final int lowerThreshold = 20;
		final int upperThreshold = 120;
		for (final IntervalView<UnsignedByteType> layer : downScaledStack) {
			LoopBuilder.setImages(layer, contentMask)
					.forEachPixel((a, b) -> {
						if (a.get() < lowerThreshold || a.get() > upperThreshold) {
							b.set(0);
						}
					});
		}

		final AtomicLong maskSize = new AtomicLong(0);
		for (final UnsignedByteType pixel : contentMask) {
			if (pixel.get() == 1) {
				maskSize.incrementAndGet();
			}
		}

		// compute average intensity of content pixels in each layer
		final List<Double> contentAverages = new ArrayList<>(downScaledStack.size());
		for (final IntervalView<UnsignedByteType> layer : downScaledStack) {
			final AtomicLong sum = new AtomicLong(0);
			LoopBuilder.setImages(layer, contentMask)
					.forEachPixel((a, b) -> {
						if (b.get() == 1) {
							sum.addAndGet(a.get());
						}
					});
			contentAverages.add((double) sum.get() / maskSize.get());
		}

		// compute shifts for adjusting intensities relative to the first layer
		final double fixedPoint = contentAverages.get(0);
		return contentAverages.stream().map(a -> a - fixedPoint).collect(Collectors.toList());
	}

	private static RandomAccessibleInterval<UnsignedByteType> applyShifts(
			final RandomAccessibleInterval<UnsignedByteType> sourceRaw,
			final List<Double> shifts ) {

		final List<IntervalView<UnsignedByteType>> sourceStack = asZStack(sourceRaw);
		final List<RandomAccessibleInterval<UnsignedByteType>> convertedLayers = new ArrayList<>(sourceStack.size());

		for (int z = 0; z < sourceStack.size(); ++z) {
			final byte shift = (byte) Math.round(shifts.get(z));
			final RandomAccessibleInterval<UnsignedByteType> layer = sourceStack.get(z);

			RandomAccessibleInterval<UnsignedByteType> convertedLayer = Converters.convert(layer, (s, t) -> {
				// only shift foreground
				if (s.get() > 0) {
					t.set( Math.max(0, Math.min( 255, s.get() - shift)));
				} else {
					t.set(0);
				}
			}, new UnsignedByteType());

			convertedLayers.add(convertedLayer);
		}

		RandomAccessibleInterval<UnsignedByteType> target = Views.stack(convertedLayers);

		return target;
	}

	private static List<IntervalView<UnsignedByteType>> asZStack(final RandomAccessibleInterval<UnsignedByteType> rai) {
		final List<IntervalView<UnsignedByteType>> stack = new ArrayList<>((int) rai.dimension(2));
		for (int z = 0; z < rai.dimension(2); ++z) {
			stack.add(Views.hyperSlice(rai, 2, z));
		}
		return stack;
	}
}
