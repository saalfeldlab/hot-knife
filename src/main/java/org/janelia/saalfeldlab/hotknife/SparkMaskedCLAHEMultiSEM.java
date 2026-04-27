package org.janelia.saalfeldlab.hotknife;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import mpicbg.ij.clahe.Flat;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.N5Util;
import org.janelia.saalfeldlab.hotknife.util.RawStack;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.scicomp.n5.zstandard.ZstandardCompression;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.position.FunctionRealRandomAccessible;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

public class SparkMaskedCLAHEMultiSEM
{
	@SuppressWarnings({"FieldMayBeFinal", "unused"})
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5PathInput",
				required = true,
				usage = "Input N5 path, e.g. gs://janelia-spark-test/hess_wafers_60_61_export")
		private String n5PathInput = null;

		@Option(name = "--n5DatasetInput",
				required = true,
				usage = "Input N5 dataset, e.g. /render/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s0")
		private String n5DatasetInput = null;

		@Option(name = "--n5DatasetOutput",
				required = true,
				usage = "Output N5 dataset, e.g. /render/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer_clahe/s0")
		private String n5DatasetOutput = null;

		@Option(name = "--n5FieldMax",
				usage = "Input N5 dataset, e.g. /heightfields_v3/w61_serial_070_to_079/w61_s079_r00_gc_par_align_ic2d___norm-layer/s1/max " +
                        "NOTE: Do not specify if your input dataset is flat since input max z is used by default")
		private String n5FieldMax = null;

		@Option(name = "--blockFactorXY",
				usage = "how much bigger the compute blocks in XY are than the blocks saved on disc")
		private int blockFactorXY = 8;

		@Option(name = "--blockFactorZ",
				usage = "how much bigger the compute blocks in Z are than the blocks saved on disc")
		private int blockFactorZ = 1;

		@Option(name = "--invert",
				usage = "Invert before saving to N5, e.g. for old MultiSEM")
		private boolean invert = false;

		@Option(name = "--overwrite",
				usage = "Overwrite existing n5 datasets without asking")
		private boolean overwrite = false;

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

        public Options(final String n5PathInput,
                       final RawStack rawStack,
                       final int blockFactorXY,
                       final int blockFactorZ,
                       final boolean overwrite) {
            this.n5PathInput = n5PathInput;
            this.n5DatasetInput = rawStack.getFlatRawS0Dataset();
            this.n5DatasetOutput = rawStack.getFlatRawCLAHES0Dataset();
            this.blockFactorXY = blockFactorXY;
            this.blockFactorZ = blockFactorZ;
            this.overwrite = overwrite;
        }
	}

    public static void validateDatasets(final N5Reader n5Input,
                                        final RawStack rawStack)
            throws IOException {

        final String flatRawDatasetS0 = rawStack.getFlatRawS0Dataset();            // /flat/w61_serial_070_to_079/w61_s076_r00/raw/s0
        final String flatRawClaheDatasetS0 = rawStack.getFlatRawCLAHES0Dataset();  // /flat/w61_serial_070_to_079/w61_s076_r00/raw_clahe/s0

        Util.checkDatasetExistence(n5Input, flatRawDatasetS0, true);
        Util.checkDatasetExistence(n5Input, flatRawClaheDatasetS0, false);

        Util.readRequiredAttribute(n5Input, flatRawDatasetS0, "blockSize", int[].class);
        Util.readRequiredAttribute(n5Input, flatRawDatasetS0, "dimensions", long[].class);

        System.out.println("SparkMaskedCLAHEMultiSEM.validateDatasets: verified datasets and max field factors for " + flatRawDatasetS0);
    }

    public static void process(final JavaSparkContext sparkContext,
                               final Options options)
            throws IOException {

        final N5Reader n5Input = N5Util.createN5Reader(options.n5PathInput);
        final String inputAttrPath = Util.getAttributesJsonPath(options.n5PathInput,
                                                                options.n5DatasetInput);

        System.out.println("loading blockSize and dimensions from " + inputAttrPath);
        final int[] blockSize = Util.readRequiredAttribute(n5Input, options.n5DatasetInput, "blockSize", int[].class);
        final long[] dimensions = Util.readRequiredAttribute(n5Input, options.n5DatasetInput, "dimensions", long[].class);
        final long maxZ = dimensions[2];

        final int[] gridBlockSize = new int[]{
                blockSize[0] * options.blockFactorXY,
                blockSize[1] * options.blockFactorXY,
                blockSize[2] * options.blockFactorZ
        };

        final double[] maxFactors;
        if (options.n5FieldMax == null) {
            maxFactors = null;
            System.out.println("using maxZ " + maxZ + " for maxFieldScaled");
        } else {
            final String n5FieldMaxParent = options.n5FieldMax.substring(0, options.n5FieldMax.lastIndexOf('/'));
            final String fieldMaxParentAttrPath = Util.getAttributesJsonPath(options.n5PathInput, n5FieldMaxParent);
            maxFactors = Util.readRequiredAttribute(n5Input, n5FieldMaxParent, FACTORS_KEY, double[].class);
            System.out.println("loaded " + FACTORS_KEY + " " + Arrays.toString(maxFactors) + " from " + fieldMaxParentAttrPath);
        }

        final List<long[][]> grid = Grid.create(dimensions, gridBlockSize, blockSize);

        final N5Writer n5Output = N5Util.createN5Writer(options.n5PathInput);

        if (n5Output.exists(options.n5DatasetOutput)) {
            if (options.overwrite) {
                n5Output.remove(options.n5DatasetOutput);
            } else {
                n5Input.close();
                n5Output.close();
                throw new IllegalArgumentException("Output data set exists: " +
                                                   options.n5PathInput + options.n5DatasetOutput);
            }
        }

        n5Output.createDataset(options.n5DatasetOutput, dimensions, blockSize, DataType.UINT8, new ZstandardCompression());
        SparkPixelNormalizeN5.transferAttributes(n5Output, options.n5DatasetInput, options.n5DatasetOutput);

        n5Output.close();
        n5Input.close();

        final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid, Math.min(grid.size(), 15000));

        // new ImageJ();

		pGrid.foreach(
				gridBlock ->
				{
					/*
					// TODO: remove debug
					if ( gridBlock[0][0] < 20000 || gridBlock[0][1] < 20000 )
						return;
					*/

                    final N5Writer workerWriter = N5Util.createN5Writer(options.n5PathInput);
					final int minIntensity = 0;
					final int maxIntensity = 255;

					final FinalInterval gridBlockInterval =
							Intervals.createMinSize(
									gridBlock[0][0], gridBlock[0][1], gridBlock[0][2],
									gridBlock[1][0], gridBlock[1][1], gridBlock[1][2]);

					System.out.println( net.imglib2.util.Util.printInterval( gridBlockInterval ) );

					final N5Reader n5 = N5Util.createN5Reader(options.n5PathInput);
					
					final RealRandomAccessible< DoubleType > maxFieldScaled;

					if ((options.n5FieldMax == null) || (maxFactors == null))
					{
						maxFieldScaled = new FunctionRealRandomAccessible<>(
								2,
								(i,o) -> o.set( maxZ ),
                                DoubleType::new);
					}
					else
					{
						final RandomAccessibleInterval<FloatType> maxField = N5Utils.open(n5, options.n5FieldMax);
						maxFieldScaled = Transform.scaleAndShiftHeightFieldAndValues(maxField, maxFactors);
					}

					final RandomAccessibleInterval<UnsignedByteType> source = N5Utils.open(n5, options.n5DatasetInput);
					final RandomAccessible<UnsignedByteType> infiniteSource = Views.extendMirrorDouble( source );

					final RandomAccessibleInterval<UnsignedByteType> result = Views.translate( ArrayImgs.unsignedBytes( gridBlockInterval.dimensionsAsLongArray() ), gridBlockInterval.minAsLongArray() );
					
					// extended interval for local contrast normalization
					final int blockRadius = 511; //1023

					final long[] min = Intervals.minAsLongArray( Intervals.hyperSlice( gridBlockInterval, 2 ) );
					final long[] max = Intervals.maxAsLongArray( Intervals.hyperSlice( gridBlockInterval, 2 ) );

					min[0] -= blockRadius;
					min[1] -= blockRadius;
					max[0] += blockRadius;
					max[1] += blockRadius;

					final Interval intervalEx = new FinalInterval(min, max);

					// 2d mask that will be reused for each z layer
					final byte[] bArray = new byte[ (int)intervalEx.dimension( 0 ) * (int)intervalEx.dimension( 1 ) ];
					final ByteProcessor bp = new ByteProcessor( (int)intervalEx.dimension( 0 ), (int)intervalEx.dimension( 1 ), bArray );
					final RandomAccessibleInterval<UnsignedByteType> maskImg =
							Views.translate( ArrayImgs.unsignedBytes( bArray, intervalEx.dimensionsAsLongArray() ), intervalEx.minAsLongArray() );

					// 2d FloatProcessor for raw image data that we will re-use for each slice
					final float[] fArray = new float[ (int)intervalEx.dimension( 0 ) * (int)intervalEx.dimension( 1 ) ];
					final FloatProcessor fp = new FloatProcessor( (int)intervalEx.dimension( 0 ), (int)intervalEx.dimension( 1 ), fArray );
					final RandomAccessibleInterval<FloatType> img =
							Views.translate( ArrayImgs.floats( fArray, intervalEx.dimensionsAsLongArray() ), intervalEx.minAsLongArray() );

					/*
					// TODO: remove debug
					ImageStack stackMask = new ImageStack();
					ImageStack stackImg = new ImageStack();
					ImageStack stackCLAHE = new ImageStack();
					*/

					for ( long z = gridBlockInterval.min( 2 ); z <= gridBlockInterval.max( 2 ); ++z )
					{
						// assemble mask for this z-layer
						final Cursor<UnsignedByteType> c = Views.flatIterable( maskImg ).localizingCursor();
						final RealRandomAccess<DoubleType> rra = maxFieldScaled.realRandomAccess();

						boolean all0 = true; // all mask values are 0
						boolean all255 = true; // all mask values are 255

						while ( c.hasNext() )
						{
							final UnsignedByteType value = c.next();

							rra.setPosition( c );

                            // if the heightfield returns a value that is bigger than the current z we are inside
							if ( rra.get().get() > z )
							{
								value.set( 255 );
								all0 = false;
							}
							else
							{
								value.set( 0 );
								all255 = false;
							}
						}

						// get the input image
						final Cursor<UnsignedByteType> cImg = Views.flatIterable( Views.interval( Views.hyperSlice( infiniteSource, 2, z ), intervalEx ) ).cursor();
						for ( final FloatType t : Views.flatIterable( img ) )
							t.set( cImg.next().getRealFloat() );

						fp.setMinAndMax( minIntensity, maxIntensity );

						/*
						// TODO: remove debug
						stackImg.addSlice( fp.duplicate() );
						 */

						if ( all255 )
						{
							//System.out.println( "no mask");
							Flat.getFastInstance().run(new ImagePlus("", fp), blockRadius, 256, 7f, null, false);
						}
						else if ( ! all0 )
						{
							//System.out.println( "with mask");
							Flat.getFastInstance().run(new ImagePlus("", fp), blockRadius, 256, 7f, bp, false);
							//Flat.getInstance().run(new ImagePlus("", fp), blockRadius, 256, 10f, bp, false);

						} // else all0: do nothing

						// copy result into the final img for saving
						final RandomAccessibleInterval<UnsignedByteType> resultSlice = Views.hyperSlice( result, 2, z );
						final Cursor<UnsignedByteType> resultCursor = Views.flatIterable( resultSlice ).localizingCursor();
						final RandomAccess<FloatType> claheRA = img.randomAccess();

						while( resultCursor.hasNext() )
						{
							final UnsignedByteType v = resultCursor.next();
							claheRA.setPosition( resultCursor );
							v.set( Math.min( maxIntensity, Math.max(minIntensity, Math.round( claheRA.get().get() ) ) ) );
						}

						/*
						// TODO: remove debug
						//System.out.println( z + ": " + all0 + ", " + all255 );
						stackMask.addSlice( bp.duplicate() );
						stackCLAHE.addSlice( fp.duplicate() );
						*/
					}

					/*
					// TODO: remove debug
					new ImagePlus("stackMask", stackMask).show();
					new ImagePlus("stackImg", stackImg).show();
					new ImagePlus("stackCLAHE", stackCLAHE).show();

					ImageJFunctions.wrapUnsignedByte( Views.interval(source, gridBlockInterval), "src" ).duplicate().show();
					ImageJFunctions.wrapUnsignedByte( result, "result" ).duplicate().show();
					SimpleMultiThreading.threadHaltUnClean();
					*/

					N5Utils.saveNonEmptyBlock(
							  result,
							  workerWriter,
                              options.n5DatasetOutput,
							  new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new ZstandardCompression()),
							  gridBlock[2],
							  new UnsignedByteType());
				});

	}
	
	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final SparkMaskedCLAHEMultiSEM.Options options = new SparkMaskedCLAHEMultiSEM.Options(args);
		if (! options.parsedSuccessfully) {
			throw new IllegalArgumentException("Options were not parsed successfully");
		}

		options.n5DatasetInput = options.n5DatasetInput.trim();
		options.n5DatasetOutput = options.n5DatasetOutput.trim();

		final SparkConf conf = new SparkConf().setAppName("SparkMaskedCLAHEMultiSEM");
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);
		sparkContext.setLogLevel("ERROR");

		process( sparkContext, options );

		sparkContext.close();
	}

    public static final String FACTORS_KEY = "downsamplingFactors";

}
