package org.janelia.saalfeldlab.hotknife;

import java.io.File;
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
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import mpicbg.ij.clahe.Flat;
import net.imglib2.img.basictypeaccess.AccessFlags;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaFutureAction;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.ops.CLLCN;
import org.janelia.saalfeldlab.hotknife.ops.ImageJStackOp;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.scicomp.n5.zstandard.ZstandardCompression;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.converter.Converters;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

public class SparkPixelNormalizeN5 {

	public enum NormalizationMethod {
		/**
		 * Contrast Limited Local Contrast Normalization
		 */
		LOCAL_CONTRAST,
		/**
		 * Contrast Limited Adaptive Histogram Equalization
		 */
		CLAHE
	}

	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput",
				required = true,
				usage = "Input N5 path, e.g. /nrs/hess/data/hess_wafer_53/export/hess_wafer_53b.n5")
		private String n5PathInput = null;

		@Option(name = "--n5DatasetInput",
				required = true,
				usage = "Input N5 dataset, e.g. /flat/s075_m119/top4/face; or /flat/*/top4/face for all subdirectories")
		private String n5DatasetInput = null;

		@Option(name = "--n5DatasetOutput",
				required = true,
				usage = "Output N5 dataset, e.g. /flat/s075_m119/top4/face_local; or /flat/*/top4/face_local for all subdirectories")
		private String n5DatasetOutput = null;

		// TODO: this is completely included in scaleIndexList; consolidate the two (see SparkApplyMask)
		@Option(name = "--scaleIndex",
				usage = "the scaleIndex of the image we are normalizing " +
						"(if you want to specify a single resolution - will be ignored if --scaleIndexList is specified)")
		private int scaleIndex = 0;

		@Option(name = "--scaleIndexList",
				usage = "the scaleIndex range we are normalizing " +
						"(e.g. 0,1,2,3,4,5,6,7,8,9, which will automatically load s0-s9 relative to the given paths)")
		private String scaleIndexList = null;

		@Option(name = "--blockFactorXY",
				usage = "how much bigger the compute blocks in XY are than the blocks saved on disc")
		private int blockFactorXY = 8;

		@Option(name = "--invert",
				usage = "Invert before saving to N5, e.g. for MultiSEM")
		private boolean invert = false;

		@Option(name = "--overwrite",
				usage = "Overwrite existing n5 datasets without asking")
		private boolean overwrite = false;

		@Option(name = "--normalizeMethod",
				required = true,
				usage = "Normalization method, e.g. LOCAL_CONTRAST, CLAHE")
		private NormalizationMethod normalizeMethod;

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

		public List<Integer> getScaleIndices() {
			if (scaleIndexList != null) {
				return Arrays.stream(scaleIndexList.split("," ))
						.map(Integer::parseInt)
						.collect(Collectors.toList());
			} else {
				return Collections.singletonList(scaleIndex);
			}
		}
	}

	public static final HashSet<String> STANDARD_ATTRIBUTES = new HashSet<>(Arrays.asList("dataType", "dimensions", "blockSize", "compression"));


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

		final FinalInterval gridBlockInterval;
		
		if ( blockSize.length == 3 )
			gridBlockInterval = Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
								gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);
		else
			gridBlockInterval = Intervals.createMinSize(gridBlock[0][0], gridBlock[0][1],
								gridBlock[1][0], gridBlock[1][1]);

		RandomAccessibleInterval<?> sourceRawRaw = N5Utils.open(n5Input, datasetName);

		//new ImageJ();
		//ImageJFunctions.show( sourceRaw );

		RealType<?> t = (RealType<?>) sourceRawRaw.getAt( sourceRawRaw.minAsPoint() );

		// map 16bit to 8bit for jrc_mus-pancreas-7
		if (t instanceof UnsignedShortType)
		{
			final double minIntensity = 32000;
			final double range = 37000 - minIntensity;
			
			sourceRawRaw = Converters.convertRAI(
						(RandomAccessibleInterval<RealType>)sourceRawRaw,
						(i,o) -> o.set( Math.min( 255, Math.max( 0, (int)Math.round( (i.getRealDouble() - minIntensity) / range * 255.0 ) ) ) ),
						new UnsignedByteType() );

			t = new UnsignedByteType();
		}

		if (t instanceof FloatType)
		{
			final RandomAccessibleInterval<FloatType> sourceRaw = (RandomAccessibleInterval<FloatType>)sourceRawRaw;
			final RandomAccessibleInterval<FloatType> source;

			source = invert ?
				Converters.convertRAI(sourceRaw, (in, out) -> { if (in.get() == 0) { out.set(0 ); } else { out.set(255 - in.get() );} }, new FloatType() ) : sourceRaw;

			final RandomAccessibleInterval<FloatType> filteredSource = normalizeContrast(source, new FloatType(), normalizeMethod, 0, 255, scaleIndex, blockSize);

			N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
					  n5Output,
					  datasetNameOutput,
					  new DatasetAttributes(dimensions, blockSize, DataType.FLOAT32, new GzipCompression()),
					  gridBlock[2],
					  new FloatType());
		}
		else if (t instanceof UnsignedShortType)
		{
			final RandomAccessibleInterval<UnsignedShortType> sourceRaw = (RandomAccessibleInterval<UnsignedShortType>)sourceRawRaw;
			final RandomAccessibleInterval<UnsignedShortType> source;

			source = invert ?
					Converters.convertRAI(sourceRaw, (in, out) -> { if (in.get() == 0) { out.set(0 ); } else { out.set(65535 - in.get() );} }, new UnsignedShortType() ) : sourceRaw;

			final RandomAccessibleInterval<UnsignedShortType> filteredSource = normalizeContrast(source,  new UnsignedShortType(), normalizeMethod, 0, 65535, scaleIndex, blockSize);

			N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
					  n5Output,
					  datasetNameOutput,
					  new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
					  gridBlock[2],
					  new UnsignedShortType());
		}
		else if (t instanceof UnsignedByteType)
		{
			final RandomAccessibleInterval<UnsignedByteType> sourceRaw = (RandomAccessibleInterval<UnsignedByteType>)sourceRawRaw;
			final RandomAccessibleInterval<UnsignedByteType> source;

			source = invert ?
					Converters.convertRAI(sourceRaw, (in, out) -> { if (in.get() == 0) { out.set(0 ); } else { out.set(255 - in.get() );} }, new UnsignedByteType() ) : sourceRaw;

			final RandomAccessibleInterval<UnsignedByteType> filteredSource = normalizeContrast(source,  new UnsignedByteType(), normalizeMethod, 0, 255, scaleIndex, blockSize);

			N5Utils.saveNonEmptyBlock(Views.interval(filteredSource, gridBlockInterval),
					  n5Output,
					  datasetNameOutput,
					  new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
					  gridBlock[2],
					  new UnsignedByteType());
		}
		else
			throw new IllegalArgumentException("Unsupported input type: " + t.getClass().getName() );

		//ImageJFunctions.show( filteredSource );
		//SimpleMultiThreading.threadHaltUnClean();

	}

	protected static < T extends RealType< T > & NativeType<T> > RandomAccessibleInterval<T> normalizeContrast(
			RandomAccessibleInterval<T> sourceRaw,
			final T type,
			final NormalizationMethod normalizeMethod,
			final double minIntensity,
			final double maxIntensity,
			final int scaleIndex,
			int[] blocksize )
	{
		final int n = sourceRaw.numDimensions();

		final int scale = 1 << scaleIndex;
		final double inverseScale = 1.0 / scale;

		final int blockRadius = (int)Math.round(511 * inverseScale); //1023

		if ( n == 2 )
		{
			sourceRaw = Views.addDimension( sourceRaw, 0, 0 );
			blocksize = new int[] { blocksize[0], blocksize[1], 1 };
		}

		final ImageJStackOp<T> filter;

		if (normalizeMethod == NormalizationMethod.LOCAL_CONTRAST)
		{
			filter = new ImageJStackOp<>(
					Views.extendValue(sourceRaw, 170),
					(fp) -> {
						final FloatProcessor fpCopy = (FloatProcessor) fp.duplicate();

						for ( int i = 0; i < fp.getWidth() * fp.getHeight(); ++i )
							if ( fp.getf( i ) == 0 )
								fp.setf( i, 170 );

						new CLLCN(fp).run(blockRadius, blockRadius, 5f, 10, 0.5f, true, true, true);

						for ( int i = 0; i < fp.getWidth() * fp.getHeight(); ++i )
							if ( fpCopy.getf( i ) == 0 )
								fp.setf( i, 0 );
					},
					blockRadius,
					minIntensity,
					maxIntensity,
					true ); // do nothing if all black
		}
		else if (normalizeMethod == NormalizationMethod.CLAHE)
		{
			filter = new ImageJStackOp<>(
					Views.extendMirrorSingle(sourceRaw),
					fp -> Flat.getFastInstance().run(new ImagePlus("", fp), blockRadius, 256, 10f, null, false),
					blockRadius,
					minIntensity,
					maxIntensity,
					true); // do nothing if all black
		}
		else
			throw new IllegalArgumentException("Unknown normalization method: " + normalizeMethod);

		final CachedCellImg<T, ?> out = Lazy.process(
				sourceRaw,
				blocksize,
				type,
				AccessFlags.setOf(AccessFlags.VOLATILE),
				filter);

		if ( n == 2 )
			return Views.hyperSlice( out, 2, 0 );
		else
			return out;
	}

	public static JavaFutureAction<Void> runWithSparkContext(
			final JavaSparkContext sparkContext,
			final String n5PathInput,
			final String n5DatasetInput,
			final String n5DatasetOutput,
			final int blockFactorXY,
			final int scaleIndex,
			final boolean invert,
			final NormalizationMethod normalizeMethod,
			final boolean overwrite )
	{
		final N5Reader n5Input = new N5FSReader(n5PathInput);

		//final String fullScaleInputDataset = options.n5DatasetInput + "/s0";
		final DatasetAttributes attributes = n5Input.getDatasetAttributes(n5DatasetInput);
		final int[] blockSize = attributes.getBlockSize();
		final long[] dimensions = attributes.getDimensions();
		final DataType dataType = attributes.getDataType();

		final int[] gridBlockSize = new int[blockSize.length]; //{ blockSize[0] * 8, blockSize[1] * 8, blockSize[2] };
		for ( int d = 0; d < Math.min(2, blockSize.length); ++ d)
			gridBlockSize[ d ] = blockSize[d] * blockFactorXY;

		for ( int d = 2; d < blockSize.length; ++d )
			gridBlockSize[ d ] = blockSize[d];

		final List<long[][]> grid = Grid.create(dimensions, gridBlockSize, blockSize);

		final N5Writer n5Output = new N5FSWriter(n5PathInput);

		if (n5Output.exists(n5DatasetOutput))
		{
			if ( overwrite )
			{
				n5Output.remove( n5DatasetOutput );
			}
			else
			{
				n5Input.close();
				n5Output.close();
				throw new IllegalArgumentException("Output data set exists: " + n5PathInput + n5DatasetOutput);
			}
		}

		// TODO: very specific for 16 > 8 bit conversion
		n5Output.createDataset(n5DatasetOutput, dimensions, blockSize, DataType.UINT8, new ZstandardCompression());
		transferAttributes(n5Output, n5DatasetInput, n5DatasetOutput);

		n5Output.close();
		n5Input.close();

		final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid);

		final JavaFutureAction<Void> future = pGrid.foreachAsync(
				gridBlock -> saveFullScaleBlock(n5PathInput,
												n5PathInput,
												n5DatasetInput,
												n5DatasetOutput,
												dimensions,
												blockSize,
												gridBlock,
												normalizeMethod,
												scaleIndex,
												invert));

		return future;
	}

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final SparkPixelNormalizeN5.Options options = new SparkPixelNormalizeN5.Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		options.n5DatasetInput = options.n5DatasetInput.trim();
		options.n5DatasetOutput = options.n5DatasetOutput.trim();

		while ( options.n5DatasetInput.length() > 1 && options.n5DatasetInput.endsWith( "/" ) )
			options.n5DatasetInput = options.n5DatasetInput.substring(0, options.n5DatasetInput.length() - 1 );

		while ( options.n5DatasetOutput.length() > 1 && options.n5DatasetOutput.endsWith( "/" ) )
			options.n5DatasetOutput = options.n5DatasetOutput.substring(0, options.n5DatasetOutput.length() - 1 );

		System.out.println( "n5DatasetInput=" + options.n5DatasetInput);
		System.out.println( "n5DatasetOutput=" + options.n5DatasetOutput);

		final HashMap< String, String > in2out = new HashMap<>();

		if ( options.n5DatasetInput.contains( "*" ) )
		{
			if ( !options.n5DatasetOutput.contains("*") || options.n5DatasetInput.split("\\*", -1).length-1 > 1 || options.n5DatasetOutput.split("\\*", -1).length-1 > 1 )
				throw new RuntimeException( "both in & out need to contain exactly one '*'." );

			final String startIn = options.n5DatasetInput.substring(0, options.n5DatasetInput.indexOf('*') );
			final String endIn = options.n5DatasetInput.substring(options.n5DatasetInput.indexOf('*') + 1);
			final String startOut = options.n5DatasetOutput.substring(0, options.n5DatasetOutput.indexOf('*') );
			final String endOut = options.n5DatasetOutput.substring(options.n5DatasetOutput.indexOf('*') + 1);

			final String[] inputDirs = new File( options.n5PathInput, startIn ).list( (dir, name) -> new File( dir, name ).isDirectory() );

			System.out.println( "Range specified, processing the following " + inputDirs.length + " folders, saving to ... " );

			for ( final String s : inputDirs )
			{
				in2out.put( startIn + s + endIn, startOut + s + endOut );
				System.out.println( startIn + s + endIn + " >>> " + startOut + s + endOut );
			}
		}
		else
		{
			in2out.put( options.n5DatasetInput, options.n5DatasetOutput );
		}

		final SparkConf conf = new SparkConf().setAppName("SparkPixelNormalizeN5");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");

		ArrayList< JavaFutureAction<Void> > futures = new ArrayList<>();

		final List<Integer> scaleIndices = options.getScaleIndices();

		for ( final Entry<String, String > entry : in2out.entrySet() )
		{
			final String n5DatasetInput = entry.getKey();
			final String n5DatasetOutput = entry.getValue();

			System.out.println( "Processing: " + n5DatasetInput + " >>> " + n5DatasetOutput );

			for (final Integer scaleIndex : scaleIndices) {

				final String myN5DatasetInput = n5DatasetInput + "/s" + scaleIndex;
				final String myN5DatasetOutput = n5DatasetOutput + "/s" + scaleIndex;

				System.out.println( "(" + new Date( System.currentTimeMillis() ) + "): Running scale index " + scaleIndex + " for " + myN5DatasetInput + " >>> " + myN5DatasetOutput );

				futures.add( runWithSparkContext(
						sparkContext,
						options.n5PathInput,
						myN5DatasetInput,
						myN5DatasetOutput,
						options.blockFactorXY,
						scaleIndex,
						options.invert,
						options.normalizeMethod,
						options.overwrite ) );
			}
		}

		System.out.println( "(" + new Date( System.currentTimeMillis() ) + "): waiting for execution" );

		futures.forEach( f ->
		{
			try { f.get(); } catch (InterruptedException | ExecutionException e) { e.printStackTrace(); }
		} );

		sparkContext.close();

		System.out.println( "(" + new Date( System.currentTimeMillis() ) + "): Done." );
	}

	public static void transferAttributes(final N5Writer n5Writer, final String input, final String output) {
		final Map<String, Class<?>> attributeTypes = n5Writer.listAttributes(input);
		attributeTypes.forEach((name, type) -> {
			if (! ((Set<String>) STANDARD_ATTRIBUTES).contains(name)) {
				final Object value = n5Writer.getAttribute(input, name, type);
				n5Writer.setAttribute(output, name, value);
			}
		});
	}
}
