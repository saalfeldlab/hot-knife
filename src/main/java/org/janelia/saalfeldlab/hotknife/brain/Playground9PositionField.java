package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.AxisOrder;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.viewer.ViewerPanel;
import ij.IJ;
import ij.ImagePlus;
import java.io.IOException;
import net.imglib2.Cursor;
import net.imglib2.FinalRealInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.ConstantUtils;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.brain.Playground8PositionField.TransformedPositionField;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import static org.janelia.saalfeldlab.hotknife.brain.Playground5.lscale;


public class Playground9PositionField {

	// load and show position field
	public static void main(String[] args) throws IOException {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/positionfield";
		final String transformGroup = "/flat.Sec37.bot.face";
		final N5Reader n5 = new N5FSReader(n5Path);
		final PositionField positionField = new PositionField(n5, transformGroup);
		System.out.println("positionField = " + positionField);

		final ImagePlus imp = IJ.openImage("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/flattened_fullplane.tif");
		final Img<UnsignedByteType> flattenedCrop = ImageJFunctions.wrapByte(imp);
		addCheckerboard(flattenedCrop, 8, 20, 20);

		Bdv bdv = BdvFunctions.show(flattenedCrop, "flattened crop", Bdv.options().is2D());

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);

		final int n5Level = 5;
		final long[] minIntervalS0 = {47204, 46557, 42756};
		final long[] maxIntervalS0 = {55779, 59038, 53664};
		final long[] minInterval = lscale(minIntervalS0, n5Level);
		final long[] maxInterval = lscale(maxIntervalS0, n5Level);

		final long[] cropMin = {minInterval[1], minInterval[2]};
		final TransformedPositionField transformedPositionField = new TransformedPositionField(positionField, n5Level, cropMin);
		final RealTransform pft = transformedPositionField.getTransform();
		final RealRandomAccessible<UnsignedByteType> unwarped = new RealTransformRealRandomAccessible<>(
				Views.interpolate(
						Views.extendBorder(flattenedCrop),
						new ClampingNLinearInterpolatorFactory<>()),
				pft);

		BdvFunctions.show(unwarped, flattenedCrop, "unwarped", Bdv.options().addTo(bdv));


		// --------------------------------------------------------------------
		// show backprojected position field bounds as constant RAI
		// --------------------------------------------------------------------

		final double pfMinX = transformedPositionField.pfToImgX(0);
		final double pfMinY = transformedPositionField.pfToImgY(0);
		final double pfMaxX = transformedPositionField.pfToImgX(positionField.getPositionFieldRAI().max(0));
		final double pfMaxY = transformedPositionField.pfToImgY(positionField.getPositionFieldRAI().max(1));

		System.out.println("pfMinX = " + pfMinX);
		System.out.println("pfMinY = " + pfMinY);
		System.out.println("pfMaxX = " + pfMaxX);
		System.out.println("pfMaxY = " + pfMaxY);
		final RandomAccessibleInterval<UnsignedByteType> pfbounds = ConstantUtils.constantRandomAccessibleInterval(new UnsignedByteType(128),
				Intervals.smallestContainingInterval(
						new FinalRealInterval(new double[] {pfMinX, pfMinY}, new double[] {pfMaxX, pfMaxY})
				)
		);
		BdvFunctions.show(pfbounds, "pfbounds", Bdv.options().addTo(bdv));


		// --------------------------------------------------------------------
		// show backprojected relative position field
		// --------------------------------------------------------------------

		final RandomAccessibleInterval<DoubleType> rpf = transformedPositionField.getRelativePositionFieldRAI();
		final AffineTransform3D rpfTransform = new AffineTransform3D();
		rpfTransform.set(
				0.125, 0, 0, pfMinX,
				0, 0.125, 0, pfMinY,
				0, 0, 1, 0
				);
		final BdvStackSource<DoubleType> rpfSource = BdvFunctions.show(rpf, "relative positionfield", Bdv.options().axisOrder(AxisOrder.XYC).addTo(bdv).sourceTransform(rpfTransform));
		rpfSource.setDisplayRangeBounds(-500, 500);
		rpfSource.setDisplayRange(-10, 200);

//		System.out.println("Intervals.toString(rpf) = " + Intervals.toString(rpf));
//		System.out.println("Intervals.toString(flattenedCrop) = " + Intervals.toString(flattenedCrop));


		// --------------------------------------------------------------------
		// show backprojected VNC slice
		// --------------------------------------------------------------------

		final ImagePlus impVNC = IJ.openImage("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/preview_VNC-1.tif");
		final Img<UnsignedByteType> imgVNC = ImageJFunctions.wrapByte(impVNC);
		addCheckerboard(imgVNC, 16, 20, 20);

		final AffineTransform3D vncTransform = new AffineTransform3D();
		vncTransform.set(
				1, 0, 0, pfMinX,
				0, 1, 0, pfMinY,
				0, 0, 1, 0
		);
		final BdvStackSource<UnsignedByteType> vncSource = BdvFunctions.show(imgVNC, "vncSlice", Bdv.options().addTo(bdv).sourceTransform(vncTransform));
	}

	static void addCheckerboard(final Img<UnsignedByteType> img, final int addedValue, final int cw, final int ch) {
		Cursor<UnsignedByteType> c = img.localizingCursor();
		while (c.hasNext()) {
			c.fwd();
			final int x = c.getIntPosition(0);
			final int y = c.getIntPosition(1);
			final int i = ((x / cw) + (y / ch)) % 2;
			if (i == 0) {
				final UnsignedByteType t = c.get();
				t.set(Math.min(t.getInteger() + addedValue, 255));
			}
		}
	}
}
