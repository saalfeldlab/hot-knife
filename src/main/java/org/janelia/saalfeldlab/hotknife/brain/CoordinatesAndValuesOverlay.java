package org.janelia.saalfeldlab.hotknife.brain;

import bdv.viewer.Interpolation;
import bdv.viewer.OverlayRenderer;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;
import bdv.viewer.ViewerState;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.imglib2.RealPoint;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;

// Overlay values of active sources.
public class CoordinatesAndValuesOverlay implements OverlayRenderer
{
	private ViewerPanel viewer;

	public CoordinatesAndValuesOverlay( final ViewerPanel viewer )
	{
		this.viewer = viewer;
	}

	@Override
	public void drawOverlays( final Graphics gg )
	{
		final Graphics2D g = ( Graphics2D ) gg;

		final RealPoint lPos = new RealPoint( 3 );
		final RealPoint gPos = new RealPoint( 3 );
		viewer.getGlobalMouseCoordinates( gPos );
		final String mousePosGlobalString = String.format( "g: (%6.1f,%6.1f,%6.1f)", gPos.getDoublePosition( 0 ), gPos.getDoublePosition( 1 ), gPos.getDoublePosition( 2 ) );

		g.setFont( new Font( "Monospaced", Font.PLAIN, 16 ) );
		g.setColor( Color.white );
		final int x0 = ( int ) g.getClipBounds().getWidth() - 400;
		final int y0 = ( int ) g.getClipBounds().getHeight() - 100;
		final int lh = 16;
		int ly = 0;

		// TODO: use text overlay stuff from BDV (with proper scaling)
		//		 https://github.com/bigdataviewer/bigdataviewer-core/blob/54e8e538fd355c15c4a10790e19cd663a204ee96/src/main/java/bdv/viewer/ViewerPanel.java#L518-L530
		//		 font = UIUtils.getFont( "monospaced.small.font" );
		//		 UIUtils.drawString( g, TOP_RIGHT, 1, mousePosGlobalString );

		final ViewerState state = viewer.state();
		final int timepoint = state.getCurrentTimepoint();
		final Interpolation interpolation = state.getInterpolation();

		final List<SourceAndConverter<?>> sources = new ArrayList<>(state.getVisibleAndPresentSources());
		sources.sort(state.sourceOrder());
		int si = 0;
		for ( SourceAndConverter< ? > source : sources )
		{
			final AffineTransform3D transform = new AffineTransform3D();
			source.getSpimSource().getSourceTransform( timepoint, 0, transform );
			transform.applyInverse( lPos, gPos );
			final String mousePosLocalString =
					String.format( "%s: (%6.0f, %6.0f, %6.0f)",
							"" + si,
							gPos.getDoublePosition( 0 ),
							gPos.getDoublePosition( 1 ),
							gPos.getDoublePosition( 2 ));
			if ( si == 0 )
				g.drawString( mousePosLocalString, x0, y0 + lh * ly++ );

			final RealRandomAccessible< ? > rra = source.getSpimSource().getInterpolatedSource( timepoint, 0, interpolation );
			final Object value = rra.getAt( lPos );

			final Object type = source.getSpimSource().getType();
			final String strValue;
			if (type instanceof IntegerType) {
				strValue = String.format("%6d", ((IntegerType) value).getIntegerLong());
			} else if (type instanceof RealType) {
				strValue = String.format("%6.3f", ((RealType) value).getRealDouble());
			} else {
				strValue = "???";
			}
			g.drawString(strValue, x0, y0 + lh * ly++ );

			++si;
		}
	}

	@Override
	public void setCanvasSize( final int width, final int height )
	{
	}
}
