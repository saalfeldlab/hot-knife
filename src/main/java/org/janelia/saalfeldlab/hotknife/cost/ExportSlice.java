package org.janelia.saalfeldlab.hotknife.cost;

import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import java.io.IOException;

public class ExportSlice {

    public static void main(String... args) throws IOException {

        String n5Path = args[0];
        String inDataset = args[1];

        N5FSReader n5 = new N5FSReader(n5Path);

        // step size
        long ss = 1;

        // zcorr

        System.out.println("Reading n5: " + n5Path + inDataset);

        RandomAccessibleInterval<UnsignedByteType> zcorr = N5Utils.open(n5, inDataset, new UnsignedByteType());
        System.out.println("Original size:" + (Interval) zcorr);

        // add ability to press a key and save the current view into an imagej...
        // or just ij show and save manually, thats easier

        ImageJFunctions.show(zcorr, n5Path + inDataset);
    }

}
