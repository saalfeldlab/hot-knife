/**
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
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
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

	@Option(names = {"--padding"}, required = false, description = "padding beyond flattening field min and max in px, e.g. 20")
	private int padding = 0;

	@Option(names = "--blockSize", required = false, split=",", description = "Size of output blocks, e.g. 128,128,128")
	private int[] blockSize = new int[] {128, 128, 128};

	@Override
	public Void call() throws IOException {

		final SparkConf conf = new SparkConf().setAppName(getClass().getCanonicalName());
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		final int[] rawBlockSize;
		final long[] dimensions;
		final String minFieldName;
		final String maxFieldName;
		final double[] downsamplingFactors;
		final double min;
		final double max;
		{
			final N5Reader n5RawReader = new N5FSReader(n5RawInputPath);
			final N5Reader n5FieldReader = new N5FSReader(n5FieldPath);

			minFieldName = fieldGroup + "/min";
			maxFieldName = fieldGroup + "/max";

			downsamplingFactors = n5FieldReader.getAttribute(fieldGroup, "downsamplingFactors", double[].class);
			final double minAvg = n5FieldReader.getAttribute(minFieldName, "avg", double.class);
			final double maxAvg = n5FieldReader.getAttribute(maxFieldName, "avg", double.class);

			min = (minAvg + 0.5) * downsamplingFactors[2] - 0.5;
			max = (maxAvg + 0.5) * downsamplingFactors[2] - 0.5;

			final DatasetAttributes attributes = n5RawReader.getDatasetAttributes(rawDataset);
			rawBlockSize = attributes.getBlockSize();
			final long[] rawDimensions = attributes.getDimensions();

			dimensions = new long[] {
					rawDimensions[0],
					rawDimensions[2],
					Math.round(max + padding) - Math.round(min - padding)
			};

			final N5Writer n5Writer = new N5FSWriter(n5OutPath);
			n5Writer.createDataset(
					outDataset,
					dimensions,
					blockSize,
					attributes.getDataType(),
					attributes.getCompression());
		}

		/* grid block size for parallelization to minimize double loading of blocks */
		final int[] gridBlockSize = new int[blockSize.length];
		Arrays.setAll(gridBlockSize, i -> Math.max(rawBlockSize[i], blockSize[i]));

		final JavaRDD<long[][]> rdd =
				sc.parallelize(
						Grid.create(
								dimensions,
								gridBlockSize,
								blockSize));

		rdd.foreach(
				gridBlock -> {
					final N5Reader n5RawReader = new N5FSReader(n5RawInputPath);
					final N5Reader n5FieldReader = new N5FSReader(n5FieldPath);
					final N5Writer n5Writer = new N5FSWriter(n5OutPath);

					/* raw */
					@SuppressWarnings("unchecked")
					final RandomAccessibleInterval<UnsignedByteType> rawVolume =
							Views.permute(
									(RandomAccessibleInterval<UnsignedByteType>)N5Utils.open(n5RawReader, rawDataset),
									1,
									2);

					final RandomAccessibleInterval<FloatType> minField = N5Utils.open(n5FieldReader, minFieldName);
					final RandomAccessibleInterval<FloatType> maxField = N5Utils.open(n5FieldReader, maxFieldName);

					final FlattenTransform<DoubleType> flattenTransform = new FlattenTransform<>(
							Transform.scaleAndShiftHeightFieldAndValues(minField, downsamplingFactors),
							Transform.scaleAndShiftHeightFieldAndValues(maxField, downsamplingFactors),
							min,
							max);

					final RandomAccessibleInterval<UnsignedByteType> flattened =
							Views.zeroMin(
									Transform.createTransformedInterval(
										rawVolume,
										new FinalInterval(
												new long[] {rawVolume.min(0), rawVolume.min(1), (int)Math.round(min - padding)},
												new long[] {rawVolume.max(0), rawVolume.max(1), (int)Math.round(max + padding)}),
										flattenTransform.inverse(),
										new UnsignedByteType()));

					final RandomAccessibleInterval<UnsignedByteType> sourceGridBlock = Views.offsetInterval(flattened, gridBlock[0], gridBlock[1]);
					N5Utils.saveBlock(sourceGridBlock, n5Writer, outDataset, gridBlock[2]);
				});

		sc.close();

		return null;
	}

	public static final void main(final String... args) {

		CommandLine.call(new SparkExportFlattenedVolume(), args);
	}
}
