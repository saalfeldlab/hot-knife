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

import ij.ImageJ;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
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

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
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

    public enum DebugMode {
        OFF, INTERACTIVE, BATCH
    }

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

	@Option(names = {"--debugMode"}, description = "enable debug mode to process a specific block")
	private DebugMode debugMode = DebugMode.OFF;

	@Option(names = {"--debugBlockX"}, description = "X coordinate of block to process in debug mode")
	private Long debugBlockX = null;

	@Option(names = {"--debugBlockY"}, description = "Y coordinate of block to process in debug mode")
	private Long debugBlockY = null;

    public SparkExportFlattenedVolume() {
    }

    public SparkExportFlattenedVolume(final String n5RawInputPath,
                                      final String n5FieldPath,
                                      final String n5OutPath,
                                      final String rawDataset,
                                      final String fieldGroup,
                                      final String outDataset,
                                      final int padding,
                                      final int[] blockSize,
                                      final boolean multiSem,
                                      final DebugMode debugMode,
                                      final Long debugBlockX,
                                      final Long debugBlockY) {
        this.n5RawInputPath = n5RawInputPath;
        this.n5FieldPath = n5FieldPath;
        this.n5OutPath = n5OutPath;
        this.rawDataset = rawDataset;
        this.fieldGroup = fieldGroup;
        this.outDataset = outDataset;
        this.padding = padding;
        this.blockSize = blockSize;
        this.multiSem = multiSem;
        this.debugMode = debugMode;
        this.debugBlockX = debugBlockX;
        this.debugBlockY = debugBlockY;
    }

    public FlatteningInfo buildFlatteningInfo()
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
    public String toString() {
        final String blockSizeStr = Arrays.toString(blockSize).replaceAll("[\\[\\] ]", "");
        final String pMultiSem = multiSem ? "  --multiSem\n" : "";
        final String pDebug = DebugMode.OFF.equals(debugMode) ? "" : "  --debugMode " + debugMode + "\n";
        final String pDebugBlockX = debugBlockX != null ? "  --debugBlockX " + debugBlockX + "\n" : "";
        final String pDebugBlockY = debugBlockY != null ? "  --debugBlockY " + debugBlockY + "\n" : "";
        return "SparkExportFlattenedVolume with parameters:\n" +
               "  --n5RawPath \"" + n5RawInputPath + "\"\n" +
               "  --n5RawDataset \"" + rawDataset + "\"\n" +
               "  --n5FieldPath \"" + n5FieldPath + "\"\n" +
               "  --n5FieldGroup \"" + fieldGroup + "\"\n" +
               "  --n5OutputPath \"" + n5OutPath + "\"\n" +
               "  --n5OutDataset \"" + outDataset + "\"\n" +
               "  --padding " + padding + "\n" +
               "  --blockSize " + blockSizeStr + "\n" +
               pMultiSem + pDebug + pDebugBlockX + pDebugBlockY;
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
                                     final DebugMode debugMode,
                                     final Long debugBlockX,
                                     final Long debugBlockY) {

        System.out.println("SparkExportFlattenedVolume: entry, flatInfo=" + flatInfo +
                           ", debugMode=" + debugMode + ", debugBlockX=" + debugBlockX + ", debugBlockY=" + debugBlockY);

        final N5PathAndDataset rawPathAndDataset = flatInfo.getRawPathAndDataset();
        final N5Path fieldPath = flatInfo.getFieldPath();
        final N5PathAndDataset flatPathAndDataset = flatInfo.getFlatPathAndDataset();

        // Skip N5Writer creation in debug mode
        if (DebugMode.INTERACTIVE.equals(debugMode)) {
            System.out.println("Debug mode: Skipping N5Writer creation for output");
        } else {
            try (N5Writer n5Writer = flatPathAndDataset.openWriter()) {
                final Compression compression = flatInfo.getCompression();
                n5Writer.createDataset(flatPathAndDataset.getDataset(),
                                       flatInfo.getDimensions(),
                                       flatInfo.getFlatBlockSize(),
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

        System.out.println( "flatInfo.getDimensions(): " + Arrays.toString( flatInfo.getDimensions()) );
        System.out.println( "rawBlockSize: " + Arrays.toString( rawBlockSize) ); //rawBlockSize: [1024, 1024, 82]
        System.out.println( "flatBlockSize: " + Arrays.toString( flatBlockSize) ); //flatBlockSize: [128, 128, 128]
        System.out.println( "gridBlockSize: " + Arrays.toString( gridBlockSize) ); //gridBlockSize: [1024, 1024, 128]

        // Create grid blocks
        final java.util.List<long[][]> gridBlocks = Grid.create(
                flatInfo.getDimensions(),
                gridBlockSize,
                flatBlockSize);

        System.out.println( "gridBlocks: " + gridBlocks.size() );//gridBlocks: 15080

        // Debug mode: filter to process only specific block
        if (DebugMode.INTERACTIVE.equals(debugMode) || DebugMode.BATCH.equals(debugMode)) {

            // Calculate grid dimensions based on flatBlockSize (outBlockSize), not gridBlockSize
            final long[] dimensions = flatInfo.getDimensions();
            final long gridXSize = (dimensions[0] + flatBlockSize[0] - 1) / flatBlockSize[0];
            final long gridYSize = (dimensions[1] + flatBlockSize[1] - 1) / flatBlockSize[1];

            System.out.println("Debug mode: gridXSize: " + gridXSize );
            System.out.println("Debug mode: gridYSize: " + gridYSize );

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
                System.out.println("Debug mode: location: " + Arrays.deepToString( gridBlocks.get(0) ));
            }

            System.out.println("Debug mode: Processing " + gridBlocks.size() + " blocks");
        }

        final JavaRDD<long[][]> rdd = sc.parallelize(gridBlocks);

        // Initialize ImageJ if in debug mode
        if (DebugMode.INTERACTIVE.equals(debugMode)) {
            System.out.println("Debug mode: Initializing ImageJ for visualization...");
            new ImageJ();
        }

        rdd.foreachPartition(
                gridBlockIterator -> processPartition(gridBlockIterator, flatInfo, rawPathAndDataset, fieldPath, flatPathAndDataset, debugMode));
    }

    private static void processPartition(final Iterator<long[][]> gridBlockIterator,
                                         final FlatteningInfo flatInfo,
                                         final N5PathAndDataset rawPathAndDataset,
                                         final N5Path fieldPath,
                                         final N5PathAndDataset flatPathAndDataset,
                                         final DebugMode debugMode) {

        if (!gridBlockIterator.hasNext())
            return;

        // Open N5 connections once per partition
        final N5Reader n5RawReader = rawPathAndDataset.openReader();
        final N5Reader n5FieldReader = fieldPath.openReader();

        // Open datasets (share cache across blocks in this partition)
        final RandomAccessibleInterval<UnsignedByteType> rawData = N5Utils.open(n5RawReader, rawPathAndDataset.getDataset());
        final RandomAccessibleInterval<UnsignedByteType> rawVolume =
                flatInfo.isMultiSEMData() ? rawData : Views.permute(rawData, 1, 2);

        final RandomAccessibleInterval<FloatType> minField = N5Utils.open(n5FieldReader, flatInfo.getMinFieldDataset());
        final RandomAccessibleInterval<FloatType> maxField = N5Utils.open(n5FieldReader, flatInfo.getMaxFieldDataset());

        System.out.println("Partition: rawVolume dimensions: " + net.imglib2.util.Util.printInterval(rawVolume));
        System.out.println("Partition: minField dimensions: " + net.imglib2.util.Util.printInterval(minField));
        System.out.println("Partition: maxField dimensions: " + net.imglib2.util.Util.printInterval(maxField));

        // Set up transform
        final RealRandomAccessible<DoubleType> minFactors = Transform.scaleAndShiftHeightFieldAndValues(minField, flatInfo.getFactors());
        final RealRandomAccessible<DoubleType> maxFactors = Transform.scaleAndShiftHeightFieldAndValues(maxField, flatInfo.getFactors());

        final FlattenTransform<DoubleType> flattenTransform = new FlattenTransform<>(minFactors, maxFactors, flatInfo.getMin(), flatInfo.getMax());

        System.out.println("Partition: FlattenTransform with min=" + flatInfo.getMin() + ", max=" + flatInfo.getMax() +
                           ", minWithPadding=" + flatInfo.getMinWithPadding() + ", maxWithPadding=" + flatInfo.getMaxWithPadding());

        final RandomAccessibleInterval<UnsignedByteType> flattened =
                Views.zeroMin(
                        Transform.createTransformedInterval(
                                rawVolume,
                                new FinalInterval(
                                        new long[] {rawVolume.min(0), rawVolume.min(1), flatInfo.getMinWithPadding()},
                                        new long[] {rawVolume.max(0), rawVolume.max(1), flatInfo.getMaxWithPadding()}),
                                flattenTransform.inverse(),
                                new UnsignedByteType()));

        System.out.println("Partition: flattened dimensions: " + net.imglib2.util.Util.printInterval(flattened));

        // Only open writer if we're going to write
        final N5Writer n5Writer = DebugMode.INTERACTIVE.equals(debugMode) ? null : flatPathAndDataset.openWriter();

        while (gridBlockIterator.hasNext()) {
            final long[][] gridBlock = gridBlockIterator.next();
            System.out.println("Processing grid block: [" + gridBlock[2][0] + ", " + gridBlock[2][1] + "]");

            final RandomAccessibleInterval<UnsignedByteType> sourceGridBlock = Views.offsetInterval(flattened, gridBlock[0], gridBlock[1]);

            if (DebugMode.INTERACTIVE.equals(debugMode)) {
                long[] min = new long[2];
                long[] max = new long[min.length];
                for (int d = 0; d < min.length; ++d) {
                    min[d] = gridBlock[0][d] / 2;
                    max[d] = min[d] + gridBlock[1][d] / 2 - 1;
                }

                System.out.println("Debug mode: Displaying results... for interval " + Arrays.toString(min) + " >> " + Arrays.toString(max));
                ImageJFunctions.show(Views.interval(minField, min, max), "Min Height Field");
                ImageJFunctions.show(Views.interval(maxField, min, max), "Max Height Field");
                ImageJFunctions.show(sourceGridBlock, "Flattened Block [" + gridBlock[2][0] + "," + gridBlock[2][1] + "]");
                System.out.println("Debug mode: Skipping N5 write operations");
                //noinspection deprecation
                SimpleMultiThreading.threadHaltUnClean();
                return;
            }

            // Copy from lazy view into concrete block using z-first iteration order.
            // This maximizes cache hits in FlattenTransform's height field cache:
            // for each (x,y) column, all z values reuse the same cached height field lookup.
            final RandomAccessibleInterval<UnsignedByteType> blockImg = ArrayImgs.unsignedBytes(gridBlock[1]);
            final RandomAccess<UnsignedByteType> src = sourceGridBlock.randomAccess();
            final RandomAccess<UnsignedByteType> dst = blockImg.randomAccess();

            final int sizeX = (int) gridBlock[1][0];
            final int sizeY = (int) gridBlock[1][1];
            final int sizeZ = (int) gridBlock[1][2];

            for (int y = 0; y < sizeY; y++) {
                src.setPosition(y, 1);
                dst.setPosition(y, 1);
                for (int x = 0; x < sizeX; x++) {
                    src.setPosition(x, 0);
                    dst.setPosition(x, 0);
                    for (int z = 0; z < sizeZ; z++) {
                        src.setPosition(z, 2);
                        dst.setPosition(z, 2);
                        dst.get().set(src.get());
                    }
                }
            }

            N5Utils.saveBlock(blockImg, n5Writer, flatPathAndDataset.getDataset(), gridBlock[2]);
        }
    }

    public static void main(final String... args) {
        final CommandLine cmd = new CommandLine(new SparkExportFlattenedVolume());
        cmd.execute(args);
    }
}
