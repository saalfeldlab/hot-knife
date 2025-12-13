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

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.cache.img.CachedCellImg;
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

        flattenVolume(sc, buildFlatteningInfo());

        sc.close();

        return null;
    }

    public static void flattenVolume(final JavaSparkContext sc,
                                     final FlatteningInfo flatInfo) {

        final N5PathAndDataset rawPathAndDataset = flatInfo.getRawPathAndDataset();
        final N5Path fieldPath = flatInfo.getFieldPath();
        final N5PathAndDataset flatPathAndDataset = flatInfo.getFlatPathAndDataset();

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

        /* grid block size for parallelization to minimize double loading of blocks */
        final int[] rawBlockSize = flatInfo.getRawBlockSize();
        final int[] flatBlockSize = flatInfo.getFlatBlockSize();
        final int[] gridBlockSize = new int[flatBlockSize.length];
        Arrays.setAll(gridBlockSize, i -> Math.max(rawBlockSize[i], flatBlockSize[i]));

        final JavaRDD<long[][]> rdd =
                sc.parallelize(
                        Grid.create(
                                flatInfo.getDimensions(),
                                gridBlockSize,
                                flatBlockSize));

        // TODO: make sure no longer need to call N5Utils.open(setupRawReader, rawDataset); to prime attributes
        rdd.foreach(
                gridBlock -> {
                    final N5Reader n5RawReader = rawPathAndDataset.openReader();
                    final N5Reader n5FieldReader = fieldPath.openReader();
                    final N5Writer n5Writer = flatPathAndDataset.openWriter();

                    /* raw */
                    final CachedCellImg<UnsignedByteType, ?> rawCellImg = N5Utils.open(n5RawReader, rawPathAndDataset.getDataset());
                    final RandomAccessibleInterval<UnsignedByteType> rawVolume =
                            flatInfo.isMultiSEMData() ? rawCellImg : Views.permute(rawCellImg, 1, 2);

                    final RandomAccessibleInterval<FloatType> minField = N5Utils.open(n5FieldReader, flatInfo.getMinFieldDataset());
                    final RandomAccessibleInterval<FloatType> maxField = N5Utils.open(n5FieldReader, flatInfo.getMaxFieldDataset());

                    final RealRandomAccessible<DoubleType> minFactors = Transform.scaleAndShiftHeightFieldAndValues(minField, flatInfo.getFactors());
                    final RealRandomAccessible<DoubleType> maxFactors = Transform.scaleAndShiftHeightFieldAndValues(maxField, flatInfo.getFactors());

                    final FlattenTransform<DoubleType> flattenTransform = new FlattenTransform<>(minFactors,
                                                                                                 maxFactors,
                                                                                                 flatInfo.getMin(),
                                                                                                 flatInfo.getMax());
                    final RandomAccessibleInterval<UnsignedByteType> flattened =
                            Views.zeroMin(
                                    Transform.createTransformedInterval(
                                            rawVolume,
                                            new FinalInterval(
                                                    new long[] {rawVolume.min(0), rawVolume.min(1), flatInfo.getMinWithPadding()},
                                                    new long[] {rawVolume.max(0), rawVolume.max(1), flatInfo.getMaxWithPadding()}),
                                            flattenTransform.inverse(),
                                            new UnsignedByteType()));

                    final RandomAccessibleInterval<UnsignedByteType> sourceGridBlock = Views.offsetInterval(flattened, gridBlock[0], gridBlock[1]);
                    N5Utils.saveBlock(sourceGridBlock, n5Writer, flatPathAndDataset.getDataset(), gridBlock[2]);
                });
    }

    public static void main(final String... args) {
        CommandLine.call(new SparkExportFlattenedVolume(), args);
    }
}
