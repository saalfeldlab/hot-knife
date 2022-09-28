package org.janelia.saalfeldlab.hotknife.brain;

import java.io.IOException;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.jetbrains.annotations.NotNull;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.DisplayMode;
import bdv.viewer.ViewerPanel;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

/**
 * Class to deform the brain using Spark and methods in ExtractStatic for the s5 downsampled brain
 * <br/>
 * Goal:
 * 0) Make Tobi's code run on our s5 downsampled brain and the correct transformation field and heighfield (so paths are right)
 * 1) save the transformed brain to a new N5
 * 		- same as SparkExportFinalVolume use groups of blocks (e.g. 8x8x1)
 * 2) only apply to the parts that are actually transformed and copy the rest
 * 
 * @author preibischs
 *
 */
public class SparkTransformBrainS5 {

	public static void main( String[] args ) throws IOException
	{
		final String n5PathInput = "/nrs/flyem/render/n5/Z0720_07m_BR/40-06-final/s5";
		final String imgGroup = ".";
		final int n5Level = 5;

		final String n5PathHeightfield = "/nrs/flyem/render/n5/Z0720_07m_VNC/heightfields_fix/brain-VNC/pass1_preibischs/min";//"/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/";
		final String heightfieldGroup = ".";

		final String n5PathPositionField = "/nrs/flyem/render/n5/Z0720_07m_VNC/surface-align-VNC/06-37/run_20220908_121000/pass12_edit/";//"/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/positionfield";
		final String positionFieldGroup = "/flat.Sec37.bot.face";

		final String n5PathOutput = "/nrs/flyem/render/n5/Z0720_07m_CNS.n5";
		final String datasetNameOutput = args.length == 1 ? args[0] : "/preibischs_test/s5";

		final SparkConf conf = new SparkConf().setAppName( "SparkTransformBrainS5" );
		final JavaSparkContext sparkContext = new JavaSparkContext(conf);

		saveSpark(sparkContext,
				  n5PathInput,
				  imgGroup,
				  n5Level,
				  n5PathHeightfield,
				  heightfieldGroup,
				  n5PathPositionField,
				  positionFieldGroup,
				  n5PathOutput,
				  datasetNameOutput);

		//display(n5PathInput, imgGroup, n5Level, n5PathHeightfield, heightfieldGroup, n5PathPositionField, positionFieldGroup);
	}

	@NotNull
	public static FlattenAndUnwarp buildFlattenAndUnwarp(final int n5Level,
														  final String n5PathHeightfield,
														  final String heightfieldGroup,
														  final String n5PathPositionField,
														  final String positionFieldGroup,
														  final RandomAccessibleInterval<UnsignedByteType> imgBrain)
			throws IOException {
		return buildFlattenAndUnwarp(n5Level, n5PathHeightfield, heightfieldGroup, n5PathPositionField, positionFieldGroup, imgBrain, true);
	}

		@NotNull
	public static FlattenAndUnwarp buildFlattenAndUnwarp(final int n5Level,
														  final String n5PathHeightfield,
														  final String heightfieldGroup,
														  final String n5PathPositionField,
														  final String positionFieldGroup,
														  final RandomAccessibleInterval<UnsignedByteType> imgBrain,
														  final boolean doShift)
			throws IOException {
		final long[] minIntervalS0 = {47204, 46557, 42756};
		final long[] maxIntervalS0 = {55779, 59038, 53664};

		// --------------------------------------------------------------------
		// load heightfield
		// --------------------------------------------------------------------
		final MyHeightField hf = new MyHeightField(n5PathHeightfield,
												   heightfieldGroup,
												   new double[] {6, 6, 1},
												   4658.6666161072235);
		final RandomAccessibleInterval<FloatType> heightfield = hf.heightfield();
		final double[] hfDownsamplingFactors = hf.downsamplingFactors();
		final double avg = hf.avg();
		final double[] plane = {2.004294094052206, -1.8362464688517335, 4243.432822291761};
		final int fadeToPlaneDist = 6000;
		final int fadeToAvgDist = 12000;
		final double minModifiedX = 45046;
//		final double minModifiedX = maxIntervalS0[ 0 ] - (500 << n5Level);

		// --------------------------------------------------------------------
		// load position field
		// --------------------------------------------------------------------
		final N5Reader n5PositionField = new N5FSReader(n5PathPositionField);
		final PositionField positionField = new PositionField(n5PositionField, positionFieldGroup);

		// --------------------------------------------------------------------
		// flatten and unwarp
		// --------------------------------------------------------------------
		final int fadeFlattenToIdentityDist = 32000;
		final int yshift = 960; // = 30 * 32
		final int yshiftFadeInPlane = 3200; // = 100 * 32
		final int yshiftFadeOrtho = 3200; // = 100 * 32
		return new FlattenAndUnwarp(
				imgBrain, n5Level, minIntervalS0, maxIntervalS0,
				heightfield, avg, plane, hfDownsamplingFactors, fadeToPlaneDist, fadeToAvgDist, minModifiedX, fadeFlattenToIdentityDist,
				positionField, doShift ? yshift : 0, yshiftFadeInPlane, yshiftFadeOrtho);
	}

	public static void display(
			final String n5Path,
			final String imgGroup,
			final int n5Level,
			final String n5PathHeightfield,
			final String heightfieldGroup,
			final String n5PathPositionField,
			final String positionFieldGroup ) throws IOException
	{
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		// --------------------------------------------------------------------
		// load and crop image
		// (the crop region covers the full image in Y and Z)
		// --------------------------------------------------------------------
		final N5Reader n5 = new N5FSReader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(n5, imgGroup);

		final FlattenAndUnwarp fau = buildFlattenAndUnwarp(n5Level,
														   n5PathHeightfield,
														   heightfieldGroup,
														   n5PathPositionField,
														   positionFieldGroup,
														   imgBrain);

		final RealRandomAccessible<UnsignedByteType> unwarpedCrop = fau.getUnwarpedCrop();
		final RealRandomAccessible<DoubleType> absDisplacement = fau.getAbsDisplacement();

		// --------------------------------------------------------------------
		// show in BDV: crop, unwarped crop
		// --------------------------------------------------------------------
		final BdvSource bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(imgBrain), "imgBrain", Bdv.options());
		bdv.getBdvHandle().getViewerPanel().setDisplayMode(DisplayMode.SINGLE);
		final BdvSource unwarpedCropSource = BdvFunctions.show(unwarpedCrop, imgBrain, "unwarped", Bdv.options().addTo(bdv));
		final BdvSource absDisplacementSource = BdvFunctions.show(absDisplacement, imgBrain, "absolute displacement", Bdv.options().addTo(bdv));
		absDisplacementSource.setColor(new ARGBType(0xff00ff));
		absDisplacementSource.setDisplayRangeBounds(0, 200);
		absDisplacementSource.setDisplayRange(0, 100);

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);
	}

	public static void saveBlock(final String n5PathInput,
								 final String imgGroup,
								 final int n5Level,
								 final String n5PathHeightfield,
								 final String heightfieldGroup,
								 final String n5PathPositionField,
								 final String positionFieldGroup,
								 final long[] dimensions,
								 final int[] blockSize,
								 final long[][] gridBlock,
								 final String n5PathOutput,
								 final String datasetNameOutput)
			throws IOException {

		final N5Reader n5Input = new N5FSReader(n5PathInput);
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(n5Input, imgGroup);
		final N5Writer n5Output = new N5FSWriter(n5PathOutput);

		final FlattenAndUnwarp fau = buildFlattenAndUnwarp(n5Level,
														   n5PathHeightfield,
														   heightfieldGroup,
														   n5PathPositionField,
														   positionFieldGroup,
														   imgBrain);

		final RealRandomAccessible<UnsignedByteType> unwarpedCrop = fau.getUnwarpedCrop();

		// raster it (onto the pixel grid)
		final RandomAccessible<UnsignedByteType> rasteredUnwarpedCrop = Views.raster( unwarpedCrop );

		// save it given the grid[][] location to the new n5
		final FinalInterval gridBlockInterval = Intervals.createMinSize(
				gridBlock[0][0],
				gridBlock[0][1],
				gridBlock[0][2],

				gridBlock[1][0],
				gridBlock[1][1],
				gridBlock[1][2]);

        N5Utils.saveNonEmptyBlock(
                Views.interval(
						rasteredUnwarpedCrop,
                        gridBlockInterval),
                n5Output,
                datasetNameOutput,
                new DatasetAttributes(dimensions, blockSize, DataType.UINT8, new GzipCompression()),
                gridBlock[2],
                new UnsignedByteType());
	}
	
	public static void saveSpark(
			final JavaSparkContext sparkContext,
			final String n5PathInput,
			final String imgGroup,
			final int n5Level,
			final String n5PathHeightfield,
			final String heightfieldGroup,
			final String n5PathPositionField,
			final String positionFieldGroup,
			final String n5PathOutput,
			final String datasetNameOutput) throws IOException
	{
		final N5Reader n5Input = new N5FSReader(n5PathInput);

		final long[] dimensions = n5Input.getAttribute(imgGroup, "dimensions", long[].class );
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
				gridBlock -> saveBlock(n5PathInput,
									   imgGroup,
									   n5Level,
									   n5PathHeightfield,
									   heightfieldGroup,
									   n5PathPositionField,
									   positionFieldGroup,
									   dimensions,
									   blockSize,
									   gridBlock,
									   n5PathOutput,
									   datasetNameOutput));

		sparkContext.close();

		n5Input.close();
		n5Output.close();
	}
}
