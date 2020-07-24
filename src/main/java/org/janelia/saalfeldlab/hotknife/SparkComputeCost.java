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

import com.kephale.vnc.CostUtils;
import com.kephale.vnc.DagmarCost;
import ij.process.ColorProcessor;
import net.imglib2.*;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import net.imglib2.util.Intervals;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.alignment.ArgbRenderer;
import org.janelia.alignment.RenderParameters;
import org.janelia.alignment.util.ImageProcessorCache;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.*;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Export a render stack to N5.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkComputeCost {

	final static public String ownerFormat = "%s/owner/%s";
	final static public String stackListFormat = ownerFormat + "/stacks";
	final static public String stackFormat = ownerFormat + "/project/%s/stack/%s";
	final static public String stackBoundsFormat = stackFormat  + "/bounds";
	final static public String boundingBoxFormat = stackFormat + "/z/%d/box/%d,%d,%d,%d,%f";
	final static public String renderParametersFormat = boundingBoxFormat + "/render-parameters";

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--inputN5Path", required = true, usage = "input N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "--outputN5Path", required = true, usage = "output N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String outputN5Path = null;

		@Option(name = "--inputN5Group", required = true, usage = "N5 dataset, e.g. /zcorr/Sec26")
		private String inputDatasetName = null;

		@Option(name = "--costN5Group", required = true, usage = "N5 dataset, e.g. /cost/Sec26")
		private String costDatasetName = null;

		@Option(name = "--costSteps", required = true, usage = "Step size for computing cost, e.g. 6,1,6")
		private String costStepString = null;
		private int[] costSteps;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);

			costSteps = new int[3];

			try {
				parser.parseArgument(args);

				parseCSIntArray(costStepString, costSteps);

				parsedSuccessfully = true;
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

		public String getOutputN5Path() {
			return outputN5Path;
		}

		public String getInputDatasetName() {
			return inputDatasetName;
		}

		public String getCostDatasetName() {
			return costDatasetName;
		}

		public String getCostStepString() {
			return costStepString;
		}

		public int[] getCostSteps() {
			return costSteps;
		}
	}

	public static final void computeCost(
			final JavaSparkContext sc,
			final Options options) throws IOException {

		String n5Path = options.getN5Path();
		String costN5Path = options.getOutputN5Path();
		String zcorrDataset = options.getInputDatasetName();
		String costDataset = options.getCostDatasetName();
		int[] costSteps = options.getCostSteps();

		final N5Reader n5 = new N5FSReader(n5Path);
		final N5Writer n5w = new N5FSWriter(costN5Path);

		int[] zcorrBlockSize = n5.getAttribute(zcorrDataset, "blockSize", int[].class);
		long[] zcorrSize = n5.getAttribute(zcorrDataset, "dimensions", long[].class);
		RandomAccessibleInterval<UnsignedByteType> zcorr = N5Utils.openVolatile(n5, zcorrDataset);

		int[] costBlockSize = new int[]{
				zcorrBlockSize[0] / costSteps[0],
				zcorrBlockSize[1] / costSteps[1],
				zcorrBlockSize[2] / costSteps[2]
		};

		long[] costSize = new long[]{ zcorrSize[0] / costSteps[0], zcorrSize[1] / costSteps[1], zcorrSize[2] / costSteps[2] };

		n5w.createDataset(
        		costDataset,
        		costSize,
        		costBlockSize,
        		DataType.UINT8,
        		new GzipCompression());
		final ArrayList<Long[]> gridCoords = new ArrayList<>();

		int gridXSize = (int)Math.ceil(zcorrSize[0] / costSteps[0]);
		int gridZSize = (int)Math.ceil(zcorrSize[2] / costSteps[2]);

		for (long x = 0; x < gridXSize; x++) {
			for (long z = 0; z < gridZSize; z++) {
				gridCoords.add(new Long[]{x, z});
			}
		}

		System.out.println("Processing " + gridCoords.size() + " grid pairs. " + gridXSize + " by " + gridZSize);

		final JavaRDD<Long[]> rddSlices = sc.parallelize(gridCoords);

		rddSlices.foreach(gridCoord -> {
		//gridCoords.forEach(gridCoord -> {

			ExecutorService executorService =  Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 2);

			try {
			    processColumn(n5Path, costN5Path, zcorrDataset, costDataset, costBlockSize, zcorrBlockSize, zcorrSize, costSteps, gridCoord, executorService);
			} catch (Exception e)
			    {
				e.printStackTrace();
			    }

		});
	}

	private static void processColumn(
			String n5Path,
			String costN5Path,
			String zcorrDataset,
			String costDataset,
			int[] costBlockSize,
			int[] zcorrBlockSize,
			long[] zcorrSize,
			int[] costSteps,
			Long[] gridCoord,
			ExecutorService executorService) throws Exception {

	    System.out.println("Processing grid coord: " + gridCoord[0] + " " + gridCoord[1]);

		final N5Reader n5 = new N5FSReader(n5Path);
		final N5Writer n5w = new N5FSWriter(costN5Path);

		RandomAccessibleInterval<UnsignedByteType> zcorr = N5Utils.open(n5, zcorrDataset);
		Interval zcorrInterval = getZcorrInterval(gridCoord[0], gridCoord[1], zcorrSize, zcorrBlockSize);

		zcorr = Views.zeroMin( Views.interval( zcorr, zcorrInterval ) );

		Img<UnsignedByteType> cost = ArrayImgs.unsignedBytes(
				zcorrInterval.dimension(0) / costSteps[0],
				zcorrInterval.dimension(1) / costSteps[1],
				zcorrInterval.dimension(2) / costSteps[2]);
		RandomAccess<UnsignedByteType> costAccess = cost.randomAccess();

		System.out.println("Cost dimensions: " + Arrays.toString(Intervals.dimensionsAsIntArray(cost)) );

		// Loop over slices and populate cost
		for( int zIdx = 0; zIdx < zcorr.max(2); zIdx += costSteps[2] ) {
			RandomAccessibleInterval<UnsignedByteType> slice = Views.hyperSlice(zcorr, 2, zIdx);

			RandomAccess<UnsignedByteType> sliceAccess = slice.randomAccess();
			Img<UnsignedByteType> sliceCopy = ArrayImgs.unsignedBytes(slice.dimension(0), slice.dimension(1));
			Cursor<UnsignedByteType> cc = sliceCopy.localizingCursor();
			while( cc.hasNext() ) {
				cc.fwd();;
				sliceAccess.setPosition(cc);
				cc.get().set(sliceAccess.get());
			}

			Img<FloatType> costSlice = DagmarCost.computeResin(sliceCopy, costSteps[0], executorService);
			Cursor<FloatType> csCur = costSlice.localizingCursor();
			while( csCur.hasNext() ) {
				csCur.fwd();
				costAccess.setPosition(csCur);

				costAccess.get().set((int) csCur.get().get());
			}
		}

		RandomAccessibleInterval<UnsignedByteType> costRai = CostUtils.floatAsUnsignedByte(CostUtils.initializeCost(cost));

		System.out.println("Writing blocks");

		// Now loop over blocks and write
		for( int yGrid = 0; yGrid < Math.ceil(zcorrSize[1] / zcorrBlockSize[1]); yGrid++ ) {
			long[] gridOffset = new long[]{gridCoord[0], yGrid, gridCoord[1]};
			RandomAccessibleInterval<UnsignedByteType> block = Views.interval(
					costRai,
					new FinalInterval(
							new long[]{0, yGrid * zcorrBlockSize[1], 0},
							new long[]{cost.dimension(0) - 1, (yGrid + 1) * zcorrBlockSize[1] - 1, cost.dimension(2) - 1}));
			N5Utils.saveBlock(
					block,
					n5w,
					costDataset,
					gridOffset);
		}
	}

	private static Interval getZcorrInterval(Long gridX, Long gridZ, long[] zcorrSize, int[] zcorrBlockSize) {
		long startX = gridX * zcorrBlockSize[0];
		long startY = 0;
		long startZ = gridZ * zcorrBlockSize[2];
		long stopX = ( gridX + 1 ) * zcorrBlockSize[0];
		long stopY = zcorrSize[1];
		long stopZ = ( gridZ + 1 ) * zcorrBlockSize[2];
		return new FinalInterval(
				new long[]{startX, startY, startZ},
				new long[]{stopX, stopY, stopZ});
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName("SparkComputeCost");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		
		//final JavaSparkContext sc = null;

		computeCost(sc, options);

		sc.close();

	}
}
