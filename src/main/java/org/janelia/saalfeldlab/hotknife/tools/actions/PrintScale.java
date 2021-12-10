package org.janelia.saalfeldlab.hotknife.tools.actions;

import java.awt.Cursor;
import java.awt.event.ActionEvent;

import org.janelia.saalfeldlab.hotknife.tools.DisplayScaleOverlay;

import bdv.viewer.ViewerPanel;

public class PrintScale extends NamedTriggerAction {

    private static final long serialVersionUID = -7884038268749788208L;

    final ViewerPanel viewer;
    DisplayScaleOverlay overlay;
    boolean isVisible;

    public PrintScale(final ViewerPanel viewer) {
        this("print current scaling", viewer, "ctrl 2");
    }

    public PrintScale(final String name, final ViewerPanel viewer, final String... defaultTriggers) {

        super(name, defaultTriggers);

        this.viewer = viewer;
        this.overlay = null;
        this.isVisible = false;
    }

    @Override
    public void actionPerformed(final ActionEvent event) {

        synchronized (viewer) {

            viewer.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

            if (overlay == null) {
                this.overlay = new DisplayScaleOverlay();
                viewer.renderTransformListeners().add(overlay);
                viewer.getDisplay().overlays().add(overlay);
            } else {
                overlay.toggleState();
            }

            viewer.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            viewer.requestRepaint();
        }
    }
}
