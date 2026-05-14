package org.janelia.saalfeldlab.hotknife.util;

import java.io.Serializable;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.spark.supplier.N5ReaderSupplier;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;
import org.janelia.saalfeldlab.n5.universe.N5Factory;

/**
 * An n5 path string with convenience methods for reading and writing data that can be accessed
 * in Spark drivers and executors because the readers and writers are created locally.
 */
public class N5Path
        implements Serializable {

    final String path;

    public N5Path(final String path) {
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return "N5Path{" + path + "}";
    }

    public N5Reader openReader() {
        return new N5Factory().openReader(path);
    }

    public N5ReaderSupplier buildReaderSupplier() {
        return this::openReader;
    }

    public N5Writer openWriter() {
        return new N5Factory().openWriter(path);
    }

    public N5WriterSupplier buildWriterSupplier() {
        return this::openWriter;
    }

}
