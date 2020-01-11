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

import bdv.util.volatiles.SharedQueue;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imagej.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.*;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import picocli.CommandLine;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import static net.preibisch.surface.SurfaceFitCommand.*;

/**
 *
 *
 * @author Kyle Harrington &lt;janelia@kyleharrington.com&gt;
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class FlattenSlab implements Callable<Void> {

	public static String minFaceDatasetName = "/heightmaps/min";
	public static String maxFaceDatasetName = "/heightmaps/max";
	public static String nailDatasetName = "/nails";

	@Option(names = {"-i", "--container"}, required = true, description = "container path, e.g. -i /nrs/flyem/tmp/VNC.n5")
	private String n5Path = "/nrs/flyem/alignment/kyle/nail_test.n5";

	@Option(names = {"-d", "--dataset"}, required = true, description = "Input dataset -d '/zcorr/Sec22___20200106_083252'")
	private String inputDataset = "/volumes/input";

	@Option(names = {"-s", "--cost"}, required = true, description = "Cost dataset -d '/cost/Sec22___20200110_133809'")
	private String costDataset = "/volumes/cost";

	@Option(names = {"-f", "--flatten"}, required = true, description = "Flatten subcontainer -f '/flatten/Sec22___20200110_133809'")
	private String flattenDataset = "/flatten";

	@Option(names = {"-u", "--resume"}, required = false, description = "Resume a flattening session by loading min/max from the flatten dataset")
	private boolean resume = false;

	@Option(names = {"--min"}, required = false, description = "Dataset for the min heightmap -f '/flatten/Sec22___20200110_133809/heightmaps/min' or full path to HDF5")
	private String minDataset = null;

	@Option(names = {"--max"}, required = false, description = "Dataset for the max heightmap -f '/flatten/Sec22___20200110_133809/heightmaps/max' or full path to HDF5")
	private String maxDataset = null;

	// If the heightmaps are already in the N5, then read them instead of computing them
	private boolean useExistingHeightmaps = false;

    private long padding = 2000;
	double transformScaleX = 1;
	double transformScaleY = 1;

	FinalVoxelDimensions voxelDimensions = new FinalVoxelDimensions("px", 1, 1, 1);

	public static final void main(String... args) {
		if( args.length == 0 )
			args = new String[]{"-i", "/nrs/flyem/tmp/VNC.n5",
					"-d", "/zcorr/Sec22___20200106_083252",
					"-f", "/flatten/Sec22___20200110_160724",
					"-s", "/cost/Sec22___20200110_160724"};
			//args = new String[]{"-i", "/nrs/flyem/tmp/VNC.n5", "-d", "/zcorr/Sec24___20200106_082231", "-f", "/flatten/Sec24___20200106_082231", "-s", "/cost/Sec23___20200110_152920", "-u"};
		// to regenerate heightmap from HDF5 use these args
		    //args = new String[]{"-i", "/nrs/flyem/tmp/VNC.n5", "-d", "/zcorr/Sec24___20200106_082231", "-f", "/flatten/Sec24___20200106_082231", "--min", "/nrs/flyem/alignment/Z1217-19m/VNC/Sec24/Sec24-bottom.h5", "--max", "/nrs/flyem/alignment/Z1217-19m/VNC/Sec24/Sec24-top.h5"};

		CommandLine.call(new FlattenSlab(), args);
		//new NailFlat().call();
	}

	@Override
	public final Void call() throws IOException, SpimDataException {
		net.imagej.ImageJ imagej = new net.imagej.ImageJ();
		ij.ImageJ ij1 = new ij.ImageJ();
		final N5FSReader n5 = new N5FSReader(n5Path);

		// Extract metadata from input
		final int numScales = n5.list(inputDataset).length;
		final long[] dimensions = n5.getDatasetAttributes(inputDataset + "/s0").getDimensions();

		System.out.println("Dataset dimensions are: " + dimensions[0] + " " + dimensions[1] + " " + dimensions[2] + " [note that Y and Z will be permuted for flattening]");

		RandomAccessibleInterval<DoubleType> min = null;
		RandomAccessibleInterval<DoubleType> max = null;

		minDataset = flattenDataset + minFaceDatasetName;
		maxDataset = flattenDataset + maxFaceDatasetName;

		// Read the full resolution scale of the cost function
		RandomAccessibleInterval<UnsignedByteType> cost = N5Utils.openVolatile(n5, costDataset + "/s0");


        // Convert cost to double **and** swap the Y and Z axis for the FlattenTransform
        RandomAccessibleInterval<DoubleType> fullCost = Views.permute( Converters.convert(cost, (a, b) -> b.setReal(a.getRealDouble()), new DoubleType()), 1, 2 );

        //ImageJFunctions.wrap(fullCost, "fullCost").show();

		/*
		Algorithm is:

		for any input image and cost, compute a min and max heightmaps
		for each pair of heightmaps, make a new flattened image and cost

		for the sequence of downsampling intervals, iteratively apply the above
		 */

		long[] samplingFactors = new long[]{200, 64};

		RandomAccessibleInterval<DoubleType> flattenedCost = fullCost;
		for( long samplingFactor : samplingFactors ) {
			System.out.println("Starting to flatten at sampling factor: " + samplingFactor);
			// Only compute heightmaps if flag is false, or flag is true and one of the heightmaps is missing
			if( useExistingHeightmaps && ( n5.exists(getMinDatasetname(samplingFactor)) && n5.exists(getMaxDatasetname(samplingFactor)) ) ) {
                System.out.println("Heightmaps already exist");
            } else {
				generateHeightmaps( flattenedCost, imagej, samplingFactor );
            }
			System.out.println("Flattening cost");
			flattenedCost = applyHeightmaps( fullCost, samplingFactor );
			ImageJFunctions.wrap(flattenedCost, "flattened_cost_" + samplingFactor).show();
		}

		return null;
	}

    private static DoubleType getAvgValue(RandomAccessibleInterval<DoubleType> rai) {
        RandomAccess<DoubleType> ra = rai.randomAccess();
        long[] pos = new long[rai.numDimensions()];
		Arrays.fill(pos, 0);
        ra.localize(pos);

        long count = 0;
        double dAvg = 0;
        for( pos[0] = 0; pos[0] < rai.dimension(0); pos[0]++ ) {
            for( pos[1] = 0; pos[1] < rai.dimension(1); pos[1]++ ) {
                ra.setPosition(pos);
                dAvg += ra.get().getRealDouble();
                count++;
            }
        }

        return new DoubleType(dAvg / count);
    }

    /**
     * Apply heightmaps that have been stored in the N5 for sampling factor to the cost image at full scale
     * @param fullCost
     * @param samplingFactor
     * @return
     * @throws IOException
     */
	private RandomAccessibleInterval<DoubleType> applyHeightmaps(RandomAccessibleInterval<DoubleType> fullCost, long samplingFactor) throws IOException {
		N5FSReader n5 = new N5FSReader(n5Path);
		RandomAccessibleInterval<DoubleType> minHeightmap = N5Utils.open(n5, getMinDatasetname(samplingFactor));
		RandomAccessibleInterval<DoubleType> maxHeightmap = N5Utils.open(n5, getMaxDatasetname(samplingFactor));

		double minMean = n5.getAttribute(getMinDatasetname(samplingFactor), "mean", double.class);
		double maxMean = n5.getAttribute(getMaxDatasetname(samplingFactor), "mean", double.class);

		final Scale2D transformScale = new Scale2D(transformScaleX, transformScaleY);

		final FlattenTransform ft = new FlattenTransform(
								RealViews.affine(
										Views.interpolate(
												Views.extendBorder(minHeightmap),
												new NLinearInterpolatorFactory<>()),
										transformScale),
								RealViews.affine(
										Views.interpolate(
												Views.extendBorder(maxHeightmap),
												new NLinearInterpolatorFactory<>()),
										transformScale),
								minMean,
								maxMean);

		final int scale = 1 << 1;
		final double inverseScale = 1.0 / scale;

		final RealTransformSequence transformSequence = new RealTransformSequence();
		final Scale3D scale3D = new Scale3D(inverseScale, inverseScale, inverseScale);
		final Translation3D shift = new Translation3D(0.5 * (scale - 1), 0.5 * (scale - 1), 0.5 * (scale - 1));
		transformSequence.add(shift);
		transformSequence.add(ft.inverse());
		transformSequence.add(shift.inverse());
		transformSequence.add(scale3D);

		final FinalInterval cropInterval = new FinalInterval(
				new long[] {0, 0, Math.round(minMean) - padding},
				new long[] {fullCost.dimension(0) - 1, fullCost.dimension(2) - 1, Math.round(maxMean) + padding});

		final RandomAccessibleInterval<DoubleType> transformedCost =
				Transform.createTransformedInterval(
						fullCost,
						cropInterval,
						transformSequence,
						new DoubleType(0));

		return transformedCost;
	}

	private String getMaxDatasetname(long samplingFactor) {
		return flattenDataset + "/heightmaps_" + samplingFactor + "/max";
	}

	private String getMinDatasetname(long samplingFactor) {
		return flattenDataset + "/heightmaps_" + samplingFactor + "/min";
	}

	/**
	 * This function uses a cost function and a sampling factor to solve for min and max heightmaps and write the heightmaps to N5
	 *
	 * @param cost
	 * @param imagej
	 * @param samplingFactor
	 * @throws IOException
	 */
	private void generateHeightmaps(RandomAccessibleInterval<DoubleType> cost, ImageJ imagej, long samplingFactor) throws IOException {
		long startTime = System.nanoTime();
		System.out.println("Computing heightmaps at sampling factor:" + samplingFactor);

		RandomAccessibleInterval<DoubleType> downsampledCostMin = sampleCost(cost, samplingFactor, "min");
		RandomAccessibleInterval<DoubleType> downsampledCostMax = sampleCost(cost, samplingFactor, "max");

		//ImageJFunctions.wrap(downsampledCostMin, "downsampled_cost_min_" + samplingFactor).show();

		N5FSWriter n5 = new N5FSWriter(n5Path);
		// Make sure costs are stored in the right block dimension (e.g. flat) for efficient fetching
		System.out.println("Computing min map");
		RandomAccessibleInterval<IntType> intMin = getScaledSurfaceMap(downsampledCostMin, 0, cost.dimension(0), cost.dimension(1), imagej.op());
		RandomAccessibleInterval<DoubleType> minHeightmap = Converters.convert(intMin, (a, x) -> x.setReal(a.getRealDouble()), new DoubleType());

		System.out.println("Writing min map");
		N5Utils.save( minHeightmap, n5, getMinDatasetname(samplingFactor), new int[]{1024, 1024}, new RawCompression() );

		DoubleType minMean = getAvgValue(minHeightmap);
		n5.setAttribute(getMinDatasetname(samplingFactor), "mean", minMean.get());
		System.out.println("Average min height: " + minMean.get());

		System.out.println("Computing max map");
		RandomAccessibleInterval<IntType> intMax = getScaledSurfaceMap(downsampledCostMax, cost.dimension(2) / 2, cost.dimension(0), cost.dimension(1), imagej.op());
		RandomAccessibleInterval<DoubleType> maxHeightmap = Converters.convert(intMax, (a, x) -> x.setReal(a.getRealDouble()), new DoubleType());

		System.out.println("Writing max map");
		N5Utils.save( maxHeightmap, n5, getMaxDatasetname(samplingFactor), new int[]{1024, 1024}, new RawCompression() );

		DoubleType maxMean = getAvgValue(maxHeightmap);
		n5.setAttribute(getMaxDatasetname(samplingFactor), "mean", maxMean.get());
		System.out.println("Average max height: " + maxMean.get());

		double computeTime = ( System.nanoTime() - startTime ) / 1000000000.0;
		System.out.println("Compute time: " + computeTime);
	}

    /**
     * Return a RAI of the cost function that is appropriate for the sampling factor and side
	 * The key job of this method is to make the smartest View of the cost function for the respective side/samplingFactor
	 *   to minimize the amount of N5 reading work
     * @param cost
     * @param samplingFactor
     * @param side - "max" or "min"
     * @return
     */
	private RandomAccessibleInterval<DoubleType> sampleCost(RandomAccessibleInterval<DoubleType> cost, long samplingFactor, String side) throws IOException {
		FinalInterval cropInterval;
		// This is a silly way of implementing this, but mirrors existing pipeline. Forces half interval per side for 200
		if( samplingFactor == 200 ) {
			if( side.equalsIgnoreCase("min") ) {
				cropInterval = Intervals.createMinMax(0, 0, 0, cost.dimension(0)-1, cost.dimension(1)-1, cost.dimension(2)/2-1);
			} else {
				cropInterval = Intervals.createMinMax(0, 0, cost.dimension(2)/2, cost.dimension(0)-1, cost.dimension(1)-1, cost.dimension(2)-1);
			}
		} else {
			// TODO solve for the correct interval after reading a heightmap from disk
			N5FSReader n5 = new N5FSReader(n5Path);
			RandomAccessibleInterval<DoubleType> hm;

			long hmSamplingFactor;
			long intervalWidth;
			if( samplingFactor == 64 ) {
				intervalWidth = 1200;
				hmSamplingFactor = 200;
			} else if( samplingFactor == 13 ) {
				intervalWidth = 400;
				hmSamplingFactor = 64;
			} else if( samplingFactor == 12 ) {
				intervalWidth = 200;
				hmSamplingFactor = 13;
			} else if( samplingFactor == 11 ) {
				intervalWidth = 100;
				hmSamplingFactor = 12;
			} else if( samplingFactor == 6 ) {
				intervalWidth = 60;
				hmSamplingFactor = 11;
			} else {
				intervalWidth = 50;
				hmSamplingFactor = 6;
			}

			double mean;
			if( side.equalsIgnoreCase("min") ) {
				//hm = N5Utils.open(n5, getMinDatasetname(hmSamplingFactor));
				mean = n5.getAttribute(getMinDatasetname(hmSamplingFactor), "mean", double.class);
			} else {
				//hm = N5Utils.open(n5, getMaxDatasetname(hmSamplingFactor));
				mean = n5.getAttribute(getMaxDatasetname(hmSamplingFactor), "mean", double.class);
			}

			cropInterval = Intervals.createMinMax(0, 0, (long) Math.round(mean - (float)intervalWidth/2), cost.dimension(0)-1, cost.dimension(1)-1, (long) Math.round(mean + (float)intervalWidth/2));
		}

		RandomAccessibleInterval<DoubleType> croppedCost = Views.interval(cost, cropInterval);

		long[] sampleSteps;

		if( samplingFactor == 200 )
			sampleSteps = new long[]{100, 50, samplingFactor};
		else
			sampleSteps = new long[]{1, 1, samplingFactor};

		FinalInterval downsampledInterval = Intervals.createMinMax(0, 0, 0,
				(long) Math.floor((float)croppedCost.dimension(0) / sampleSteps[0]),
				(long) Math.floor((float)croppedCost.dimension(1) / sampleSteps[1]),
				(long) Math.floor((float)croppedCost.dimension(2) / sampleSteps[2]));

		RandomAccessible<DoubleType> sampledCost = Views.subsample(croppedCost, sampleSteps[0], sampleSteps[1], sampleSteps[2]);

		return Views.interval(sampledCost,downsampledInterval);
	}
}
