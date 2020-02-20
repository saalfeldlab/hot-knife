/**
 *
 */
package org.janelia.saalfeldlab.hotknife.tools;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import bdv.util.Affine3DHelpers;
import bdv.viewer.ViewerPanel;

/**
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 *
 */
public class SmoothBrushOverlay extends BrushOverlay {

	protected int radius2 = 2;

	public SmoothBrushOverlay(final ViewerPanel viewer) {

		super(viewer);
	}

	public void setRadius2(final int radius2) {

		this.radius2 = radius2;
	}

	@Override
	public void drawOverlays(final Graphics g) {

		if (visible) {

			final Graphics2D g2d = (Graphics2D)g;

			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2d.setComposite(AlphaComposite.SrcOver);

			final double scale;
			synchronized (viewer) {
				viewer.getState().getViewerTransform(viewerTransform);
				scale = Affine3DHelpers.extractScale(viewerTransform, 0);
			}
			final double scaledRadius = scale * radius;
			final double scaledRadius2 = scale * radius2;


			if (x + scaledRadius > 0 &&
					x - scaledRadius < width &&
					y + scaledRadius > 0 &&
					y - scaledRadius < height) {
				final int roundScaledRadius = (int)Math.round(scaledRadius);
				g2d.setColor(Color.YELLOW);
				g2d.setStroke(stroke);
				g2d.drawOval(x - roundScaledRadius, y - roundScaledRadius, 2 * roundScaledRadius + 1, 2 * roundScaledRadius + 1);
			}

			if (x + scaledRadius2 > 0 &&
					x - scaledRadius2 < width &&
					y + scaledRadius2 > 0 &&
					y - scaledRadius2 < height) {
				final int roundScaledRadius2 = (int)Math.round(scaledRadius2);
				g2d.setColor(Color.MAGENTA);
				g2d.setStroke(stroke);
				g2d.drawOval(x - roundScaledRadius2, y - roundScaledRadius2, 2 * roundScaledRadius2 + 1, 2 * roundScaledRadius2 + 1);
			}
		}
	}
}
