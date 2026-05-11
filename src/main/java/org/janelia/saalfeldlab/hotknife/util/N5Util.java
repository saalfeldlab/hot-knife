package org.janelia.saalfeldlab.hotknife.util;

import java.io.IOException;

import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

public class N5Util {

    public static N5Reader createN5Reader(final String n5Path) {
        return new N5Factory().openReader(n5Path);
    }

    public static N5Writer createN5Writer(final String n5Path) {
        return new N5Factory().openWriter(n5Path);
    }

    public static void verifyDatasetAndAttributesExist(final N5Reader n5Reader,
                                                       final String dataset) throws IOException {
        if (! n5Reader.datasetExists(dataset)) {
            throw new IOException("dataset '" + dataset + "' does not exist within " + n5Reader.getURI());
        }
        final DatasetAttributes attributes = n5Reader.getDatasetAttributes(dataset);
        if (attributes == null) {
            throw new IOException("attributes missing for dataset '" + dataset + "' within " + n5Reader.getURI());
        }
    }
}
