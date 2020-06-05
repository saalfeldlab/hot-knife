package org.janelia.saalfeldlab.hotknife.tools;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.AbstractNamedAction;
import org.scijava.ui.behaviour.util.InputActionBindings;

import bdv.util.Affine3DHelpers;
import bdv.viewer.ViewerPanel;
import bdv.viewer.animate.RotationAnimator;
import net.imglib2.Point;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.ui.OverlayRenderer;
import net.imglib2.ui.TransformListener;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class HeightFieldKeyActions {

	final protected ViewerPanel viewer;
	final protected RandomAccessibleInterval<FloatType> heightField;
	final protected double avg;

	final protected String n5Path;
	final protected String heightFieldDataset;

	// for keystroke actions
	private final ActionMap ksActionMap = new ActionMap();
	private final InputMap ksInputMap = new InputMap();
	private final KeyStrokeAdder ksKeyStrokeAdder;

	public HeightFieldKeyActions(
			final ViewerPanel viewer,
			final RandomAccessibleInterval<FloatType> heightField,
			final double avg,
			final String n5Path,
			final String heightFieldDataset,
			final InputTriggerConfig config,
			final InputActionBindings inputActionBindings) {

		this.viewer = viewer;
		this.heightField = heightField;
		this.avg = avg;
		this.n5Path = n5Path;
		this.heightFieldDataset = heightFieldDataset;

		ksKeyStrokeAdder = config.keyStrokeAdder(ksInputMap, "persistence");

		new SaveHeightField("save heightfield", "ctrl S").register();
		new Undo("undo", "ctrl Z").register();
		new GoToZero("go to z=0", "ctrl C").register();
		new DisplayZeroLine("display z=0", viewer, "ctrl 0").register();

		inputActionBindings.addActionMap("persistence", ksActionMap);
		inputActionBindings.addInputMap("persistence", ksInputMap);
	}

	private abstract class SelfRegisteringAction extends AbstractNamedAction {

		private static final long serialVersionUID = -1032489117210681503L;

		private final String[] defaultTriggers;

		public SelfRegisteringAction(final String name, final String... defaultTriggers) {
			super(name);
			this.defaultTriggers = defaultTriggers;
		}

		public void register() {
			put(ksActionMap);
			ksKeyStrokeAdder.put(name(), defaultTriggers);
		}
	}

	public void saveHeightField() throws IOException, InterruptedException, ExecutionException {

		System.out.print("Saving heightfield " + n5Path + ":/" + heightFieldDataset + " ... ");
		final ExecutorService exec = Executors.newFixedThreadPool(4);
		final N5FSWriter n5 = new N5FSWriter(n5Path);
		N5Utils.save(
				heightField,
				n5,
				heightFieldDataset,
				new int[] {1024, 1024},
				new GzipCompression(),
				exec);
		exec.shutdown();
		n5.setAttribute(heightFieldDataset, "avg", avg);
		System.out.println("done.");
	}

	private class GoToZero extends SelfRegisteringAction {

		private static final long serialVersionUID = 1679653174783245445L;

		public GoToZero(final String name, final String ... defaultTriggers) {
			super(name, defaultTriggers);
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

				final AffineTransform3D initialTransform = viewer.getDisplay().getTransformEventHandler().getTransform();

				//
				// simply set z to zero (not good)
				//

				// initialTransform.set(0, 3, 4);

				//
				// better, turn to xy, set to z=0, turn back
				//

				// xy plane quaternion
				final double[] qTarget = new double[] { 1, 0, 0, 0 };

				// quaternion of the current viewer transformation
				final double[] qTargetBack = new double[ 4 ];
				Affine3DHelpers.extractRotation( initialTransform, qTargetBack );

				// mouse coordinates
				final Point p = new Point( 2 );
				viewer.getMouseCoordinates( p );
				double centerX = p.getIntPosition( 0 );
				double centerY = p.getIntPosition( 1 );

				// use Tobias's code to compute the rotation around the point (amount == 1.0)
				final AffineTransform3D xyPlaneTransform = new RotationAnimator( initialTransform, centerX, centerY, qTarget, 300 ).get( 1 );

				// set z to 0.0
				xyPlaneTransform.set(0, 3, 4);

				// use Tobias's code to compute the rotation around the point back to the original orientation (amount == 1.0)
				final AffineTransform3D finalTransform = new RotationAnimator( xyPlaneTransform, centerX, centerY, qTargetBack, 300 ).get( 1 );

				//
				// update new transformation
				//
				viewer.getState().setViewerTransform(finalTransform);
				viewer.transformChanged(finalTransform);
				viewer.setCurrentViewerTransform( finalTransform );
				viewer.requestRepaint();

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
			}
		}
	}

	private class SaveHeightField extends SelfRegisteringAction {

		private static final long serialVersionUID = -7884038268749788208L;

		public SaveHeightField(final String name, final String ... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
				try {
					saveHeightField();
				} catch (final IOException | InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
			}
		}
	}

	private class Undo extends SelfRegisteringAction {

		private static final long serialVersionUID = -7208806278835605976L;

		public Undo(final String name, final String ... defaultTriggers) {

			super(name, defaultTriggers);
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
				try {
					final N5FSReader n5 = new N5FSReader(n5Path);
					if (n5.datasetExists(heightFieldDataset)) {
						final long[] dimensions = n5.getAttribute(heightFieldDataset, "dimensions", long[].class);
						if (dimensions[0] == heightField.dimension(0) && dimensions[1] == heightField.dimension(1)) {
							Util.copy(N5Utils.open(n5, heightFieldDataset), heightField);
						}
					}
				} catch (final IOException e) {
					e.printStackTrace();
				}
				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
				viewer.requestRepaint();
			}
		}
	}

	private class DisplayZeroLine extends SelfRegisteringAction {

		private static final long serialVersionUID = -7884038268749788208L;

		final ViewerPanel viewer;
		ZeroLineOverlay overlay;
		boolean isVisible;

		public DisplayZeroLine(final String name, final ViewerPanel viewer, final String ... defaultTriggers) {

			super(name, defaultTriggers);

			this.viewer = viewer;
			this.overlay = null;
			this.isVisible = false;
		}

		@Override
		public void actionPerformed(final ActionEvent event) {

			synchronized (viewer) {

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

				if ( overlay == null )
				{
					this.overlay = new ZeroLineOverlay( viewer );

					viewer.addRenderTransformListener( overlay );
					viewer.getDisplay().addOverlayRenderer( overlay );
				}
				else
				{
					overlay.toggleState();
				}

				viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
				viewer.requestRepaint();
			}
		}
	}

	private static class ZeroLineOverlay implements OverlayRenderer, TransformListener< AffineTransform3D >
	{
		private final AffineTransform3D viewerTransform;
		private final ViewerPanel viewer;
		private Color col = Color.green.darker();

		private boolean draw;

		public ZeroLineOverlay( final ViewerPanel viewer )
		{
			this.viewer = viewer;
			this.viewerTransform = new AffineTransform3D();
			this.draw = true;
		}

		@Override
		public void transformChanged( final AffineTransform3D transform )
		{
			viewerTransform.set( transform );
		}

		public void toggleState() { this.draw = !this.draw; }

		@Override
		public void drawOverlays( final Graphics g )
		{
			if ( !draw )
				return;

			final Graphics2D graphics = ( Graphics2D ) g;

			graphics.setColor( col );
			graphics.drawLine( 0, 0, 400, 200 );

			/*
			final double[] lPos = new double[ 3 ];
			final double[] gPos = new double[ 3 ];
			final AffineTransform3D transform = new AffineTransform3D();

			for ( final InterestPointSource pointSource : interestPointSources )
			{
				final HashMap< ? extends ViewId, ? extends Collection< ? extends RealLocalizable > > coordinates = pointSource.getLocalCoordinates( t );

				for ( final ViewId viewId : coordinates.keySet() )
				{
					pointSource.getLocalToGlobalTransform( viewId, t, transform );
					transform.preConcatenate( viewerTransform );

					for ( final RealLocalizable p : coordinates.get( viewId ) )
					{
						p.localize( lPos );
						transform.apply( lPos, gPos );
						final double size = getPointSize( gPos );
						final int x = ( int ) ( gPos[ 0 ] - 0.5 * size );
						final int y = ( int ) ( gPos[ 1 ] - 0.5 * size );
						final int w = ( int ) size;
						graphics.setColor( getColor( gPos ) );
						graphics.fillOval( x, y, w, w );
					}
				}
			}
			*/
		}

		@Override
		public void setCanvasSize( final int width, final int height )
		{}
	}
}
