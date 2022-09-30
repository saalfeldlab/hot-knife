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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import bdv.img.h5.H5Utils;
import bdv.img.hdf5.Util;
import ch.systemsx.cisd.base.mdarray.MDDoubleArray;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.HDF5FloatStorageFeatures;
import ch.systemsx.cisd.hdf5.IHDF5DoubleWriter;
import ch.systemsx.cisd.hdf5.IHDF5Writer;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ExportSlabSeriesTransformsH5 {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "-t", aliases = {"--top"}, required = true, usage = "top slab face offset")
		private List<Long> topOffsets = new ArrayList<>();

		@Option(name = "-b", aliases = {"--bot"}, required = true, usage = "top slab face offset")
		private List<Long> botOffsets = new ArrayList<>();

		@Option(name = "-j", aliases = {"--n5Group"}, required = true, usage = "N5 group containing alignments, e.g. /nrs/flyem/data/tmp/Z0115-22.n5/align-6")
		private String n5GroupAlign;

		@Option(name = "--hdf5Path", required = true, usage = "HDF5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.hdf5")
		private String hdf5Path = null;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				parsedSuccessfully = topOffsets.size() == botOffsets.size();
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		/**
		 * @return the n5Path
		 */
		public String getN5Path() {

			return n5Path;
		}

		/**
		 * @return the top offsets
		 */
		public List<Long> getTopOffsets() {

			return topOffsets;
		}

		/**
		 * @return the bottom offsets (max)
		 */
		public List<Long> getBotOffsets() {

			return botOffsets;
		}

		/**
		 * @return the group
		 */
		public String getGroup() {

			return n5GroupAlign;
		}

		public String getHdf5Path() {

			return hdf5Path;
		}
	}


	/**
	 * Reorder double array representing column-major coordinate (imglib2) to
	 * row-major (hdf5). Permuted in is stored in out and out is returned.
	 *
	 * @param in
	 *            column major coordinates
	 * @param out
	 *            row major coordinates
	 * @return out
	 */
	public static double[] reorder(final double[] in, final double[] out) {

		assert in.length == out.length;
		for (int i = 0, o = in.length - 1; i < in.length; ++i, --o)
			out[o] = in[i];
		return out;
	}

	/**
	 * Reorder double array representing column-major coordinate (imglib2) to
	 * row-major (hdf5).
	 *
	 * @param in
	 *            column major coordinates
	 * @return row major coordinates (new array).
	 */
	public static double[] reorder(final double[] in) {

		return reorder(in, new double[in.length]);
	}

	/**
	 * Save a long value as a uint64 attribute of an HDF5 object.
	 *
	 * @param value
	 * @param writer
	 * @param object
	 * @param attribute
	 */
	static public void saveUint64Attribute(
			final long value,
			final IHDF5Writer writer,
			final String object,
			final String attribute) {

		if (!writer.exists(object))
			writer.object().createGroup(object);

		// TODO Bug in JHDF5, does not save the value most of the time when
		// using the non-deprecated method
		writer.uint64().setAttr( object, attribute, value );
//		writer.setLongAttribute(object, attribute, value);
		// writer.file().flush();
	}

	/**
	 * Save a double value as a float64 attribute of an HDF5 object.
	 *
	 * @param value
	 * @param writer
	 * @param object
	 * @param attribute
	 */
	static public void saveFloat64Attribute(
			final double value,
			final IHDF5Writer writer,
			final String object,
			final String attribute) {

		if (!writer.exists(object))
			writer.object().createGroup(object);

		// TODO Bug in JHDF5, does not save the value most of the time when
		// using the non-deprecated method
		writer.float64().setAttr(object, attribute, value);
//		writer.setDoubleAttribute(object, attribute, value);
		// writer.file().flush();
	}

	/**
	 * Save a double array as a float64[] attribute of an HDF5 object.
	 *
	 * @param values
	 * @param writer
	 * @param object
	 * @param attribute
	 */
	static public void saveAttribute(
			final double[] values,
			final IHDF5Writer writer,
			final String object,
			final String attribute )
	{
		if ( !writer.exists( object ) )
			writer.object().createGroup( object );

		writer.float64().setArrayAttr( object, attribute, values );
	}


	/**
	 * Save a {@link RandomAccessibleInterval} of {@link DoubleType} into an HDF5
	 * float64 dataset.
	 *
	 * @param source source
	 * @param dimensions dimensions of the dataset if created new
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void createDouble(
			final IHDF5Writer writer,
			final String dataset,
			final Dimensions datasetDimensions,
			final int[] cellDimensions) {

		final IHDF5DoubleWriter float64Writer = writer.float64();

		if (writer.exists(dataset)) writer.delete(dataset);

		float64Writer.createMDArray(
				dataset,
				Util.reorder(Intervals.dimensionsAsLongArray(datasetDimensions)),
				Util.reorder(cellDimensions),
				HDF5FloatStorageFeatures.FLOAT_DEFLATE);
	}

	static public void cropCellDimensions(
			final long[] max,
			final long[] offset,
			final int[] cellDimensions,
			final long[] croppedCellDimensions) {

		for (int d = 0; d < max.length; ++d)
			croppedCellDimensions[d] = Math.min(cellDimensions[d], max[d] - offset[d] + 1);
	}

	/**
	 * Save a {@link RandomAccessibleInterval} of {@link DoubleType} into an HDF5
	 * float64 dataset.
	 *
	 * @param source
	 * @param writer
	 * @param dataset
	 * @param cellDimensions
	 */
	static public void saveDouble(
			final RandomAccessibleInterval<DoubleType > source,
			final IHDF5Writer writer,
			final String dataset,
			final int[] cellDimensions) {

		if (!writer.exists(dataset))
			createDouble(writer, dataset, source, cellDimensions);

		final long[] dimensions = Util.reorder(writer.object().getDimensions(dataset));
		final int n = source.numDimensions();

		final IHDF5DoubleWriter uint8Writer = writer.float64();

		/* min is >= 0, max is < dimensions */
		final long[] min = Intervals.minAsLongArray(source);
		final long[] max = Intervals.maxAsLongArray(source);
		for (int d = 0; d < min.length; ++d) {
			min[d] = Math.max(0, min[d]);
			max[d] = Math.min(dimensions[d] - 1, max[d]);
		}

		final long[] offset = min.clone();
		final long[] sourceCellDimensions = new long[n];
		for (int d = 0; d < n;) {
			cropCellDimensions(max, offset, cellDimensions, sourceCellDimensions);
			final RandomAccessibleInterval<DoubleType> sourceBlock = Views.offsetInterval(source, offset, sourceCellDimensions);
			final MDDoubleArray targetCell = new MDDoubleArray(Util.reorder(sourceCellDimensions));
			int i = 0;
			for (final DoubleType t : Views.flatIterable(sourceBlock))
				targetCell.set(t.get(), i++);

			uint8Writer.writeMDArrayBlockWithOffset(dataset, targetCell, Util.reorder(offset));

			for (d = 0; d < n; ++d) {
				offset[d] += cellDimensions[d];
				if (offset[d] <= max[d])
					break;
				else
					offset[d] = min[d];
			}
		}
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		run(
				options.getN5Path(),
				options.getGroup(),
				options.getTopOffsets(),
				options.getBotOffsets(),
				options.getHdf5Path());
	}

	public static void run(
			final String n5Path,
			final String group,
			final List<Long> topOffsets,
			final List<Long> botOffsets,
			final String hdf5Path) throws IOException {

		final N5Reader n5 = new N5FSReader(n5Path);
		final IHDF5Writer hdf5Writer = HDF5Factory.open(hdf5Path);

		final String[] transformDatasetNames = n5.getAttribute(group, "transforms", String[].class);
		final double[] boundsMin = n5.getAttribute(group, "boundsMin", double[].class);
		final double[] boundsMax = n5.getAttribute(group, "boundsMax", double[].class);

		hdf5Writer.object().createGroup(group);
		H5Utils.saveAttribute(boundsMin, hdf5Writer, group, "boundsMin");
		H5Utils.saveAttribute(boundsMax, hdf5Writer, group, "boundsMax");

		for (int i = 0; i < topOffsets.size(); ++i) {

			final long t = System.nanoTime();

			final String topDatasetName = group + "/" + transformDatasetNames[i * 2];
			final String botDatasetName = group + "/" + transformDatasetNames[i * 2 + 1];

			System.out.printf( "Exporting '%s' and '%s' ...", topDatasetName, botDatasetName );

			final RandomAccessibleInterval<DoubleType> topPositionField = N5Utils.open(n5, topDatasetName);
			final RandomAccessibleInterval<DoubleType> botPositionField = N5Utils.open(n5, botDatasetName);

			final DatasetAttributes topAttributes = n5.getDatasetAttributes(topDatasetName);
			final DatasetAttributes botAttributes = n5.getDatasetAttributes(botDatasetName);

			saveDouble(topPositionField, hdf5Writer, topDatasetName, topAttributes.getBlockSize());
			saveDouble(botPositionField, hdf5Writer, botDatasetName, botAttributes.getBlockSize());

			saveUint64Attribute(topOffsets.get(i), hdf5Writer, topDatasetName, "faceOffset");
			saveUint64Attribute(botOffsets.get(i), hdf5Writer, botDatasetName, "faceOffset");

			final double topScale = n5.getAttribute(topDatasetName, "scale", double.class);
			final double botScale = n5.getAttribute(botDatasetName, "scale", double.class);

			saveFloat64Attribute(topScale, hdf5Writer, topDatasetName, "scale");
			saveFloat64Attribute(botScale, hdf5Writer, botDatasetName, "scale");

			System.out.printf( " took %.2fs.", (System.nanoTime() - t) / 1000000000.0 );
			System.out.println();
		}

		hdf5Writer.close();
	}
}
