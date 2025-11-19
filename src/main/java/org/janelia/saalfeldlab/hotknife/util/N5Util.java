package org.janelia.saalfeldlab.hotknife.util;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

public class N5Util {

    public static N5Reader createN5Reader(final String n5Path) {
        return new N5Factory().openReader(N5Factory.StorageFormat.N5, n5Path);
    }

    public static N5Writer createN5Writer(final String n5Path) {
        return new N5Factory().openWriter(N5Factory.StorageFormat.N5, n5Path);
    }
}
