package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.AxisOrder;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.viewer.ViewerPanel;
import ij.IJ;
import ij.ImagePlus;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import static org.janelia.saalfeldlab.hotknife.brain.Playground5.lscale;

public class Playground7PositionField {

	// load and show position field
	public static void main(String[] args) throws IOException {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/positionfield";
		final String transformGroup = "/flat.Sec37.bot.face";
		final N5Reader n5 = new N5FSReader(n5Path);
		final PositionField positionField = new PositionField(n5, transformGroup);
		System.out.println("positionField = " + positionField);

		final RandomAccessibleInterval<DoubleType> pf = positionField.getPositionFieldRAI();
		final BdvStackSource< DoubleType > bdv = BdvFunctions.show( pf, "field", Bdv.options().is2D().axisOrder( AxisOrder.XYC ) );

		final RandomAccessibleInterval< DoubleType > rpf = Views.interval(
				new FunctionRandomAccessible<>(
						pf.numDimensions(),
						() -> {
							final RandomAccess< DoubleType > input = Views.extendBorder( pf ).randomAccess();
							return ( pos, value ) -> {
								input.setPosition( pos );
								final int d = pos.getIntPosition( pos.numDimensions() - 1 );
								value.set( input.get().get() - pos.getDoublePosition( d ) );
							};
						},
						DoubleType::new ),
				pf );
 		BdvFunctions.show(rpf, "relative positionfield", Bdv.options().axisOrder(AxisOrder.XYC).addTo(bdv));

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);




		final ImagePlus imp = IJ.openImage("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/flattened_crop.tif");
		final Img<UnsignedByteType> flattenedCrop = ImageJFunctions.wrapByte(imp);
		final Random random = new Random();
		flattenedCrop.forEach(t -> t.set(t.get() + random.nextInt(128)));
		BdvFunctions.show(flattenedCrop, "flattened crop", Bdv.options().addTo(bdv));

		final int n5Level = 5;
		final double[] n5DownsamplingFactors = {1 << n5Level, 1 << n5Level, 1 << n5Level};
		final long[] minIntervalS0 = {47204, 46557, 42756};
		final long[] maxIntervalS0 = {55779, 59038, 53664};
		final long[] minInterval = lscale(minIntervalS0, n5Level);
		final long[] maxInterval = lscale(maxIntervalS0, n5Level);

		final RealTransform pft = positionField.getTransform(5);
		System.out.println(Arrays.toString(positionField.getPositionFieldRAI().minAsLongArray()));

		final RealRandomAccessible<UnsignedByteType> unwarped = new RealTransformRealRandomAccessible<>(
				Views.interpolate(
						Views.extendBorder(flattenedCrop),
						new ClampingNLinearInterpolatorFactory<>()),
				pft);

		BdvFunctions.show(unwarped, Intervals.hyperSlice(rpf, 2), "unwarped", Bdv.options().addTo(bdv));
	}
}
