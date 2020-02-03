package org.janelia.saalfeldlab.hotknife;

import ch.systemsx.cisd.hdf5.HDF5Factory;
import ch.systemsx.cisd.hdf5.IHDF5Reader;
import mpicbg.util.RealSum;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.converter.Converters;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubsampleHeightmap {

    public static String minFaceDatasetName = "/heightmaps/min";
	public static String maxFaceDatasetName = "/heightmaps/max";

	static ExecutorService exec = Executors.newFixedThreadPool(48);

    public static void main(String... args) throws IOException, ExecutionException, InterruptedException {
        int costStep = 6;
        String sectionName = ( args.length > 0 ? args[0] : "Sec06" );
        String n5Path = "/nrs/flyem/tmp/VNC.n5";

        String minFaceDatasetName = "/heightmaps/min";
        String maxFaceDatasetName = "/heightmaps/max";

        // Derived values
        String flattenDataset = "/flatten/" + sectionName + "_original";
        String minhdf5 = "/nrs/flyem/alignment/Z1217-19m/VNC/" + sectionName + "/" + sectionName + "-bottom.h5";
        String maxhdf5 = "/nrs/flyem/alignment/Z1217-19m/VNC/" + sectionName + "/" + sectionName + "-top.h5";

        // Min heightmap
        processHeightmap(minhdf5, costStep, n5Path, flattenDataset + minFaceDatasetName);
        // Max heightmap
        processHeightmap(maxhdf5, costStep, n5Path, flattenDataset + maxFaceDatasetName);

        System.out.println("Done.");
        System.exit(0);
    }

    private static void processHeightmap(String hdf5name, int costStep, String n5Path, String outDataset) throws IOException, ExecutionException, InterruptedException {
        IHDF5Reader hdf5Reader = HDF5Factory.openForReading(hdf5name);
        final N5HDF5Reader hdf5 = new N5HDF5Reader(hdf5Reader, new int[]{1024, 1024});
        final RandomAccessibleInterval<FloatType> floats = N5Utils.open(hdf5, "/volume");

        RandomAccessibleInterval<DoubleType> minConv =
                Converters.convert(
                        Views.subsample(floats, costStep, costStep),
                        (a, b) -> b.setReal(a.getRealDouble()),
                        new DoubleType());

        System.out.println("Writing heightmap: " + n5Path + "/" + outDataset);
        // Using an HDF5 RAI can be slow when computing transforms, write to N5 and use that
        N5FSWriter n5w = new N5FSWriter(n5Path);
        N5Utils.save(minConv, n5w, outDataset, new int[]{1024, 1024}, new RawCompression(), exec);

        DoubleType minMean = getAvgValue(minConv);
        n5w.setAttribute(outDataset, "mean", minMean.getRealDouble());
    }

    public static DoubleType getAvgValue(RandomAccessibleInterval<DoubleType> rai) {
        RandomAccess<DoubleType> ra = rai.randomAccess();
        long[] pos = new long[rai.numDimensions()];
        for( int k = 0; k < pos.length; k++ ) pos[k] = 0;
        ra.localize(pos);

        long count = 0;
        final RealSum sum = new RealSum();
        for( pos[0] = 0; pos[0] < rai.dimension(0); pos[0]++ ) {
            for( pos[1] = 0; pos[1] < rai.dimension(1); pos[1]++ ) {
                ra.setPosition(pos);
                final double v = ra.get().getRealDouble();
                if( !Double.isNaN(v) && !Double.isInfinite(v) ) {
                	sum.add( v );
                    count++;
                }
            }
        }

        return new DoubleType(sum.getSum() / count);
    }

}
