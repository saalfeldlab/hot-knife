package org.janelia.saalfeldlab.hotknife.cost;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import net.imagej.ImageJ;
import net.imagej.patcher.LegacyInjector;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

public class Visualize3DCrop {
static String n5Path = "/home/kharrington/Data/portableVNC.n5/";

    static String dataset = "/z33/v2_acquire_1_7270_sp2___20200804_184632_s0_crop001";

    //static String costDataset = "/cost/Sec14___20200106_085015/s0";

    static boolean withGraphics = true;

    static {
		LegacyInjector.preinit();
	}

    public static void main(String... args) throws IOException, ExecutionException, InterruptedException {


        ij.ImageJ ij1 = new ij.ImageJ();
        ij1.setIconImage(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB));

        ImageJ ij = new ImageJ();
        if( withGraphics )
            ij.ui().showUI();

        N5Reader n5r = new N5FSReader(n5Path);

        RandomAccessibleInterval<UnsignedByteType> volume = N5Utils.open(n5r, dataset);
        //RandomAccessibleInterval<UnsignedByteType> cost = N5Utils.open(n5r, costDataset);

        Bdv bdv =
                BdvFunctions.show(
                        Converters.convert(
                                volume,
                                (a,b) -> b.set(ARGBType.rgba(a.get(), a.get(), a.get(), 1)),
                                new ARGBType()),
                        "input",
                        Bdv.options());

        ImageJFunctions.show(volume);

//        bdv =
//                BdvFunctions.show(
//                        Converters.convert(
//                                cost,
//                                (a,b) -> b.set(ARGBType.red(a.get())),
//                                new ARGBType()),
//                        "cost",
//                        Bdv.options().addTo( bdv ));
//
//        ImageJFunctions.show(cost);


        //bdv.getConverterSetups().get( 1 ).setDisplayRange( 0, 255 );
		//bdv.getConverterSetups().get( 1 ).setColor(new ARGBType(0x00ff0000));

        //bdv.getConverterSetups().get(1).setColor(new ARGBType(ARGBType.rgba(1, 0, 1, 1)));
    }

}
