package org.janelia.saalfeldlab.hotknife.brain;

import java.io.IOException;

import org.janelia.saalfeldlab.hotknife.brain.ExtractStatic.FlattenAndUnwarp;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.DisplayMode;
import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Class to deform the brain using Spark and methods in ExtractStatic for the s5 downsampled brain
 * 
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
		final String n5Path = "/nrs/flyem/render/n5/Z0720_07m_BR/40-06-final/s5";
		final String imgGroup = ".";

		final String n5PathHeightfield = "/nrs/flyem/render/n5/Z0720_07m_VNC/heightfields_fix/brain-VNC/pass1_preibischs/min";//"/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/";
		final String heightfieldGroup = ".";

		final String n5PathPositionField = "/nrs/flyem/render/n5/Z0720_07m_VNC/surface-align-VNC/06-37/run_20220908_121000/pass12_edit/";//"/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/positionfield";
		final String positionFieldGroup = "/flat.Sec37.bot.face";
		
		display(n5Path, imgGroup, n5PathHeightfield, heightfieldGroup, n5PathPositionField, positionFieldGroup );
	}

	public static void display(
			final String n5Path,
			final String imgGroup,
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
		final int n5Level = 5;

		final long[] minIntervalS0 = {47204, 46557, 42756};
		final long[] maxIntervalS0 = {55779, 59038, 53664};


		// --------------------------------------------------------------------
		// load heightfield
		// --------------------------------------------------------------------
		final MyHeightField hf = new MyHeightField(n5PathHeightfield, heightfieldGroup, new double[] {6, 6, 1}, 4658.6666161072235);
		final RandomAccessibleInterval<FloatType> heightfield = hf.heightfield();
		final double[] hfDownsamplingFactors = hf.downsamplingFactors();
		final double avg = hf.avg();
		final double[] plane = {2.004294094052206, -1.8362464688517335, 4243.432822291761};
		final int fadeToPlaneDist = 1000;
		final int fadeToAvgDist = 2000;
		final double max = 500; // TODO: max should be <= 335 to match stuart's line


		// --------------------------------------------------------------------
		// load position field
		// --------------------------------------------------------------------
		final N5Reader n5PositionField = new N5FSReader(n5PathPositionField);
		final PositionField positionField = new PositionField(n5PositionField, positionFieldGroup);


		// --------------------------------------------------------------------
		// flatten and unwarp
		// --------------------------------------------------------------------
		FlattenAndUnwarp fau = new FlattenAndUnwarp(
				imgBrain, n5Level, minIntervalS0, maxIntervalS0,
				heightfield, avg, plane, hfDownsamplingFactors, fadeToPlaneDist, fadeToAvgDist, max, 1000,
				positionField);

		final RandomAccessibleInterval<UnsignedByteType> crop = fau.getCrop();
		final RealRandomAccessible<UnsignedByteType> unwarpedCrop = fau.getUnwarpedCrop();
		final RealRandomAccessible<DoubleType> absDisplacement =fau.getAbsDisplacement();


		// --------------------------------------------------------------------
		// show in BDV: crop, unwarped crop
		// --------------------------------------------------------------------
		final BdvSource bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(crop), "crop", Bdv.options());
		bdv.getBdvHandle().getViewerPanel().setDisplayMode(DisplayMode.SINGLE);
		final BdvSource unwarpedCropSource = BdvFunctions.show(unwarpedCrop, crop, "unwarped", Bdv.options().addTo(bdv));
		final BdvSource absDisplacementSource = BdvFunctions.show(absDisplacement, crop, "absolute displacement", Bdv.options().addTo(bdv));
		absDisplacementSource.setColor(new ARGBType(0xff00ff));
		absDisplacementSource.setDisplayRangeBounds(0, 200);
		absDisplacementSource.setDisplayRange(0, 100);


		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);
	}
}
