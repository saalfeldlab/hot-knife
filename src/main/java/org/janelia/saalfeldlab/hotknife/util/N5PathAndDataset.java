package org.janelia.saalfeldlab.hotknife.util;

import java.io.Serializable;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;

/**
 * An n5 path along with a dataset name.
 */
public class N5PathAndDataset implements Serializable {

    private final N5Path n5Path;
    private final String dataset;

    public N5PathAndDataset(final N5Path n5Path,
                            final String dataset) {
        this.n5Path = n5Path;
        this.dataset = dataset;
    }

    public N5PathAndDataset(final String n5Path,
                            final String dataset) {
        this(new N5Path(n5Path), dataset);
    }

    public N5Path getN5Path() {
        return n5Path;
    }

    public String getDataset() {
        return dataset;
    }

    public N5Reader openReader() {
        return n5Path.openReader();
    }

    public N5Writer openWriter() {
        return n5Path.openWriter();
    }

    @Override
    public String toString() {
        return "N5PathAndDataset{" + n5Path.getPath() + ", " + dataset + "}";
    }
}
