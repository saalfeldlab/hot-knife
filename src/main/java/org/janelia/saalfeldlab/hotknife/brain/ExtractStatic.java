package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.DisplayMode;
import bdv.viewer.ViewerPanel;
import java.io.IOException;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

public class ExtractStatic {


	// Apply heightfield transform to full volume (transformed to crop coordinates), then apply position field
	public static void main(String[] args) throws IOException {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

		// --------------------------------------------------------------------
		// load and crop image
		// (the crop region covers the full image in Y and Z)
		// --------------------------------------------------------------------
		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/s5/";
		final String imgGroup = ".";

		final N5Reader n5 = new N5FSReader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(n5, imgGroup);
		final int n5Level = 5;

		final long[] minIntervalS0 = {47204, 46557, 42756};
		final long[] maxIntervalS0 = {55779, 59038, 53664};


		// --------------------------------------------------------------------
		// load heightfield
		// --------------------------------------------------------------------
		final MyHeightField hf = new MyHeightField("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/", ".", new double[] {6, 6, 1}, 4658.6666161072235);
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
		final String n5PathPositionField = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/positionfield";
		final String positionFieldGroup = "/flat.Sec37.bot.face";
		final N5Reader n5PositionField = new N5FSReader(n5PathPositionField);
		final PositionField positionField = new PositionField(n5PositionField, positionFieldGroup);


		// --------------------------------------------------------------------
		// flatten and unwarp
		// --------------------------------------------------------------------
		final int fadeFlattenToIdentityDist = 32000;
		FlattenAndUnwarp fau = new FlattenAndUnwarp(
				imgBrain, n5Level, minIntervalS0, maxIntervalS0,
				heightfield, avg, plane, hfDownsamplingFactors, fadeToPlaneDist, fadeToAvgDist, minModifiedX, fadeFlattenToIdentityDist,
				positionField,0, 1, 1);


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
}
