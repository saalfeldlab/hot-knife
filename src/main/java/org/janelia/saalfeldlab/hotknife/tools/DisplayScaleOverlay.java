package org.janelia.saalfeldlab.hotknife.tools;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.text.DecimalFormat;

import bdv.viewer.OverlayRenderer;
import bdv.viewer.TransformListener;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.LinAlgHelpers;

public class DisplayScaleOverlay implements OverlayRenderer, TransformListener<AffineTransform3D> {

    private final DecimalFormat format = new DecimalFormat("0.####");
    private final AffineTransform3D viewerTransform;

    private boolean draw;

    public DisplayScaleOverlay() {

        this.viewerTransform = new AffineTransform3D();
        this.draw = true;
    }

    @Override
    public void setCanvasSize(final int width, final int height) {
    }

    @Override
    public void transformChanged(final AffineTransform3D transform) {

        viewerTransform.set(transform);
    }

    public void toggleState() {

        this.draw = !this.draw;
    }

    @Override
    public void drawOverlays(final Graphics g) {

        if (!draw)
            return;

        // scale=det(A)^(1/3);
        final double scale = computeScale(viewerTransform);

        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.setColor( Color.red );
        g.drawString(
                "s= " + format.format(scale) + " x",
                (int)g.getClipBounds().getWidth() - 100,
                (int)g.getClipBounds().getHeight() - 24);

        // TransformAwareBufferedImageOverlayRenderer t = null;
        // t.bufferedImage;
    }

    public static double computeScale(final AffineTransform3D t) {

        final double[] m = t.getRowPackedCopy();

        // scale=det(A)^(1/3);
        return Math.pow(LinAlgHelpers.det3x3(m[0], m[1], m[2], m[4], m[5], m[6], m[8], m[9], m[10]), 1.0 / 3.0);
    }

}
