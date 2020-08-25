package org.janelia.saalfeldlab.hotknife.cost;

import net.imglib2.*;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.RealSum;
import net.imglib2.util.ValuePair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import java.util.ArrayList;
import java.util.List;

public class Utils {


    /**
     * Take the cumulative sum along dimension 0. Only designed and tested with 1D images.
     * @param diff
     * @return
     */
    public static IntervalView<DoubleType> cumulativeSum(RandomAccessibleInterval<FloatType> diff) {
        long[] min = new long[diff.numDimensions()];
        diff.min(min);

        IntervalView<DoubleType> cSum =
            Views.translate(
                ArrayImgs.doubles(diff.dimension(0)),
                min);
        RandomAccess<FloatType> diffRA = diff.randomAccess();

        Cursor<DoubleType> cSumCur = cSum.cursor();

        RealSum sum = new RealSum();
        //double sum = 0;

        while( cSumCur.hasNext() ) {
            cSumCur.fwd();
            diffRA.setPosition(cSumCur);
            sum.add(diffRA.get().get());
            cSumCur.get().set(sum.getSum());
            //sum += diffRA.get().get();
            //cSumCur.get().set(sum);
        }
        return cSum;
    }

    /**
     * Mutate values in column to correct for slope of intensity values across column
     * @param column
     * @param startIdx
     * @param endIdx
     * @param slope
     * @param indY
     */
    static void slopeCorrection(Img<UnsignedByteType> column, long startIdx, long endIdx, double slope, RandomAccessibleInterval<LongType> indY) {
        double addToEnd = (double) (-(endIdx - startIdx) * slope);
        double numIndices = (endIdx - startIdx + 1);

        Cursor<UnsignedByteType> cCur = column.localizingCursor();
        Cursor<LongType> indYCur = Views.iterable(indY).cursor();// Check this may not be safe due to iteration order issues
        while( cCur.hasNext() ) {
            cCur.fwd();
            //long y = indYCur.get().get();
            long y = cCur.getLongPosition(0);
            int val = (int) (cCur.get().get() + ((double) (y - startIdx)) / numIndices * addToEnd);
            //System.out.println(cCur.get().get() + " " + val);
            val = Math.min(val, 255);
            cCur.get().set(val);
        }
    }

    /**
     * Compute the slope of the maskedImage
     * @param maskedImage
     * @param indYRA
     * @param minRow
     * @param maxRow
     * @param minCol
     * @param maxCol
     * @param bandSize
     * @param startIdx
     * @param slopeCorrBandFactor
     * @param minSlope
     * @param maxSlope
     * @return slope
     */
    static public double slopeCalc(
            RandomAccessibleInterval<UnsignedByteType> maskedImage,
            RandomAccessible<LongType> indYRA,
            long minRow,
            long maxRow,
            long minCol,
            long maxCol,
            int bandSize,
            double startIdx,
            float slopeCorrBandFactor,
            float minSlope,
            float maxSlope) {

        double meanI = 0.5 * (2 * startIdx + slopeCorrBandFactor * bandSize);

        Interval interval = new FinalInterval(
                new long[]{minCol, minRow},
                new long[]{maxCol - 1, maxRow - 1});

        maskedImage = Views.interval(maskedImage, interval);
        RandomAccessibleInterval<LongType> indY = Views.interval(indYRA, interval);

        List<Integer> vals = new ArrayList<>();
        RandomAccessibleInterval<LongType> localIndY =
                Converters.convert(indY, (a, b) -> b.set((long) (a.get() - meanI)), new LongType());

        Cursor<UnsignedByteType> miCur = Views.iterable(maskedImage).cursor();
        while( miCur.hasNext() ){
            vals.add( miCur.next().get() );
        }
        //vals.sort(Integer::compare);
        vals.sort(Integer::compareTo);

        if( vals.size() == 0 )
            return 0;

        int slopeCorrMedian = vals.get(vals.size() / 2);// THIS VALUE IS WRONG currently

        RealSum en = new RealSum();
        miCur = Views.iterable(maskedImage).localizingCursor();
        RandomAccess<LongType> localIndYRA = localIndY.randomAccess();
        while( miCur.hasNext() ){
            miCur.fwd();
            localIndYRA.setPosition(miCur);

            en.add(localIndYRA.get().get() * ( miCur.get().get() - slopeCorrMedian ));
        }

        RealSum div = new RealSum();
        Cursor<LongType> localIndYCur = Views.iterable(localIndY).cursor();
        while( localIndYCur.hasNext() ) {
            localIndYCur.fwd();
            div.add( Math.pow(localIndYCur.get().get(), 2) );
        }

        double slope = en.getSum() / div.getSum();
        return Math.max( Math.min( slope, maxSlope ), minSlope );
    }

    public static Pair<Double, Double> calcAverage(int idx, double avg, double median, double stddev, double medianContra, double iqrContra) {
        double[] zScore = new double[]{1.0, 1.65, 1.96, 2.61, 3.0};
        double belowAvg;
        if( idx < 5 )
            belowAvg = Math.min( avg - idx - 1, median - zScore[idx] * stddev );
        else
            belowAvg = Math.min( median - 4.0 * stddev, medianContra + 1.5 * iqrContra );
        if( idx == 6 )
            avg = Math.min( avg, medianContra + 1.5 * iqrContra + Math.max(1, stddev) );
        return new ValuePair<>(avg, belowAvg);
    }

    /**
     * Take the difference of all values from some constant and return the result in an Img. Only designed and tested with 1D images.
     * Also filters values to be within 0.5 - 254.5
     * @param column
     * @param from
     * @param maxDiff
     * @return
     */
    public static Img<FloatType> differenceFrom(RandomAccessibleInterval<UnsignedByteType> column, double from, double maxDiff, long startIdx, long endIdx) {
        Img<FloatType> diff = ArrayImgs.floats((long) (endIdx - startIdx + 1));

        //IntervalView<UnsignedByteType> alignedColumn = Views.translate(column, startIdx);

        Cursor<FloatType> daCur = diff.localizingCursor();
        RandomAccess<UnsignedByteType> colRA = column.randomAccess();

        long[] pos = new long[1];

        while( daCur.hasNext() ) {
            daCur.fwd();
            daCur.localize(pos);
            colRA.setPosition(pos[0] + startIdx, 0);
            double val = colRA.get().get();
            double da = from - val;

            //System.out.println("differenceFrom: " + val + " " + da);

            if( val < 0.5 ) da = 0;
            if( val > 254.5 ) da = 0;
            if( da < 0 ) da = 0;
            if( da > maxDiff ) da = maxDiff;
            daCur.get().set((float) da);
        }

        return diff;
    }

    public static Img<FloatType> differenceFromBelow(RandomAccessibleInterval<UnsignedByteType> column, float from, float maxDiff, long startIdx, long endIdx) {
        Img<FloatType> diff = ArrayImgs.floats((long) (endIdx - startIdx + 1));

        //IntervalView<UnsignedByteType> alignedColumn = Views.translate(column, startIdx);

        Cursor<FloatType> daCur = diff.localizingCursor();
        RandomAccess<UnsignedByteType> colRA = column.randomAccess();

        long[] pos = new long[1];

        while( daCur.hasNext() ) {
            daCur.fwd();
            daCur.localize(pos);
            colRA.setPosition(pos[0] + startIdx, 0);
            float val = colRA.get().get();

            float da = val - from;

            if( val < 0.5 ) da = 0;
            if( val > 254.5 ) da = 0;
            if( da < 0 ) da = 0;
            if( da > maxDiff ) da = maxDiff;
            daCur.get().set(da);
        }

        return diff;
    }

    public static double diffL2(RandomAccessibleInterval<FloatType> cost, RandomAccessibleInterval<FloatType> targetCost) {
        RealSum score = new RealSum();

        Cursor<FloatType> costCur = Views.iterable(cost).cursor();
        Cursor<FloatType> targetCur = Views.iterable(targetCost).cursor();
        while( costCur.hasNext() ) {
            costCur.fwd();
            targetCur.fwd();
            score.add( Math.pow( (costCur.get().get() - targetCur.get().get()), 2 ) );
        }

        return score.getSum();
    }

    /**
     * Return a RAI sized to interval with the distance along z for each location in the heightfield
     * @param heightfield
     * @param interval
     * @return
     */
    public static RandomAccessibleInterval<FloatType> distanceTransformHeightfield(RandomAccessibleInterval<FloatType> heightfield, Interval interval, float scale, float stretch) {
        RandomAccess<FloatType> heightfieldAccess = heightfield.randomAccess();

        return Views.interval(
                new FunctionRandomAccessible<>(
                        3,
                        (loc, val) -> {
                            heightfieldAccess.setPosition(loc.getLongPosition(0) /  6, 0);
                            heightfieldAccess.setPosition(loc.getLongPosition(1) /  6, 1);
                            float targetVal = heightfieldAccess.get().get();
                            float z = loc.getFloatPosition(2);
                            val.set(scale / (stretch * Math.abs(z - targetVal) + 0.001f));
                        },
                        FloatType::new),
                interval);
    }


}
