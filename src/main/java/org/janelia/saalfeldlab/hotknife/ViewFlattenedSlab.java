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

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import net.imglib2.RandomAccess;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
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
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale2D;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ViewFlattenedSlab implements Callable<Void> {

	private long padding = 2000;

	@Option(names = {"-i", "--container"}, required = true, description = "container path, e.g. -i /nrs/flyem/tmp/VNC.n5")
	private String n5Path = "/nrs/flyem/alignment/kyle/nail_test.n5";

	@Option(names = {"-d", "--dataset"}, required = true, description = "Input dataset -d '/zcorr/Sec22___20200106_083252'")
	private String inputDataset = "/volumes/input";

	@Option(names = {"--min"}, required = true, description = "Dataset for the min heightmap -f '/flatten/Sec22___20200110_133809/heightmaps/min' or full path to HDF5")
	private String minDataset = null;

	@Option(names = {"--max"}, required = true, description = "Dataset for the max heightmap -f '/flatten/Sec22___20200110_133809/heightmaps/max' or full path to HDF5")
	private String maxDataset = null;

	private double transformScaleX = 1;
	private double transformScaleY = 1;

	private boolean useVolatile = true;

	FinalVoxelDimensions voxelDimensions = new FinalVoxelDimensions("px", new double[]{1, 1, 1});


	public static final void main(String... args) throws IOException, InterruptedException, ExecutionException {
		if( args.length == 0 )
			args = new String[]{"-i", "/nrs/flyem/tmp/VNC.n5",
					"-d", "/zcorr/Sec22___20200106_083252",
					"--min", "/nrs/flyem/alignment/Z1217-19m/VNC/Sec22/Sec22-bottom.h5",
					"--max", "/nrs/flyem/alignment/Z1217-19m/VNC/Sec22/Sec22-top.h5"
//					"--min", "/flatten/Sec22___20200113_kyle001/heightmaps/min",
//					"--max", "/flatten/Sec22___20200113_kyle001/heightmaps/max"
					};
			//args = new String[]{"-i", "/nrs/flyem/tmp/VNC.n5", "-d", "/zcorr/Sec24___20200106_082231", "-f", "/flatten/Sec24___20200106_082231", "-s", "/cost/Sec23___20200110_152920", "-u"};
		// to regenerate heightmap from HDF5 use these args
		    //args = new String[]{"-i", "/nrs/flyem/tmp/VNC.n5", "-d", "/zcorr/Sec24___20200106_082231", "-f", "/flatten/Sec24___20200106_082231", "--min", "/nrs/flyem/alignment/Z1217-19m/VNC/Sec24/Sec24-bottom.h5", "--max", "/nrs/flyem/alignment/Z1217-19m/VNC/Sec24/Sec24-top.h5"};"--min", "/nrs/flyem/alignment/Z1217-19m/VNC/Sec24/Sec24-bottom.h5", "--max", "/nrs/flyem/alignment/Z1217-19m/VNC/Sec24/Sec24-top.h5"};


		CommandLine.call(new ViewFlattenedSlab(), args);
//		new ViewFlattenedSlab().call();
	}


	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final N5FSReader n5 = new N5FSReader(n5Path);

		// Extract metadata from input
		final int numScales = n5.list(inputDataset).length;
		final long[] dimensions = n5.getDatasetAttributes(inputDataset + "/s0").getDimensions();

		RandomAccessibleInterval<DoubleType> min = null;
		RandomAccessibleInterval<DoubleType> max = null;

		// Min heightmap: Load from N5 if possible
		if( minDataset != null && n5.exists(minDataset) ) {
			System.out.println("Loading min face from N5 " + minDataset);
			min = N5Utils.open(n5, minDataset);
		} else if( minDataset != null && new File(minDataset).exists() ) {
			// If there is no minDataset, then assume this is an HDF5
			System.out.println("Loading min face from HDF5");
			final IHDF5Reader hdf5Reader = HDF5Factory.openForReading(minDataset);
			final N5HDF5Reader hdf5 = new N5HDF5Reader(hdf5Reader, new int[]{128, 128, 128});
			final RandomAccessibleInterval<FloatType> floats = N5Utils.open(hdf5, "/volume");
			min = Converters.convert(floats, (a, b) -> b.setReal(a.getRealDouble()), new DoubleType());

			// Using an HDF5 RAI can be slow when computing transforms, write to N5 and use that FIXME
//			N5FSWriter n5w = new N5FSWriter(n5Path);
//			N5Utils.save(minConv, n5w, flattenDataset + BigWarp.minFaceDatasetName, new int[]{1024, 1024}, new GzipCompression());
//			min = N5Utils.open(n5, flattenDataset + BigWarp.minFaceDatasetName);
		}

		// Min heightmap: Load from N5 if possible
		if( maxDataset != null && n5.exists(maxDataset) ) {
			System.out.println("Loading max face from N5 " + maxDataset);
			max = N5Utils.open(n5, maxDataset);
		} else if(  maxDataset != null && new File(maxDataset).exists() ) {
			// If there is no maxDataset, then assume this is an HDF5
			System.out.println("Loading max face from HDF5");
			final IHDF5Reader hdf5Reader = HDF5Factory.openForReading(maxDataset);
			final N5HDF5Reader hdf5 = new N5HDF5Reader(hdf5Reader, new int[]{128, 128, 128});
			final RandomAccessibleInterval<FloatType> floats = N5Utils.open(hdf5, "/volume");
			max = Converters.convert(floats, (a, b) -> b.setReal(a.getRealDouble()), new DoubleType());

			// Using an HDF5 RAI can be slow when computing transforms, write to N5 and use that FIXME
//			N5FSWriter n5w = new N5FSWriter(n5Path);
//			N5Utils.save(maxConv, n5w, flattenDataset + BigWarp.maxFaceDatasetName, new int[]{1024, 1024}, new GzipCompression());
//			max = N5Utils.open(n5, flattenDataset + BigWarp.maxFaceDatasetName);
		}



		DoubleType maxMean = getAvgValue(max);
		DoubleType minMean = getAvgValue(min);
		System.out.println("Done computing average values of heightmap. min = " + minMean.getRealDouble() + " max = " + maxMean.getRealDouble());

		final Scale2D transformScale = new Scale2D(transformScaleX, transformScaleY);

		final FlattenTransform ft = new FlattenTransform(
				RealViews.affine(
						Views.interpolate(
								Views.extendBorder(min),
								new NLinearInterpolatorFactory<>()),
						transformScale),
				RealViews.affine(
						Views.interpolate(
								Views.extendBorder(max),
								new NLinearInterpolatorFactory<>()),
						transformScale),
				minMean.get(),
				maxMean.get());

		/*
		 * raw data
		 */
		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.min(8, Math.max(1, numProc - 2)));


		final FinalInterval cropInterval = new FinalInterval(
				new long[] {0, 0, Math.round(minMean.get()) - padding},
				new long[] {dimensions[0] - 1, dimensions[2] - 1, Math.round(maxMean.get()) + padding});


		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<UnsignedByteType>[] rawMipmaps = new RandomAccessibleInterval[numScales];

		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = new RandomAccessibleInterval[numScales];

		final double[][] scales = new double[numScales][];

		/*
		 * raw pixels for mipmap level
		 * can be reused when transformation updates
		 */
		for (int s = 0; s < numScales; ++s) {

			/* TODO read downsamplingFactors */
			final int scale = 1 << s;
			final double inverseScale = 1.0 / scale;

			rawMipmaps[s] =
					N5Utils.openVolatile(
							n5,
							inputDataset + "/s" + s);
		}


		BdvStackSource<?> bdv = null;


		/*
		 * transform, everything below needs update when transform changes
		 */
		for (int s = 0; s < numScales; ++s) {

			/* TODO read downsamplingFactors */
			final int scale = 1 << s;
			final double inverseScale = 1.0 / scale;

			final RealTransformSequence transformSequence = new RealTransformSequence();
			final Scale3D scale3D = new Scale3D(inverseScale, inverseScale, inverseScale);
			final Translation3D shift = new Translation3D(0.5 * (scale - 1), 0.5 * (scale - 1), 0.5 * (scale - 1));
			transformSequence.add(shift);
			transformSequence.add(ft.inverse());
			transformSequence.add(shift.inverse());
			transformSequence.add(scale3D);

			final RandomAccessibleInterval<UnsignedByteType> transformedSource =
					Transform.createTransformedInterval(
							Views.permute(rawMipmaps[s], 1, 2),
							cropInterval,
							transformSequence,
							new UnsignedByteType(0));

			final SubsampleIntervalView<UnsignedByteType> subsampledTransformedSource = Views.subsample(transformedSource, scale);
			final RandomAccessibleInterval<UnsignedByteType> cachedSource = Show.wrapAsVolatileCachedCellImg(subsampledTransformedSource, new int[]{32, 32, 32});

			mipmaps[s] = cachedSource;
			scales[s] = new double[]{scale, scale, scale};
		}

		/*
		 * update when transforms change
		 */
		final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
				new RandomAccessibleIntervalMipmapSource<>(
						mipmaps,
						new UnsignedByteType(),
						scales,
						voxelDimensions,
						inputDataset);

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

		return null;
	}

	public static DoubleType getAvgValue(RandomAccessibleInterval<DoubleType> rai) {
        RandomAccess<DoubleType> ra = rai.randomAccess();
        long[] pos = new long[rai.numDimensions()];
        for( int k = 0; k < pos.length; k++ ) pos[k] = 0;
        ra.localize(pos);
        DoubleType avg = new DoubleType();

        long count = 0;
        double dAvg = 0;
        for( pos[0] = 0; pos[0] < rai.dimension(0); pos[0]++ ) {
            for( pos[1] = 0; pos[1] < rai.dimension(1); pos[1]++ ) {
                ra.setPosition(pos);
                // Apparently some heightmap values can be NaN
                if( !Double.isNaN(ra.get().getRealDouble()) && !Double.isInfinite(ra.get().getRealDouble()) ) {
					dAvg += ra.get().getRealDouble();
					count++;
				}
            }
        }

        return new DoubleType(dAvg / count);
    }

    public static DoubleType getMaxValue(RandomAccessibleInterval<DoubleType> rai) {
        RandomAccess<DoubleType> ra = rai.randomAccess();
        long[] pos = new long[rai.numDimensions()];
        for( int k = 0; k < pos.length; k++ ) pos[k] = 0;
        ra.localize(pos);

        double max = Double.MIN_VALUE;
        for( pos[0] = 0; pos[0] < rai.dimension(0); pos[0]++ ) {
            for( pos[1] = 0; pos[1] < rai.dimension(1); pos[1]++ ) {
                ra.setPosition(pos);
                max = Math.max( max, ra.get().getRealDouble() );
            }
        }

        return new DoubleType(max);
    }        DoubleType avg = new DoubleType();

    public static DoubleType getMinValue(RandomAccessibleInterval<DoubleType> rai) {
        RandomAccess<DoubleType> ra = rai.randomAccess();
        long[] pos = new long[rai.numDimensions()];
        for( int k = 0; k < pos.length; k++ ) pos[k] = 0;
        ra.localize(pos);

        double min = Double.MAX_VALUE;
        for( pos[0] = 0; pos[0] < rai.dimension(0); pos[0]++ ) {
            for( pos[1] = 0; pos[1] < rai.dimension(1); pos[1]++ ) {
                ra.setPosition(pos);
                min = Math.min( min, ra.get().getRealDouble() );
            }
        }

        return new DoubleType(min);
    }
}
