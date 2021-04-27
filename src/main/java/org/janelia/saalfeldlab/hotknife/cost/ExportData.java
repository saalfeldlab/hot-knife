package org.janelia.saalfeldlab.hotknife.cost;

import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.SubsampleIntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import java.io.IOException;

public class ExportData {

    public static void main(String... args) throws IOException {

        String n5Path = args[0];
        String inDataset = args[1];
        String outN5Path = args[2];
        String outDataset = args[3];
        long x = Long.parseLong(args[4]);
        long y = Long.parseLong(args[5]);
        long maxX = Long.parseLong(args[6]);
        long maxY = Long.parseLong(args[7]);

        N5FSReader n5 = new N5FSReader(n5Path);
        N5Writer n5w = new N5FSWriter(outN5Path);

        // step size
        long ss = 1;

        // zcorr

        System.out.println("Reading n5: " + n5Path + inDataset);

        RandomAccessibleInterval<UnsignedByteType> zcorr = N5Utils.open(n5, inDataset, new UnsignedByteType());
        System.out.println("Original size:" + (Interval) zcorr);

        // zcorr crop
        FinalInterval cropInterval =
                new FinalInterval(
                        new long[]{ x, 0, y },
                        new long[]{ maxX - 1, zcorr.dimension(1) - 1, maxY - 1 });
        System.out.println("Cropping to: " + cropInterval);

        RandomAccessibleInterval<UnsignedByteType> crop = Views.interval(zcorr, cropInterval);
        SubsampleIntervalView<UnsignedByteType> subsample = Views.subsample(crop, 1, ss, 1);
        System.out.println("Subsampling to: " + subsample.dimension(1));

        System.out.println("Writing: " + outN5Path + outDataset);
        N5Utils.save(subsample, n5w, outDataset, new int[]{128, 128, 128}, new GzipCompression());
    }

}
