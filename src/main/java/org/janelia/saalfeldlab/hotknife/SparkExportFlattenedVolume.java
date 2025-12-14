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

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.Callable;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.FlatteningInfo;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.N5Path;
import org.janelia.saalfeldlab.hotknife.util.N5PathAndDataset;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import ij.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@SuppressWarnings("FieldMayBeFinal")
public class SparkExportFlattenedVolume implements Callable<Void>, Serializable {

	@Option(names = {"--n5RawPath"}, required = true, description = "N5 raw input path, e.g. /nrs/flyem/tmp/VNC.n5")
	private String n5RawInputPath = null;

	@Option(names = {"--n5FieldPath"}, required = true, description = "N5 height field input path, e.g. /nrs/flyem/tmp/VNC.n5")
	private String n5FieldPath = null;

	@Option(names = {"--n5OutputPath"}, required = true, description = "N5 output path, e.g. /nrs/flyem/tmp/VNC.n5")
	private String n5OutPath = null;

	@Option(names = {"--n5RawDataset"}, required = true, description = "N5 raw input dataset, e.g. /raw/s0")
	private String rawDataset = null;

	@Option(names = {"--n5FieldGroup"}, required = true, description = "N5 fields input group, e.g. /heightfields/slab-01/s1")
	private String fieldGroup = null;

	@Option(names = {"--n5OutDataset"}, required = true, description = "N5 output dataset, e.g. /flattened/slab-01")
	private String outDataset = null;

	@Option(names = {"--padding"}, description = "padding beyond flattening field min and max in px, e.g. 20")
	private int padding = 0;

	@Option(names = "--blockSize", split=",", description = "Size of output blocks, e.g. 128,128,128")
	private int[] blockSize = new int[] {128, 128, 128};

	@Option(names = {"--multiSem"}, description = "FIB-SEM datasets needed to be permuted, Multi-Sem once not, plus some more parameters are different")
	private boolean multiSem = false;

	@Option(names = {"--debugMode"}, description = "enable debug mode to process only specific blocks")
	private boolean debugMode = false;

	@Option(names = {"--debugBlockX"}, description = "X coordinate of block to process in debug mode")
	private Long debugBlockX = null;

	@Option(names = {"--debugBlockY"}, description = "Y coordinate of block to process in debug mode")
	private Long debugBlockY = null;

    private FlatteningInfo buildFlatteningInfo()
            throws IOException {

        final N5PathAndDataset clahePathAndDataset = new N5PathAndDataset(n5RawInputPath, rawDataset);
        final N5PathAndDataset heightfieldPathAndDataset = new N5PathAndDataset(n5FieldPath, fieldGroup);
        final N5PathAndDataset flatPathAndDataset = new N5PathAndDataset(n5OutPath, outDataset);

        return new FlatteningInfo(clahePathAndDataset,
                                  heightfieldPathAndDataset,
                                  multiSem,
                                  padding,
                                  flatPathAndDataset,
                                  blockSize);
    }

    @Override
    public Void call() throws IOException {

        final SparkConf conf = new SparkConf().setAppName(getClass().getCanonicalName());
        final JavaSparkContext sc = new JavaSparkContext(conf);
        sc.setLogLevel("ERROR");

        flattenVolume(sc, buildFlatteningInfo(), debugMode, debugBlockX, debugBlockY);

        sc.close();

        return null;
    }

    public static void flattenVolume(final JavaSparkContext sc,
                                     final FlatteningInfo flatInfo,
                                     final boolean debugMode,
                                     final Long debugBlockX,
                                     final Long debugBlockY) {

        final N5PathAndDataset rawPathAndDataset = flatInfo.getRawPathAndDataset();
        final N5Path fieldPath = flatInfo.getFieldPath();
        final N5PathAndDataset flatPathAndDataset = flatInfo.getFlatPathAndDataset();

        // Skip N5Writer creation in debug mode
        if (debugMode)
        {
            System.out.println("Debug mode: Skipping N5Writer creation for output");
            System.out.println("Debug mode: " + flatPathAndDataset.getDataset());
            System.out.println("Debug mode: " + Arrays.toString( flatInfo.getDimensions()) );
            System.out.println("Debug mode: " + Arrays.toString( flatInfo.getRawBlockSize() ));
            System.out.println("Debug mode: " + flatInfo.getRawDataType());
            //System.exit( 0 );
        } else {
            try (N5Writer n5Writer = flatPathAndDataset.openWriter()) {
                final Compression compression = flatInfo.getCompression();
                n5Writer.createDataset(flatPathAndDataset.getDataset(),
                                       flatInfo.getDimensions(),
                                       flatInfo.getRawBlockSize(),
                                       flatInfo.getRawDataType(),
                                       compression);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        /* grid block size for parallelization to minimize double loading of blocks */
        final int[] rawBlockSize = flatInfo.getRawBlockSize();
        final int[] flatBlockSize = flatInfo.getFlatBlockSize();
        final int[] gridBlockSize = new int[flatBlockSize.length];
        Arrays.setAll(gridBlockSize, i -> Math.max(rawBlockSize[i], flatBlockSize[i]));

        // Create grid blocks
        final java.util.List<long[][]> gridBlocks = Grid.create(
                flatInfo.getDimensions(),
                gridBlockSize,
                flatBlockSize);

        // Debug mode: filter to process only specific block
        if (debugMode) {
            System.out.println("Debug mode: === DEBUG MODE ENABLED ===");

            // Calculate grid dimensions
            final long[] dimensions = flatInfo.getDimensions();
            final long gridXSize = (dimensions[0] + gridBlockSize[0] - 1) / gridBlockSize[0];
            final long gridYSize = (dimensions[1] + gridBlockSize[1] - 1) / gridBlockSize[1];

            if (debugBlockX != null && debugBlockY != null) {
                System.out.println("Debug mode: Processing single block: [" + debugBlockX + ", " + debugBlockY + "]");
                // Filter to only the specified block
                gridBlocks.removeIf(block -> block[2][0] != debugBlockX || block[2][1] != debugBlockY);
            } else {
                // Default: process middle block
                long midX = gridXSize / 2;
                long midY = gridYSize / 2;
                System.out.println("Debug mode: No specific debug blocks specified, processing middle block: [" + midX + ", " + midY + "]");
                gridBlocks.removeIf(block -> block[2][0] != midX || block[2][1] != midY);
            }

            System.out.println("Debug mode: Processing " + gridBlocks.size() + " blocks");
        }

        final JavaRDD<long[][]> rdd = sc.parallelize(gridBlocks);

        // Initialize ImageJ if in debug mode
        if (debugMode) {
            System.out.println("Debug mode: Initializing ImageJ for visualization...");
            new ImageJ();
        }

        // TODO: make sure no longer need to call N5Utils.open(setupRawReader, rawDataset); to prime attributes
        rdd.foreach(
                gridBlock -> {
                    System.out.println("Processing grid block: [" + gridBlock[2][0] + ", " + gridBlock[2][1] + "]");

                    final N5Reader n5RawReader = rawPathAndDataset.openReader();
                    final N5Reader n5FieldReader = fieldPath.openReader();

                    /* raw */
                    final CachedCellImg<UnsignedByteType, ?> rawCellImg = N5Utils.open(n5RawReader, rawPathAndDataset.getDataset());
                    final RandomAccessibleInterval<UnsignedByteType> rawVolume =
                            flatInfo.isMultiSEMData() ? rawCellImg : Views.permute(rawCellImg, 1, 2);

                    System.out.println("Debug mode: rawVolume dimensions: " + net.imglib2.util.Util.printInterval(rawVolume));

                    final RandomAccessibleInterval<FloatType> minField = N5Utils.open(n5FieldReader, flatInfo.getMinFieldDataset());
                    final RandomAccessibleInterval<FloatType> maxField = N5Utils.open(n5FieldReader, flatInfo.getMaxFieldDataset());

                    System.out.println("Debug mode: minField dimensions: " + net.imglib2.util.Util.printInterval(minField));
                    System.out.println("Debug mode: maxField dimensions: " + net.imglib2.util.Util.printInterval(maxField));

                    final RealRandomAccessible<DoubleType> minFactors = Transform.scaleAndShiftHeightFieldAndValues(minField, flatInfo.getFactors());
                    final RealRandomAccessible<DoubleType> maxFactors = Transform.scaleAndShiftHeightFieldAndValues(maxField, flatInfo.getFactors());

                    final FlattenTransform<DoubleType> flattenTransform = new FlattenTransform<>(minFactors,
                                                                                                 maxFactors,
                                                                                                 flatInfo.getMin(),
                                                                                                 flatInfo.getMax());

                    System.out.println("Debug mode: Creating flattened transform with min=" + flatInfo.getMin() + ", max=" + flatInfo.getMax());
                    System.out.println("Debug mode: Padding: minWithPadding=" + flatInfo.getMinWithPadding() + ", maxWithPadding=" + flatInfo.getMaxWithPadding());

                    final RandomAccessibleInterval<UnsignedByteType> flattened =
                            Views.zeroMin(
                                    Transform.createTransformedInterval(
                                            rawVolume,
                                            new FinalInterval(
                                                    new long[] {rawVolume.min(0), rawVolume.min(1), flatInfo.getMinWithPadding()},
                                                    new long[] {rawVolume.max(0), rawVolume.max(1), flatInfo.getMaxWithPadding()}),
                                            flattenTransform.inverse(),
                                            new UnsignedByteType()));

                    System.out.println("Debug mode: flattened dimensions: " + net.imglib2.util.Util.printInterval(flattened));

                    final RandomAccessibleInterval<UnsignedByteType> sourceGridBlock = Views.offsetInterval(flattened, gridBlock[0], gridBlock[1]);

                    System.out.println("Debug mode: sourceGridBlock dimensions: " + net.imglib2.util.Util.printInterval(sourceGridBlock));

                    // In debug mode, show results and stop without writing
                    if (debugMode) {
                    	long[] min = new long[2];
                    	long[] max = new long[ min.length ];
                    	for ( int d = 0; d < min.length; ++d )
                    	{
                    		min[ d ] = gridBlock[0][d] / 2;
                    		max[ d ] = min[ d ] + gridBlock[1][d]/2 - 1;
                    	}

                        System.out.println("Debug mode: Displaying results... for interval " + Arrays.toString( min ) + " >> " + Arrays.toString( max ));
                        ImageJFunctions.show( Views.interval( minField, min, max ), "Min Height Field");
                        ImageJFunctions.show( Views.interval( maxField, min, max ), "Max Height Field");
                        ImageJFunctions.show(sourceGridBlock, "Flattened Block [" + gridBlock[2][0] + "," + gridBlock[2][1] + "]");
                        System.out.println("Debug mode: Skipping N5 write operations");
                        SimpleMultiThreading.threadHaltUnClean();
                        return;
                    }

                    final N5Writer n5Writer = flatPathAndDataset.openWriter();
                    N5Utils.saveBlock(sourceGridBlock, n5Writer, flatPathAndDataset.getDataset(), gridBlock[2]);
                });
    }

    public static void main(final String... args) {
        CommandLine.call(new SparkExportFlattenedVolume(), args);
    }
}
