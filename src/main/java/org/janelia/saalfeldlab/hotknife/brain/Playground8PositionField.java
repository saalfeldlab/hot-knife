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
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
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


public class Playground8PositionField {

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
		bdv.setActive(false);


		final RandomAccessibleInterval<DoubleType> rpf = Views.interval(
				new FunctionRandomAccessible<>(
						pf.numDimensions(),
						() -> {
							final RandomAccess<DoubleType> input = Views.extendBorder(pf).randomAccess();
							return (pos, value) -> {
								input.setPosition(pos);
								final int d = pos.getIntPosition(pos.numDimensions() - 1);
								value.set(input.get().get() - pos.getDoublePosition(d) - positionField.getOffset(d));
							};
						},
						DoubleType::new),
				pf);
		final BdvStackSource<DoubleType> rpfSource = BdvFunctions.show(rpf, "relative positionfield", Bdv.options().axisOrder(AxisOrder.XYC).addTo(bdv));
		rpfSource.setDisplayRangeBounds(-500, 500);
		rpfSource.setDisplayRange(-10, 200);


		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);

		final ImagePlus imp = IJ.openImage("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/flattened_fullplane.tif");
		final Img<UnsignedByteType> flattenedCrop = ImageJFunctions.wrapByte(imp);
		addCheckerboard(flattenedCrop, 8, 20, 20);
		BdvFunctions.show(flattenedCrop, "flattened crop", Bdv.options().addTo(bdv));

		final int n5Level = 5;
		final long[] minIntervalS0 = {47204, 46557, 42756};
		final long[] maxIntervalS0 = {55779, 59038, 53664};
		final long[] minInterval = lscale(minIntervalS0, n5Level);
		final long[] maxInterval = lscale(maxIntervalS0, n5Level);

		final long[] cropMin = {minInterval[1], minInterval[2]};
		final RealTransform pft = new TransformedPositionField(positionField, n5Level, cropMin).getTransform();
		final RealRandomAccessible<UnsignedByteType> unwarped = new RealTransformRealRandomAccessible<>(
				Views.interpolate(
						Views.extendBorder(flattenedCrop),
						new ClampingNLinearInterpolatorFactory<>()),
				pft);

		BdvFunctions.show(unwarped, flattenedCrop, "unwarped", Bdv.options().addTo(bdv));
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

	public static class TransformedPositionField {

		private final PositionField positionField;

		private final double pfOffsetX;
		private final double pfOffsetY;
		private final double imgToPfScale;
		private final double pfToImgScale;
		private final double imgCropMinX;
		private final double imgCropMinY;

		// make into a deformation field
		private final RandomAccessibleInterval<DoubleType> rpf;
		private final RealRandomAccessible<DoubleType> irpf;

		public double pfToImgX(final double pfX)
		{
			return (pfX + pfOffsetX)  * pfToImgScale + imgCropMinX;
		}

		public double pfToImgY(final double pfY)
		{
			return (pfY + pfOffsetY)  * pfToImgScale + imgCropMinY;
		}

		public double imgToPfX(final double imgX)
		{
			return (imgX - imgCropMinX) * imgToPfScale - pfOffsetX;
		}

		public double imgToPfY(final double imgY)
		{
			return (imgY - imgCropMinY) * imgToPfScale - pfOffsetY;
		}

		// imgCropMin: in the resolution level of the image (not necessarily full resolution)
		public TransformedPositionField(final PositionField positionField, final int imgLevel, final long[] imgCropMin) {
			this.positionField = positionField;
			pfOffsetX = positionField.getOffset(0);
			pfOffsetY = positionField.getOffset(1);
			imgToPfScale = positionField.getScale() * (1 << imgLevel);
			pfToImgScale = 1.0 / imgToPfScale;
			imgCropMinX = imgCropMin[ 0 ];
			imgCropMinY = imgCropMin[ 1 ];

			final RandomAccessibleInterval<DoubleType> pf = positionField.getPositionFieldRAI();
			rpf = Views.interval(
					new FunctionRandomAccessible<>(
							pf.numDimensions(),
							() -> {
								final RandomAccess<DoubleType> input = pf.randomAccess();
								return (pos, value) -> {
									if (Intervals.contains(pf, pos)) {
										input.setPosition(pos);
										final int d = pos.getIntPosition(pos.numDimensions() - 1);
										value.set(input.get().get() - pos.getDoublePosition(d) - positionField.getOffset(d));
									} else {
										value.set(0);
									}
								};
							},
							DoubleType::new),
					pf);
			irpf = Views.interpolate(rpf, new NLinearInterpolatorFactory<>());
		}

		public RealTransform getTransform() {
			return new PFTransform();
		}

		class PFTransform implements RealTransform {

			private final double[] pf = new double[3];
			private final RealRandomAccess<DoubleType> rpfa = irpf.realRandomAccess();

			public PFTransform() {
			}

			@Override
			public int numSourceDimensions() {
				return 2;
			}

			@Override
			public int numTargetDimensions() {
				return 2;
			}

			@Override
			public void apply(final double[] source, final double[] target) {
				apply(RealPoint.wrap(source), RealPoint.wrap(target));
			}

			@Override
			public void apply(final RealLocalizable source, final RealPositionable target) {
				final double imgX = source.getDoublePosition(0);
				final double imgY = source.getDoublePosition(1);
				pf[0] = imgToPfX(imgX);
				pf[1] = imgToPfY(imgY);
				rpfa.setPosition(pf);
				final double pfDiffX = rpfa.get().get();
				rpfa.setPosition(1, 2);
				final double pfDiffY = rpfa.get().get();
				target.setPosition(imgX + pfDiffX * pfToImgScale, 0);
				target.setPosition(imgY + pfDiffY * pfToImgScale, 1);
			}

			@Override
			public RealTransform copy() {
				return new PFTransform();
			}
		}
	}
}
