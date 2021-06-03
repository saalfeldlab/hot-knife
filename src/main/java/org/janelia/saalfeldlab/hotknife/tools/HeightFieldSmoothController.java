package org.janelia.saalfeldlab.hotknife.tools;

import java.awt.Color;

import org.janelia.saalfeldlab.hotknife.ops.SimpleGaussRA;
import org.scijava.ui.behaviour.ScrollBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;

import bdv.viewer.ViewerPanel;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.cache.Cache;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class HeightFieldSmoothController extends AbstractHeightFieldBrushController {

	protected SimpleGaussRA<FloatType> gaussOp;
	protected ArrayImg<FloatType, ?> patch;
	protected double smoothSigma = 2;

	protected final Cache< ?, ? > gradientCache;

	public HeightFieldSmoothController(
			final ViewerPanel viewer,
			final RandomAccessibleInterval<FloatType> heightField,
			final ScaleAndTranslation heightFieldTransform,
			final Cache< ?, ? > gradientCache,
			final InputTriggerConfig config) {

		super(viewer, heightField, heightFieldTransform, config, new CircleOverlay(viewer, new int[] {5, 2}, new Color[] {Color.YELLOW, Color.MAGENTA}));

		gaussOp = new SimpleGaussRA<>(new double[] {smoothSigma, smoothSigma});
		patch = new ArrayImgFactory<>(new FloatType()).create(brushMask);

		this.gradientCache = gradientCache;

		new Smooth("paint smooth brush", "Q button1").register();
		new ChangeBrushRadius("change smooth brush radius", "Q scroll").register();
		new ChangeBrushRadius2("change smooth sigma", "shift Q scroll").register();
		new MoveBrush("move smooth brush", "Q").register();
	}

	protected class Smooth extends AbstractPaintBehavior {

		public Smooth(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		protected void paint(final RealLocalizable coords)
		{
			final IntervalView<FloatType> heightFieldInterval = Views.interval(
					zeroExtendedHeightField,
					new long[] {
							Math.round(coords.getDoublePosition(0) - (brushMask.dimension(0) / 2)),
							Math.round(coords.getDoublePosition(1) - (brushMask.dimension(1) / 2))},
					new long[] {
							Math.round(coords.getDoublePosition(0) + (brushMask.dimension(0) / 2)),
							Math.round(coords.getDoublePosition(1) + (brushMask.dimension(1) / 2))});

			gaussOp.compute(
					extendedHeightField,
					Views.translate(
							patch,
							heightFieldInterval.min(0),
							heightFieldInterval.min(1)));

			final ArrayCursor<DoubleType> maskCursor = brushMask.cursor();
			final Cursor<FloatType> heightFieldCursor = heightFieldInterval.cursor();
			final Cursor<FloatType> patchCursor = patch.cursor();

			while (maskCursor.hasNext()) {

				final FloatType v = heightFieldCursor.next();
				final double a = v.getRealDouble();
				final double b = patchCursor.next().getRealDouble();
				final double lambda = maskCursor.next().getRealDouble();

				v.setReal((b - a) * lambda + a);
			}

			if ( gradientCache != null )
				gradientCache.invalidateAll();
		}
	}

	protected class ChangeBrushRadius extends AbstractHeightFieldBrushController.ChangeBrushRadius {

		public ChangeBrushRadius(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void scroll(final double wheelRotation, final boolean isHorizontal, final int x, final int y) {

			super.scroll(wheelRotation, isHorizontal, x, y);

			if (!isHorizontal) {
				patch = new ArrayImgFactory<>(new FloatType()).create(brushMask);
			}
		}
	}

	protected class ChangeBrushRadius2 extends SelfRegisteringBehaviour implements ScrollBehaviour {

		public ChangeBrushRadius2(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void scroll(final double wheelRotation, final boolean isHorizontal, final int x, final int y) {

			if (!isHorizontal) {
				if (wheelRotation < 0)
					smoothSigma *= 1.1;
				else if (wheelRotation > 0)
					smoothSigma = Math.max(1, smoothSigma * 0.9);

				brushOverlay.setRadius(3 * (int)Math.round(smoothSigma), 1);
				final double scaledSigma = smoothSigma / heightFieldTransform.getScale(0);
				gaussOp = new SimpleGaussRA<>(new double[] {scaledSigma, scaledSigma});
				viewer.getDisplay().repaint();
			}
		}
	}
}
