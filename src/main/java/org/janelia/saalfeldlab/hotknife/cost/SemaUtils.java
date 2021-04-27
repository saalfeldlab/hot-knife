package org.janelia.saalfeldlab.hotknife.cost;

import ij.ImagePlus;
import ij.plugin.FolderOpener;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.RealSum;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import java.util.Random;

public class SemaUtils {

    public static void copyRealInto(RandomAccessibleInterval<UnsignedByteType> cost, RandomAccessibleInterval<DoubleType> costDouble) {
		RandomAccess<UnsignedByteType> sRA = cost.randomAccess();
		RandomAccess<DoubleType> tRA = costDouble.randomAccess();

		long[] p = new long[3];
		for( p[0] = 0; p[0] < cost.dimension(0); p[0]++ ) {
			for( p[1] = 0; p[1] < cost.dimension(1); p[1]++ ) {
				for( p[2] = 0; p[2] < cost.dimension(2); p[2]++ ) {
					tRA.setPosition(p);
					sRA.setPosition(p);

					tRA.get().set(sRA.get().getRealDouble());
				}
			}
		}
	}

    public static Img<RealType> readAndFlipCost(String directory) {
        ImagePlus imp = FolderOpener.open(directory, " file=tif");
        //imp.setTitle("Target");
        // Reslice image
        //IJ.run(imp, "Reslice [/]...", "output=1.000 start=Top avoid");
        //imp = IJ.getImage();

        // Reslice using imglib
        Img<RealType> img = ImageJFunctions.wrapReal(imp);
        RandomAccessibleInterval<RealType> rotView = Views.invertAxis(Views.rotate(img, 1, 2),1);
        Img<RealType> resliceImg = img.factory().create(rotView);
        Cursor<RealType> rotCur = Views.iterable(rotView).cursor();
        Cursor<RealType> resCur = resliceImg.cursor();
        while( rotCur.hasNext() ) {
            rotCur.fwd();
            resCur.fwd();
            resCur.get().set(rotCur.get());
        }
        return resliceImg;
    }

    public static RandomAccessibleInterval<DoubleType> flipCost(RandomAccessibleInterval<? extends RealType> rai) {
        IntervalView<? extends RealType> rotView = Views.invertAxis(Views.rotate(rai, 1, 2), 1);

        return Converters.convert((RandomAccessibleInterval<RealType>) rotView, (a, b) -> b.setReal(a.getRealDouble()), new DoubleType());
    }

    public static DoubleType getAvgValue(RandomAccessibleInterval<FloatType> rai) {
        RandomAccess<FloatType> ra = rai.randomAccess();
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
    
    public static DoubleType getApproxAvgValue( final RandomAccessibleInterval<DoubleType> rai, final int numDraws)
    {
    	final int n = rai.numDimensions();
    	Random rnd = new Random( System.currentTimeMillis() );
    	RealSum sum = new RealSum();
    	final RandomAccess< DoubleType > ra = rai.randomAccess();
   
    	long count = 0;
    	
    	do
    	{
    		for ( int d = 0; d < n; ++d )
    			ra.setPosition( rnd.nextInt( (int)rai.dimension(d)) + (int)rai.min(d), d );
    		
    		final double v = ra.get().getRealDouble();
    		
    		if( !Double.isNaN(v) && !Double.isInfinite(v) )
    		{
    			sum.add(v);
                count++;
                System.out.println( count );
            }
    	}
    	while ( count < numDraws );
    	
    	return new DoubleType( sum.getSum() / count);
    }

//    public static DoubleType getAvgValue(Iterable<DoubleType> ii) throws Exception {
//        if( !ii.iterator().hasNext() ) {
//            throw new Exception("Iterable is empty");
//        }
//        DoubleType avg = (DoubleType) ii.iterator().next().copy();
//        long count = 0;
//        avg.setReal(0);
//        Iterator<DoubleType> it = ii.iterator();
//        DoubleType val;
//        while( it.hasNext() ) {
//            val = it.next();
//            avg.add(val);
//            count++;
//        }
//        avg.setReal(avg.getRealDouble()/count);
//        return avg;
//    }
}
