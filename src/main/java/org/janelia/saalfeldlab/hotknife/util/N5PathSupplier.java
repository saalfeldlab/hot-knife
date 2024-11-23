package org.janelia.saalfeldlab.hotknife.util;

import java.io.IOException;

import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;

/**
 * Serializable downsample supplier for spark.
 *
 * @author Eric Trautman
 */
public class N5PathSupplier implements N5WriterSupplier {
    private final String path;
    public N5PathSupplier(final String path) {
        this.path = path;
    }
    @Override
    public N5Writer get()
            throws IOException {
        return new N5Factory().openWriter( StorageFormat.N5, path );//new N5FSWriter(path);
    }
}

