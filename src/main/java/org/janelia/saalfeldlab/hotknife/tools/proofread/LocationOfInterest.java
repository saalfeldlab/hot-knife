package org.janelia.saalfeldlab.hotknife.tools.proofread;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;

/**
 * Encapsulates data for one location of interest.
 *
 * @author Eric Trautman
 */
public class LocationOfInterest {

	public static AtomicInteger id = new AtomicInteger( 1 );
    private final String transformString;
    private final double[] transform;
    private String notes;

    @SuppressWarnings("unused")
    private LocationOfInterest() {
        this(null);
    }

    public LocationOfInterest(final AffineTransform3D affineTransform3D ) {

        if (affineTransform3D != null) {

        	/*
            final RealPoint gPos = new RealPoint(3);
            final RealPoint lPos = new RealPoint(3);
            lPos.setPosition(x, 0);
            lPos.setPosition(y, 1);
            affineTransform3D.applyInverse(gPos, lPos);*/

            this.transformString = "issue " + id.getAndIncrement();
            										/* String.format("(%6.0f,%6.0f,%6.0f)",
                                                   gPos.getDoublePosition(0),
                                                   gPos.getDoublePosition(1),
                                                   gPos.getDoublePosition(2));*/

            this.transform = affineTransform3D.getRowPackedCopy();

        } else {
            this.transformString = null;
            this.transform = null;
        }
        
        this.notes = null;
    }

    public AffineTransform3D getTransform()
            throws IllegalArgumentException {

        final AffineTransform3D value;
        if (transform == null) {
            value = null;
        } else if (transform.length == 12) {
            value = new AffineTransform3D();
            value.set(transform);
        } else {
            throw new IllegalArgumentException("transform array has " + transform.length +
                                               " values but should have 12");
        }
        return value;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(final String notes) {
        this.notes = notes;
    }

    @Override
    public String toString() {
        return transformString;
    }

    public static void saveListToFile(final List<LocationOfInterest> list,
                                      final String filePath)
            throws IOException {
        final File file = new File(filePath).getAbsoluteFile();
        try (final FileWriter writer = new FileWriter(file)) {
            GSON.toJson(list, writer);
            writer.flush();
        }
        System.out.println("saved " + list.size() + " locations to " + file);
    }

    public static List<LocationOfInterest> loadListFromFile(final String filePath)
            throws IOException {
        final File file = new File(filePath).getAbsoluteFile();
        final Type listType = new TypeToken<ArrayList<LocationOfInterest>>(){}.getType();
        final List<LocationOfInterest> list;
        try (final FileReader fileReader = new FileReader(file)) {
            list = GSON.fromJson(fileReader, listType);
        }
        System.out.println("loaded " + list.size() + " locations from " + file);
        return list;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
}
