package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.DisplayMode;
import bdv.viewer.ViewerPanel;
import java.io.IOException;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

public class PlaygroundStitch3 {


	// Apply heightfield transform to full volume (transformed to crop coordinates), then apply position field
	public static void main(String[] args) throws IOException {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

		// --------------------------------------------------------------------
		// load and crop image
		// (the crop region covers the full image in Y and Z)
		// --------------------------------------------------------------------
		final String n5Path = "/nrs/flyem/render/n5/Z0720_07m_BR/40-06-final/s5";//"/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/s5/";
		final String imgGroup = ".";
		final String brainVNCsurface = "/nrs/flyem/render/n5/Z0720_07m_VNC/heightfields_fix/brain-VNC/pass1_preibischs/min"; //"/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/",
		final String brainVNCsurfaceGroup = ".";
		final String brainVNCdeformationField = "/nrs/flyem/render/n5/Z0720_07m_VNC/surface-align-VNC/06-37/run_20220908_121000/pass12_edit/"; //"/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/positionfield",
		final String brainVNCdeformationFieldGroup = "/flat.Sec37.bot.face";
		final int n5Level = 5;

		final N5Reader n5 = new N5FSReader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(n5, imgGroup);

		// --------------------------------------------------------------------
		// flatten and unwarp
		// --------------------------------------------------------------------
		FlattenAndUnwarp fau = SparkTransformBrainS5.buildFlattenAndUnwarp(
				n5Level,
				brainVNCsurface,
				brainVNCsurfaceGroup,
				brainVNCdeformationField,
				brainVNCdeformationFieldGroup,
				imgBrain);

		// --------------------------------------------------------------------
		// show in BDV: crop, unwarped crop
		// --------------------------------------------------------------------
		final BdvSource bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(imgBrain), "imgBrain", Bdv.options());
		bdv.getBdvHandle().getViewerPanel().setDisplayMode(DisplayMode.SINGLE);

		final RealRandomAccessible<UnsignedByteType> unwarpedCrop = fau.getUnwarpedCrop();
		final BdvSource unwarpedCropSource = BdvFunctions.show(unwarpedCrop, imgBrain, "unwarped", Bdv.options().addTo(bdv));

		// --------------------------------------------------------------------
		// load and crop VNC image
		// --------------------------------------------------------------------
		final String VNCn5Path = "/nrs/flyem/render/n5/Z0720_07m_VNC/";//"/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/vnc.n5/";
		final String VNCimgGroup = "final-align-VNC/20220922_120102/s5";//"setup0/timepoint0/s0/";

		final N5Reader VNCn5 = new N5FSReader(VNCn5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgVNC = N5Utils.openVolatile(VNCn5, VNCimgGroup);

		RandomAccessibleInterval<UnsignedByteType> viewVNC = Views.rotate(imgVNC, 2, 0);
		viewVNC = Views.rotate(viewVNC, 1, 2);
		viewVNC = Views.zeroMin(viewVNC);
		viewVNC = Views.translate(viewVNC, fau.getVncTranslation());

		final Interval bbox = Intervals.union(imgBrain, viewVNC);
		final RandomAccessibleInterval<UnsignedByteType> viewVNCf = viewVNC;
		final RandomAccessibleInterval<UnsignedByteType> merged = Views.interval(
				new FunctionRandomAccessible<>(
						3,
						() -> {
							final RandomAccess<UnsignedByteType> ba = Views.raster(unwarpedCrop).randomAccess();
							final RandomAccess<UnsignedByteType> va = viewVNCf.randomAccess();
							return (pos, type) -> {
								if (Intervals.contains(viewVNCf, pos)) {
									va.setPosition(pos);
									type.set(va.get());
								} else {
									ba.setPosition(pos);
									type.set(ba.get());
								}
							};
						},
						UnsignedByteType::new),
				bbox);
		// TODO ==> merged is what needs to be written out


		FlattenAndUnwarp fauNoShift = SparkTransformBrainS5.buildFlattenAndUnwarp(
				n5Level,
				brainVNCsurface,
				brainVNCsurfaceGroup,
				brainVNCdeformationField,
				brainVNCdeformationFieldGroup,
				imgBrain,
				false);
		final BdvSource unwarpedNoShiftSource = BdvFunctions.show(fauNoShift.getUnwarpedCrop(), imgBrain, "unwarped no shift", Bdv.options().addTo(bdv));

		final RealRandomAccessible<DoubleType> absDisplacement = fau.getAbsDisplacement();
		final BdvSource absDisplacementSource = BdvFunctions.show(absDisplacement, imgBrain, "absolute displacement", Bdv.options().addTo(bdv));
		absDisplacementSource.setColor(new ARGBType(0xff00ff));
		absDisplacementSource.setDisplayRangeBounds(0, 200);
		absDisplacementSource.setDisplayRange(0, 100);

		final BdvSource imgVNCSource = BdvFunctions.show(VolatileViews.wrapAsVolatile(viewVNC), "imgVNC", Bdv.options().addTo(bdv));
		imgVNCSource.setColor(new ARGBType(0x00ff00));

		final BdvSource mergedSource = BdvFunctions.show(merged, "merged", Bdv.options().addTo(bdv));

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);
	}
}
