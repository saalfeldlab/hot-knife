package org.janelia.saalfeldlab.hotknife.tools.proofread;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractCellEditor;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableColumn;

import bdv.viewer.ViewerPanel;
import bdv.viewer.animate.SimilarityTransformAnimator;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Panel containing locations user interface components.
 *
 * @author Eric Trautman
 */
public class LocationsPanel
        extends JPanel {

    public static final String KEY = "LocationPanel";

    public LocationsPanel(final ViewerPanel viewer) {
        super(new BorderLayout());

        final LocationTableModel tableModel = new LocationTableModel();

        final JTable locationTable = new JTable(tableModel);

        final TableColumn coordinatesColumn = locationTable.getColumn(COORDINATES_COLUMN_HEADER);
        coordinatesColumn.setCellRenderer(new CoordinatesCellRenderer());
        coordinatesColumn.setPreferredWidth(180);

        final GoButton goButton = new GoButton(locationTable, tableModel, viewer);
        final RemoveButton removeButton = new RemoveButton(locationTable, tableModel);
        final LocationActionsPanel actionsPanel = new LocationActionsPanel(goButton, removeButton);
        final LocationActionsCellRenderer renderer = new LocationActionsCellRenderer(actionsPanel);
        final LocationActionsCellEditor editor = new LocationActionsCellEditor(actionsPanel);
        final TableColumn actionsColumn = locationTable.getColumn(ACTIONS_COLUMN_HEADER);
        actionsColumn.setCellRenderer(renderer);
        actionsColumn.setCellEditor(editor);

        final JScrollPane scrollPaneTable = new JScrollPane(locationTable);
        scrollPaneTable.setBorder(new EmptyBorder(0, 0, 0, 0 ));

        final JButton addCurrentLocationButton = new JButton("Add Location");
        addCurrentLocationButton.addActionListener(e -> {
            final AffineTransform3D t = new AffineTransform3D();
            viewer.state().getViewerTransform(t);
            final double cX = viewer.getDisplay().getWidth() / 2.0;
            final double cY = viewer.getDisplay().getHeight() / 2.0;
            t.set( t.get( 0, 3 ) - cX, 0, 3 );
            t.set( t.get( 1, 3 ) - cY, 1, 3 );
            tableModel.add(new LocationOfInterest(t, 0, 0));
        });

        final JButton loadButton = new JButton("Load");
        loadButton.addActionListener(e -> {
            final JFileChooser fileChooser = new JFileChooser();
            fileChooser.showDialog(null, "Load File");

            final File selectedFile = fileChooser.getSelectedFile();

            if (selectedFile != null) {
                try {
                    tableModel.addAll(LocationOfInterest.loadListFromFile(selectedFile.getAbsolutePath()),
                                      true);
                } catch (IOException ioe) {
                    throw new IllegalArgumentException("failed to save " + selectedFile.getAbsolutePath(), ioe);
                }

            }
        });

        final JButton saveButton = new JButton("Save");
        saveButton.addActionListener(e -> {
            final JFileChooser fileChooser = new JFileChooser();
            fileChooser.showDialog(null, "Save File");

            final File selectedFile = fileChooser.getSelectedFile();

            if (selectedFile != null) {
                try {
                    LocationOfInterest.saveListToFile(tableModel.getLocationList(), selectedFile.getAbsolutePath());
                } catch (IOException ioe) {
                    throw new IllegalArgumentException("failed to save " + selectedFile.getAbsolutePath(), ioe);
                }

            }
        });

        final JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 1, 10));
        actionPanel.add(addCurrentLocationButton);
        actionPanel.add(loadButton);
        actionPanel.add(saveButton);

        this.add(actionPanel, BorderLayout.NORTH);
        this.add(scrollPaneTable, BorderLayout.CENTER);
    }

    private static class LocationTableModel
            extends AbstractTableModel {

        private final List<LocationOfInterest> locationList;

        public LocationTableModel() {
            this.locationList = new ArrayList<>();
        }

        @Override
        public int getRowCount() {
            return locationList.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? COORDINATES_COLUMN_HEADER : ACTIONS_COLUMN_HEADER;
        }

        @Override
        public Object getValueAt(final int rowIndex,
                                 final int columnIndex) {
            Object value = columnIndex;
            if (columnIndex == 0) {
                value = locationList.get(rowIndex);
            }
            return value;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1;
        }

        public List<LocationOfInterest> getLocationList() {
            return locationList;
        }

        public LocationOfInterest getLocationForRow(final int rowIndex) {
            LocationOfInterest location = null;
            if ((rowIndex >= 0) && (rowIndex < locationList.size())) {
                location = locationList.get(rowIndex);
            }
            return location;
        }

        public void add(final LocationOfInterest location) {
            locationList.add(location);
            this.fireTableDataChanged();
        }

        public void addAll(final List<LocationOfInterest> locations,
                           boolean clearExisting) {
            if (clearExisting) {
                this.locationList.clear();
            }
            this.locationList.addAll(locations);
            this.fireTableDataChanged();
        }

        public void removeRow(int rowIndex) {
            System.out.println("remove " + rowIndex);
            if ((rowIndex >= 0) && (rowIndex < locationList.size())) {
                locationList.remove(rowIndex);
                this.fireTableDataChanged();
            }
        }

    }

    private static final class GoButton extends JButton {
        public GoButton(final JTable table,
                        final LocationTableModel tableModel,
                        final ViewerPanel viewer) {
            super("Go");

            setPreferredSize(new Dimension(60, 20));
            setToolTipText("navigate to this location in viewer");

            addActionListener(e -> {
                final int selectedRow = table.getSelectedRow();
                final LocationOfInterest location = tableModel.getLocationForRow(selectedRow);
                if (location != null) {
                    final AffineTransform3D locationTransform = location.getTransform();
                    final AffineTransform3D viewerCenter = new AffineTransform3D();
                    viewer.state().getViewerTransform(viewerCenter);
                    final double cX = viewer.getDisplay().getWidth() / 2.0;
                    final double cY = viewer.getDisplay().getHeight() / 2.0;
                    viewerCenter.set(viewerCenter.get(0, 3) - cX, 0, 3);
                    viewerCenter.set(viewerCenter.get(1, 3) - cY, 1, 3);
                    viewer.setTransformAnimator(
                            new SimilarityTransformAnimator(viewerCenter, locationTransform, cX, cY, 300));
                    viewer.toggleInterpolation();
                }
                table.getCellEditor().stopCellEditing();
            });

        }
    }

    private static class RemoveButton extends JButton {
        public RemoveButton(final JTable table,
                            final LocationTableModel tableModel) {
            final URL iconUrl = LocationsPanel.class.getResource("/bdv/ui/remove-location.png");
            if (iconUrl == null) {
                setText("Remove");
            } else {
                final ImageIcon icon = new ImageIcon(iconUrl);
                setIcon(icon);
                setPreferredSize(new Dimension(icon.getIconWidth(), icon.getIconHeight()));
            }
            setToolTipText("remove this location from the list");
            addActionListener(e -> {
                tableModel.removeRow(table.getSelectedRow());
                table.getCellEditor().stopCellEditing();
            });

        }
    }

    private static class LocationActionsPanel
            extends JPanel {

        public LocationActionsPanel(final GoButton goButton,
                                    final RemoveButton removeButton) {
            super(new BorderLayout());
            setBackground(Color.WHITE);
            add(goButton, BorderLayout.LINE_START);
            add(removeButton, BorderLayout.LINE_END);
        }
    }

    private static class CoordinatesCellRenderer
            extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(final JTable table,
                                                       final Object value,
                                                       final boolean isSelected,
                                                       final boolean hasFocus,
                                                       final int row,
                                                       final int column) {
            return new JLabel("<html><pre>" + value + "</pre></html>");
        }
    }

    private static class LocationActionsCellRenderer
            extends DefaultTableCellRenderer {

        private final LocationActionsPanel panel;

        public LocationActionsCellRenderer(final LocationActionsPanel panel) {
            this.panel = panel;
        }

        @Override
        public Component getTableCellRendererComponent(final JTable table,
                                                       final Object value,
                                                       final boolean isSelected,
                                                       final boolean hasFocus,
                                                       final int row,
                                                       final int column) {
            return panel;
        }
    }

    private static class LocationActionsCellEditor extends AbstractCellEditor
            implements TableCellEditor {

        private final LocationActionsPanel panel;

        public LocationActionsCellEditor(final LocationActionsPanel panel) {
            this.panel = panel;
        }

        @Override
        public Component getTableCellEditorComponent(final JTable table,
                                                     final Object value,
                                                     final boolean isSelected,
                                                     final int row,
                                                     final int column) {
            return panel;
        }

        @Override
        public Object getCellEditorValue() {
            return null;
        }
    }

    private static final String COORDINATES_COLUMN_HEADER = "Coordinates";
    private static final String ACTIONS_COLUMN_HEADER = "Actions";

}
