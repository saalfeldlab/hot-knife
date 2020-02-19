package org.janelia.saalfeldlab.hotknife;

import org.janelia.saalfeldlab.hotknife.util.Util;
import org.scijava.ui.behaviour.Behaviour;
import org.scijava.ui.behaviour.BehaviourMap;
import org.scijava.ui.behaviour.DragBehaviour;
import org.scijava.ui.behaviour.InputTriggerAdder;
import org.scijava.ui.behaviour.InputTriggerMap;
import org.scijava.ui.behaviour.ScrollBehaviour;
import org.scijava.ui.behaviour.io.InputTriggerConfig;

import bdv.bigcat.ui.BrushOverlay;
import bdv.viewer.ViewerPanel;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class HeightFieldBrushController {

	final protected ViewerPanel viewer;
	final protected RandomAccessibleInterval<FloatType> heightField;
	final protected RandomAccessible<FloatType> extendedHeightField;
	final protected ScaleAndTranslation heightFieldTransform;
	final protected RealPoint brushLocation;
	final protected BrushOverlay brushOverlay;
	final protected AffineTransform3D viewerTransform = new AffineTransform3D();
	protected ArrayImg<DoubleType, DoubleArray> brushMask;
	protected double brushSigma = 100;

	// for behavioUrs
	private final BehaviourMap behaviourMap = new BehaviourMap();
	private final InputTriggerMap inputTriggerMap = new InputTriggerMap();
	private final InputTriggerAdder inputAdder;

	private static ArrayImg<DoubleType, DoubleArray> createMask(final double sigma, final double scale) {

		final double sigmaScaled = sigma / scale;
		final double varScaled = -0.5 / sigmaScaled / sigmaScaled;
		final int radius = (int)Math.round(sigmaScaled * 3);
		final ArrayImg<DoubleType, DoubleArray> brushMask = ArrayImgs.doubles(radius * 2 + 1, radius * 2 + 1);
		final FunctionRandomAccessible<DoubleType> gauss = new FunctionRandomAccessible<DoubleType>(
				2,
				(a, b) -> {
					final double x = a.getDoublePosition(0);
					final double y = a.getDoublePosition(1);
					final double v = Math.exp((x * x + y * y) * varScaled);
					b.set(v);
				},
				DoubleType::new);

		Util.copy(gauss, Views.translate(brushMask, -radius, -radius));

		return brushMask;
	}

	public BehaviourMap getBehaviourMap() {

		return behaviourMap;
	}

	public InputTriggerMap getInputTriggerMap() {

		return inputTriggerMap;
	}

	public BrushOverlay getBrushOverlay() {

		return brushOverlay;
	}

	/**
	 * Coordinates where mouse dragging started.
	 */
	private int oX, oY;

	public HeightFieldBrushController(
			final ViewerPanel viewer,
			final RandomAccessibleInterval<FloatType> heightField,
			final ScaleAndTranslation heightFieldTransform,
			final InputTriggerConfig config) {

		this.viewer = viewer;
		this.heightField = heightField;
		extendedHeightField = Views.extendBorder(this.heightField);
		this.heightFieldTransform = heightFieldTransform;
		brushOverlay = new BrushOverlay(viewer);
		brushMask = createMask(brushSigma, heightFieldTransform.getScale(0));
		inputAdder = config.inputTriggerAdder(inputTriggerMap, "brush");

		brushLocation = new RealPoint(3);

		new Push( "push", "SPACE button1" ).register();
		new Pull( "erase", "SPACE button2", "SPACE button3" ).register();
		new ChangeBrushRadius( "change brush radius", "SPACE scroll" ).register();
		new MoveBrush( "move brush", "SPACE" ).register();
	}

	private void setCoordinates(final int x, final int y) {

		brushLocation.setPosition(x, 0);
		brushLocation.setPosition(y, 1);
		brushLocation.setPosition(0, 2);

		viewer.displayToGlobalCoordinates(brushLocation);

		heightFieldTransform.applyInverse(brushLocation, brushLocation);
	}

	private abstract class SelfRegisteringBehaviour implements Behaviour {

		private final String name;

		private final String[] defaultTriggers;

		protected String getName() {

			return name;
		}

		public SelfRegisteringBehaviour(final String name, final String... defaultTriggers) {

			this.name = name;
			this.defaultTriggers = defaultTriggers;
		}

		public void register() {

			behaviourMap.put(name, this);
			inputAdder.put(name, defaultTriggers);
		}
	}

	private abstract class AbstractPaintBehavior extends SelfRegisteringBehaviour implements DragBehaviour {

		public AbstractPaintBehavior(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		protected void paint(final RealLocalizable coords)
		{
			final IntervalView<FloatType> heightFieldInterval = Views.offsetInterval(
					extendedHeightField,
					new long[] {
							Math.round(coords.getDoublePosition(0) - (brushMask.dimension(0) / 2)),
							Math.round(coords.getDoublePosition(1) - (brushMask.dimension(1) / 2))},
					new long[] {
							brushMask.dimension(0),
							brushMask.dimension(1)
					});

			final ArrayCursor<DoubleType> maskCursor = brushMask.cursor();
			final Cursor<FloatType> heightFieldCursor = heightFieldInterval.cursor();

			while (maskCursor.hasNext()) {
				final FloatType v = heightFieldCursor.next();
				v.setReal(maskCursor.next().getRealDouble() * getValue() + v.getRealDouble());
			}
		}

		protected void paint(final int x, final int y) {

			setCoordinates(x, y);
			paint(brushLocation);
		}

		abstract protected double getValue();

		@Override
		public void init( final int x, final int y ) {

			synchronized (this) {

				oX = x;
				oY = y;
			}

			paint(x, y);

			viewer.requestRepaint();
		}

		@Override
		public void drag(final int x, final int y) {

			brushOverlay.setPosition(x, y);
			paint(x, y);

			viewer.requestRepaint();
		}

		@Override
		public void end(final int x, final int y) {}
	}

	private class Push extends AbstractPaintBehavior {

		public Push(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		protected double getValue() {

			return 0.25;
		}
	}

	private class Pull extends AbstractPaintBehavior {

		public Pull(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		protected double getValue() {

			return -0.25;
		}
	}

	private class ChangeBrushRadius extends SelfRegisteringBehaviour implements ScrollBehaviour {

		public ChangeBrushRadius(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void scroll(final double wheelRotation, final boolean isHorizontal, final int x, final int y) {

			if (!isHorizontal) {
				if (wheelRotation < 0)
					brushSigma *= 1.1;
				else if (wheelRotation > 0)
					brushSigma = Math.max(1, brushSigma * 0.9);

				brushOverlay.setRadius((int)Math.round(brushSigma));
				brushMask = createMask(brushSigma, heightFieldTransform.getScale(0));
				viewer.getDisplay().repaint();
			}
		}
	}

	private class MoveBrush extends SelfRegisteringBehaviour implements DragBehaviour {

		public MoveBrush(final String name, final String... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void init(final int x, final int y) {

			brushOverlay.setPosition(x, y);
			brushOverlay.setVisible(true);
			viewer.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR));
			viewer.getDisplay().repaint();
		}

		@Override
		public void drag(final int x, final int y) {

			brushOverlay.setPosition(x, y);
		}

		@Override
		public void end(final int x, final int y) {

			brushOverlay.setVisible(false);
			viewer.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.DEFAULT_CURSOR));
			viewer.getDisplay().repaint();
		}
	}
}
