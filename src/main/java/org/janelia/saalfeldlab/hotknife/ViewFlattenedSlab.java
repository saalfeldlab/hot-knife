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
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ViewFlattenedSlab {

	private double botY = 1189.986083984375;
	private double topY = 4233.8876953125;

	private boolean useVolatile = true;

	FinalVoxelDimensions voxelDimensions = new FinalVoxelDimensions("px", new double[]{1, 1, 1});


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		new ViewFlattenedSlab().run();
	}


	public final void run() throws IOException, InterruptedException, ExecutionException {

		/*
		 * transformation
		 */
		final IHDF5Reader hdf5ReaderTop = HDF5Factory.openForReading("/nrs/flyem/alignment/Z1217-19m/VNC/Sec04/Sec04-top.h5");
		final IHDF5Reader hdf5ReaderBot = HDF5Factory.openForReading("/nrs/flyem/alignment/Z1217-19m/VNC/Sec04/Sec04-bottom.h5");
//		final IHDF5Reader hdf5ReaderTop = HDF5Factory.openForReading("/home/saalfeld/projects/flyem/Sec04-top.h5");
//		final IHDF5Reader hdf5ReaderBot = HDF5Factory.openForReading("/home/saalfeld/projects/flyem/Sec04-bottom.h5");
		final N5HDF5Reader hdf5Top = new N5HDF5Reader(hdf5ReaderTop, new int[] {128, 128, 128});
		final N5HDF5Reader hdf5Bot = new N5HDF5Reader(hdf5ReaderBot, new int[] {128, 128, 128});

		System.out.println(Arrays.toString(hdf5Top.listAttributes("/").keySet().toArray()));
		System.out.println(hdf5Top.getDatasetAttributes("/volume").getDataType());

		final RandomAccessibleInterval<FloatType> topFloats = N5Utils.openVolatile(hdf5Top, "/volume");
		final RandomAccessibleInterval<FloatType> botFloats = N5Utils.openVolatile(hdf5Bot, "/volume");

		final RandomAccessibleInterval<DoubleType> top = Converters.convert(topFloats, (a, b) -> b.setReal(a.getRealDouble()), new DoubleType());
		final RandomAccessibleInterval<DoubleType> bot = Converters.convert(botFloats, (a, b) -> b.setReal(a.getRealDouble()), new DoubleType());

		final FlattenTransform ft = new FlattenTransform(bot, top, botY, topY);


		/*
		 * raw data
		 */
		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.min(8, Math.max(1, numProc - 2)));

		final N5FSReader n5 = new N5FSReader("/nrs/flyem/render/n5/Z1217_19m/Sec04/stacks");

		final String datasetName = "/v1_1_affine_filtered_1_26365___20191217_153959";

		final long[] dimensions = n5.getDatasetAttributes(datasetName + "/s0").getDimensions();

		final FinalInterval cropInterval = new FinalInterval(
				new long[] {0, 0, Math.round(botY) - 20},
				new long[] {dimensions[0] - 1, dimensions[2] - 1, Math.round(topY) + 20});

		final int numScales = n5.list(datasetName).length;

		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = new RandomAccessibleInterval[numScales];
		final double[][] scales = new double[numScales][];

		BdvStackSource<?> bdv = null;

		for (int s = 0; s < numScales; ++s) {

			/* TODO read downsamplingFactors */
			final int scale = 1 << s;
			final double inverseScale = 1.0 / scale;

			final RandomAccessibleInterval<UnsignedByteType> source =
					N5Utils.openVolatile(
							n5,
							datasetName + "/s" + s);

			final RealTransformSequence transformSequence = new RealTransformSequence();
			final Scale3D scale3D = new Scale3D(inverseScale, inverseScale, inverseScale);
			transformSequence.add(ft.inverse());
			transformSequence.add(scale3D);

			final RandomAccessibleInterval<UnsignedByteType> transformedSource =
					Transform.createTransformedInterval(
							Views.permute(source, 1, 2),
							cropInterval,
							transformSequence,
							new UnsignedByteType(0));

			final SubsampleIntervalView<UnsignedByteType> subsampledTransformedSource = Views.subsample(transformedSource, scale);
			final RandomAccessibleInterval<UnsignedByteType> cachedSource = Show.wrapAsVolatileCachedCellImg(subsampledTransformedSource, new int[]{32, 32, 32});

			mipmaps[s] = cachedSource;
			scales[s] = new double[]{scale, scale, scale};
		}

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
				new RandomAccessibleIntervalMipmapSource<>(
						mipmaps,
						new UnsignedByteType(),
						scales,
						voxelDimensions,
						datasetName);

		final Source<?> volatileMipmapSource;
		if (useVolatile)
			volatileMipmapSource = mipmapSource.asVolatile(queue);
		else
			volatileMipmapSource = mipmapSource;

		bdv = Show.mipmapSource(volatileMipmapSource, bdv, BdvOptions.options().screenScales(new double[] {0.5}).numRenderingThreads(10));

//		BdvFunctions.show(VolatileViews.wrapAsVolatile(topFloats, queue), "", BdvOptions.options().is2D());


//		BdvFunctions.show(
//				VolatileViews.wrapAsVolatile(
//						imgXZY,
//						queue),
//				"img",
//				BdvOptions.options().addTo(bdv));
	}
}
