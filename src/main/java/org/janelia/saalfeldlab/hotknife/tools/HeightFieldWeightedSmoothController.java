package org.janelia.saalfeldlab.hotknife.tools;

import java.awt.Color;
import java.util.List;
import java.util.RandomAccess;

import org.janelia.saalfeldlab.hotknife.ops.SimpleGaussRA;
import org.janelia.saalfeldlab.hotknife.ops.WeightedGaussRA;
import org.scijava.ui.behaviour.ScrollBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;

import bdv.tools.brightness.MinMaxGroup;
import bdv.util.BdvStackSource;
import bdv.viewer.ViewerPanel;
import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.cache.Cache;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class HeightFieldWeightedSmoothController extends AbstractHeightFieldBrushController {

	protected ArrayImg<FloatType, ?> patchHF, patchWeight, patchOut;
	protected double smoothSigma = 2;
	protected double scaledSigma = smoothSigma;

	final protected RandomAccessibleInterval<FloatType> initialGradient;
	final protected RandomAccessible<FloatType> extendedInitialGradient;
	final protected BdvStackSource< ? > bdvInitialGradient;

	final protected Cache< ?, ? > gradientCache;

	public HeightFieldWeightedSmoothController(
			final ViewerPanel viewer,
			final RandomAccessibleInterval<FloatType> heightField,
			final ScaleAndTranslation heightFieldTransform,
			final RandomAccessibleInterval<FloatType> initialGradient,
			final BdvStackSource< ? > bdvInitialGradient,
			final Cache< ?, ? > gradientCache,
			final InputTriggerConfig config) {

		super(viewer, heightField, heightFieldTransform, config, new CircleOverlay(viewer, new int[] {5, 2}, new Color[] {Color.RED, Color.BLUE}));

		this.patchOut = new ArrayImgFactory<>(new FloatType()).create(brushMask);
		this.patchHF = new ArrayImgFactory<>(new FloatType()).create(brushMask);
		this.patchWeight = new ArrayImgFactory<>(new FloatType()).create(brushMask);

		this.bdvInitialGradient = bdvInitialGradient;
		this.gradientCache = gradientCache;
		this.initialGradient = initialGradient;
		this.extendedInitialGradient = Views.extendZero( initialGradient );

		new Smooth("paint weighted smooth brush", "W button1").register();
		new ChangeBrushRadius("change weighted smooth brush radius", "W scroll").register();
		new ChangeBrushRadius2("change weighted smooth sigma", "shift W scroll").register();
		new MoveBrush("move weighted smooth brush", "W").register();
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

			final double[] currentMinMax = getMinMaxDisplayRange( bdvInitialGradient );

			final float min = (float)currentMinMax[ 0 ];
			final float max = (float)currentMinMax[ 1 ];

			final RandomAccessible< FloatType > convertedExtendedInitialGradient = Converters.convert(
					extendedInitialGradient,
					// TODO: Listener on the sliders, only update converter if necessary
					new Converter< FloatType, FloatType >()
					{
						@Override
						public void convert( final FloatType input, final FloatType output )
						{
							output.set( Math.max( 0, Math.min( 1, 1.0f - (( input.get() - min ) / ( max - min )) ) ) );
						}
					},
					new FloatType() );

			final WeightedGaussRA< FloatType > weightedgauss =
					new WeightedGaussRA<>(
							extendedHeightField,
							convertedExtendedInitialGradient,
							Views.translate(
									patchHF,
									heightFieldInterval.min(0),
									heightFieldInterval.min(1) ),
							Views.translate(
									patchWeight,
									heightFieldInterval.min(0),
									heightFieldInterval.min(1) ),
							new FloatType(),
							new double[] {scaledSigma, scaledSigma} );

			weightedgauss.accept(
					Views.translate(
						patchOut,
						heightFieldInterval.min(0),
						heightFieldInterval.min(1)) );

			//ImageJFunctions.show( Views.interval( convertedExtendedGradient, heightFieldInterval ) );
			//ImageJFunctions.show( patchOut );

			final ArrayCursor<DoubleType> maskCursor = brushMask.cursor();
			final Cursor<FloatType> heightFieldCursor = heightFieldInterval.cursor();
			final Cursor<FloatType> patchCursor = patchOut.cursor();

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
				patchOut = new ArrayImgFactory<>(new FloatType()).create(brushMask);
				patchHF = new ArrayImgFactory<>(new FloatType()).create(brushMask);
				patchWeight = new ArrayImgFactory<>(new FloatType()).create(brushMask);
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
				scaledSigma = smoothSigma / heightFieldTransform.getScale(0);
				//gaussOpHF = new SimpleGaussRA<>(new double[] {scaledSigma, scaledSigma});
				viewer.getDisplay().repaint();
			}
		}
	}

	public static double[] getMinMaxDisplayRange( BdvStackSource< ? > bdv )
	{
		// TODO: Listener on the sliders, only update converter if necessary
		// TODO: hack that assumes that it is the second assignment, higher versions of bdv-vistools allow to query this directly
		final MinMaxGroup group = bdv.getBdvHandle().getSetupAssignments().getMinMaxGroups().get( 2 );

		return new double[] {
				group.getMinBoundedValue().getCurrentValue(),
				group.getMaxBoundedValue().getCurrentValue() };
	}
}
