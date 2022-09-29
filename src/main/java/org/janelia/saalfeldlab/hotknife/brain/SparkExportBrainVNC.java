package org.janelia.saalfeldlab.hotknife.brain;

import java.io.IOException;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

/**
 * created from PlaygroundStitch3
 * 
 * @author preibischs
 *
 */
public class SparkExportBrainVNC {

	public static void main( String[] args ) throws IOException
	{
		final int n5Level = args.length > 1 ? Integer.parseInt(args[1]) : 5;
		final String datasetNameOutput = args.length > 0 ? args[0] : "/fullcns_test/s" + n5Level;

		final String n5Path = "/nrs/flyem/render/n5/Z0720_07m_BR/40-06-final/s"+n5Level;
		final String imgGroup = ".";
		final String brainVNCsurface = "/nrs/flyem/render/n5/Z0720_07m_VNC/heightfields_fix/brain-VNC/pass1_preibischs/min";
		final String brainVNCsurfaceGroup = ".";
		final String brainVNCdeformationField = "/nrs/flyem/render/n5/Z0720_07m_VNC/surface-align-VNC/06-37/run_20220908_121000/pass12_edit/";
		final String brainVNCdeformationFieldGroup = "/flat.Sec37.bot.face";
		final String VNCn5Path = "/nrs/flyem/render/n5/Z0720_07m_VNC/";
		final String VNCimgGroup = "final-align-VNC/20220922_120102/s"+n5Level;

		final String n5PathOutput = "/nrs/flyem/render/n5/Z0720_07m_CNS.n5";

		final SparkConf conf = new SparkConf().setAppName( "SparkExportBrainVNC" );
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);

		saveSpark(sparkContext,
				  n5Path,
				  imgGroup,
				  n5Level,
				  brainVNCsurface,
				  brainVNCsurfaceGroup,
				  brainVNCdeformationField,
				  brainVNCdeformationFieldGroup,
				  VNCn5Path,
				  VNCimgGroup,
				  n5PathOutput,
				  datasetNameOutput);
	}

	public static void saveSpark(
			final JavaSparkContext sparkContext,
			final String n5Path,
			final String imgGroup,
			final int n5Level,
			final String brainVNCsurface,
			final String brainVNCsurfaceGroup,
			final String brainVNCdeformationField,
			final String brainVNCdeformationFieldGroup,
			final String VNCn5Path,
			final String VNCimgGroup,
			final String n5PathOutput,
			final String datasetNameOutput) throws IOException
	{
		final N5Reader n5Input = new N5FSReader(n5Path);

		// ---------------------------
		// determine new bounding box
		// ---------------------------
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(new N5FSReader(n5Path), imgGroup);
		final RandomAccessibleInterval<UnsignedByteType> imgVNC = N5Utils.openVolatile(new N5FSReader(VNCn5Path), VNCimgGroup);

		final FlattenAndUnwarp fau = SparkTransformBrainS5.buildFlattenAndUnwarp(
				n5Level,
				brainVNCsurface,
				brainVNCsurfaceGroup,
				brainVNCdeformationField,
				brainVNCdeformationFieldGroup,
				imgBrain);

		final Interval bbox = Intervals.union(imgBrain, PlaygroundStitch3.orientVNC(fau, imgVNC));

		System.out.println( "Brain interval at s" + n5Level + ": " + Util.printInterval(imgBrain));
		System.out.println( "Brain+VNC interval at s" + n5Level + ": " + Util.printInterval(bbox));

		final long[] dimensions = bbox.dimensionsAsLongArray();
		final int[] blockSize = n5Input.getAttribute(imgGroup, "blockSize", int[].class );
		System.out.println( "dimensions: " + Util.printCoordinates( dimensions ) +
							", blocksize: " + Util.printCoordinates(blockSize) );

		/* create output dataset */
		final N5Writer n5Output = new N5FSWriter(n5PathOutput);
		n5Output.createDataset(datasetNameOutput, dimensions, blockSize, DataType.UINT8, new GzipCompression());

		final List<long[][]> grid = Grid.create(dimensions,
												new int[] {
														blockSize[0] * 4,
														blockSize[1] * 4,
														blockSize[2] * 4
												},
												blockSize);

		System.out.println( grid.size() + " jobs." );

		final JavaRDD<long[][]> pGrid = sparkContext.parallelize(grid);

		pGrid.foreach(
				gridBlock -> saveBlock(n5Path,
									   imgGroup,
									   n5Level,
									   brainVNCsurface,
									   brainVNCsurfaceGroup,
									   brainVNCdeformationField,
									   brainVNCdeformationFieldGroup,
									   VNCn5Path,
									   VNCimgGroup,
									   dimensions,
									   blockSize,
									   gridBlock,
									   n5PathOutput,
									   datasetNameOutput));

		sparkContext.close();

		n5Input.close();
		n5Output.close();
	}

	public static void saveBlock(
			final String n5Path,
			final String imgGroup,
			final int n5Level,
			final String brainVNCsurface,
			final String brainVNCsurfaceGroup,
			final String brainVNCdeformationField,
			final String brainVNCdeformationFieldGroup,
			final String VNCn5Path,
			final String VNCimgGroup,
			final long[] dimensions,
			final int[] blockSize,
			final long[][] gridBlock,
			final String n5PathOutput,
			final String datasetNameOutput) throws IOException
	{

		final N5Writer n5Output = new N5FSWriter(n5PathOutput);

		final N5Reader n5 = new N5FSReader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(n5, imgGroup);

		final FlattenAndUnwarp fau = SparkTransformBrainS5.buildFlattenAndUnwarp(
				n5Level,
				brainVNCsurface,
				brainVNCsurfaceGroup,
				brainVNCdeformationField,
				brainVNCdeformationFieldGroup,
				imgBrain);

		final N5Reader VNCn5 = new N5FSReader(VNCn5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgVNC = N5Utils.openVolatile(VNCn5, VNCimgGroup);
		final RandomAccessibleInterval<UnsignedByteType> viewVNC = PlaygroundStitch3.orientVNC(fau, imgVNC);
		final Interval bbox = Intervals.union(imgBrain, viewVNC);

		final RandomAccessibleInterval<UnsignedByteType> merged =
				PlaygroundStitch3.merge(fau.getCompositeUnwarpedCrop(), viewVNC, bbox);

		// save it given the grid[][] location to the new n5
		final FinalInterval gridBlockInterval =
				Intervals.createMinSize(
						gridBlock[0][0],
						gridBlock[0][1],
						gridBlock[0][2],
						gridBlock[1][0],
						gridBlock[1][1],
						gridBlock[1][2]);

		N5Utils.saveNonEmptyBlock(
				Views.interval(merged, gridBlockInterval),
				n5Output,
				datasetNameOutput,
				new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
				gridBlock[2],
				new UnsignedByteType());

		n5Output.close();
		n5.close();
	}
}
