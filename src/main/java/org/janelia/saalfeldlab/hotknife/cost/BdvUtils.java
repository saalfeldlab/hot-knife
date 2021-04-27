package org.janelia.saalfeldlab.hotknife.cost;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;

public class BdvUtils {

    public static Bdv showInputAndCost(RandomAccessibleInterval<UnsignedByteType> input, RandomAccessibleInterval<UnsignedByteType> cost) {

        Bdv bdv =
                BdvFunctions.show(
                        Converters.convert(
                                input,
                                (a,b) -> b.set(ARGBType.rgba(a.get(), a.get(), a.get(), 1)),
                                new ARGBType()),
                        "input",
                        Bdv.options());

        bdv =
                BdvFunctions.show(
                        Converters.convert(
                                cost,
                                (a,b) -> b.set(ARGBType.red(a.get())),
                                new ARGBType()),
                        "cost",
                        Bdv.options().addTo( bdv ));

        return bdv;
    }

}
