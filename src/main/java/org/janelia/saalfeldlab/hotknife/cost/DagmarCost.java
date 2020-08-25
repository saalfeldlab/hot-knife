package org.janelia.saalfeldlab.hotknife.cost;

import ij.IJ;
import ij.ImagePlus;
import net.imagej.ops.OpService;
import net.imglib2.*;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.algorithm.gauss3.SeparableSymmetricConvolution;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.scijava.log.LogService;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.lang.Integer.max;

// Port of: https://github.com/JaneliaSciComp/fibsem-flatten/blob/master/fibflatten/resin.py
public class DagmarCost {

    private RandomAccessibleInterval<UnsignedByteType> image;

    private int stepSize = 1;
    private boolean withGraphics = false;
    private boolean doSlopeCorrection = true;
    private boolean doLowAvg = false;
    private int bandSize = 50;
    private int minGradient = 20;
    private int slopeCorrXRange = 10;
    private float slopeCorrBandFactor = 3.5f;
    private float maxSlope = 0.04f;
    private float minSlope = 0.0f;
    private int startThresh = 50;
    private int kernelSize = 5;// for valsCount

    private double smallGauss = 3;
    private double bigGauss = 8;
    private double gaussMaskThreshold = 30;

    public Img<FloatType> computeResin(RandomAccessibleInterval<UnsignedByteType> image, ExecutorService executorService) throws Exception {
        return computeResin(image, true, true, executorService);
    }


    public Img<FloatType> computeResin(RandomAccessibleInterval<UnsignedByteType> image,
                                              boolean top,
                                              boolean bottom,
                                              ExecutorService executorService) throws Exception {

        // Compute a big and small gaussian
        // TODO: combine gausses
        RandomAccessibleInterval<UnsignedByteType> g03 = gauss(image, smallGauss, executorService);
        RandomAccessibleInterval<UnsignedByteType> g08 = gauss(image, bigGauss, executorService);

        //System.out.println("cost: gauss done");

        // Compute a mask as the DoG thresholded by gaussMaskThreshold from the prev gaussians
        Img<BitType> mask = ArrayImgs.bits(g03.dimension(0), g03.dimension(1));
        RandomAccess<UnsignedByteType> g03Access = g03.randomAccess();
        RandomAccess<UnsignedByteType> g08Access = g08.randomAccess();
        Cursor<BitType> mCur = mask.localizingCursor();
        while (mCur.hasNext()) {
            mCur.next();
            g03Access.setPosition(mCur);
            g08Access.setPosition(mCur);
            int v03 = g03Access.get().get();
            int v08 = g08Access.get().get();
            if (v03 - v08 < gaussMaskThreshold)
                mCur.get().setZero();
            else
                mCur.get().setOne();
        }

        // Compute top then bottom cost
        Img<FloatType> topCost = null;
        IntervalView<FloatType> botCost = null;

        RandomAccess<UnsignedByteType> imageRA = image.randomAccess();

//        Img<UnsignedByteType> maskedImage = ArrayImgs.unsignedBytes(image.dimension(0), image.dimension(1));
//        RandomAccess<UnsignedByteType> outRA = maskedImage.randomAccess();
//
//        mCur = mask.localizingCursor();
//        while (mCur.hasNext()) {
//            mCur.fwd();
//            outRA.setPosition(mCur);
//            imageRA.setPosition(mCur);
//
//            if (mCur.get().get())
//                outRA.get().set(imageRA.get());
//            else
//                outRA.get().setZero();
//        }

        RandomAccessibleInterval<UnsignedByteType> subsampledImage = Views.subsample(image, stepSize, 1);
        RandomAccess<UnsignedByteType> subsampledRA = subsampledImage.randomAccess();

        long[] min = new long[3];
        long[] max = new long[3];

        subsampledImage.min(min);
        subsampledImage.max(max);

        if (top) {
            Img<UnsignedByteType> copyImg = ArrayImgs.unsignedBytes(subsampledImage.dimension(0), subsampledImage.dimension(1) );

            Cursor<UnsignedByteType> copyCur = copyImg.localizingCursor();
            while (copyCur.hasNext()) {
                copyCur.fwd();
                subsampledRA.setPosition(copyCur);
                copyCur.get().set(subsampledRA.get());
            }

            topCost = computeOneSide(copyImg);
        }

        //System.out.println("cost: computed top cost");

        // To compute bottom cost, flip and use the same code, then flip result
        if (bottom) {
            Img<UnsignedByteType> copyImg = ArrayImgs.unsignedBytes(subsampledImage.dimension(0), subsampledImage.dimension(1));

            Cursor<UnsignedByteType> copyCur = copyImg.localizingCursor();
            while (copyCur.hasNext()) {
                copyCur.fwd();
                subsampledRA.setPosition(copyCur);
                copyCur.get().set(subsampledRA.get());
            }

            botCost =
                    Views.zeroMin(
                            Views.invertAxis(
                                    computeOneSide(Views.zeroMin(Views.invertAxis(copyImg, 1))),
                                    1));
        }

        //System.out.println("cost: computed bottom cost");

        //ImageJFunctions.show(topCost, "cost (top)");
        //ImageJFunctions.show(botCost, "cost (bot)");

        if (top && bottom)
            return mergeCosts(topCost, botCost);
        else if (top)
            return topCost;
        else
            return intoImg(botCost);
    }


    private RandomAccessibleInterval<UnsignedByteType> gauss(RandomAccessibleInterval<UnsignedByteType> image, double sigma, ExecutorService executorService) {

        RandomAccessibleInterval<UnsignedByteType> output = ArrayImgs.unsignedBytes(image.dimension(0), image.dimension(1));
        RandomAccessible<UnsignedByteType> eIn = Views.extendMirrorSingle(image);

        //System.out.println("gauss of: " + Arrays.toString(Intervals.dimensionsAsLongArray(image)));

        RandomAccessible<UnsignedByteType> in = Views.extendMirrorSingle(image);

        try {
            SeparableSymmetricConvolution.convolve(Gauss3.halfkernels(new double[]{sigma, sigma}), in, output, executorService);
        } catch (IncompatibleTypeException var5) {
            throw new RuntimeException(var5);
        }

        return output;
    }


    private Img<FloatType> intoImg(RandomAccessibleInterval<FloatType> cost) {
        Img<FloatType> outCost = ArrayImgs.floats(cost.dimension(0), cost.dimension(1));
        RandomAccess<FloatType> topAccess = cost.randomAccess();

        Cursor<FloatType> outCur = outCost.localizingCursor();
        while (outCur.hasNext()) {
            outCur.fwd();
            topAccess.setPosition(outCur);

            outCur.get().set(topAccess.get());
        }

        return outCost;
    }


    public Img<FloatType> mergeCosts(RandomAccessibleInterval<FloatType> topCost, RandomAccessibleInterval<FloatType> botCost) {

        Img<FloatType> outCost = ArrayImgs.floats(topCost.dimension(0), topCost.dimension(1));

        RandomAccess<FloatType> topAccess = topCost.randomAccess();
        RandomAccess<FloatType> botAccess = botCost.randomAccess();

        Cursor<FloatType> outCur = outCost.localizingCursor();
        while (outCur.hasNext()) {
            outCur.fwd();
            topAccess.setPosition(outCur);
            botAccess.setPosition(outCur);

            outCur.get().set(Math.min(topAccess.get().get(), botAccess.get().get()));
        }

        return outCost;

    }


    public Img<FloatType> computeOneSide(RandomAccessibleInterval<UnsignedByteType> image) throws Exception {
        // TODO consider using a CellImg with blocks set to full columns (because this algorithm is column based); show performance difference
        Img<FloatType> cost = ArrayImgs.floats(image.dimension(0), image.dimension(1));
        cost.forEach(t -> t.set(255.0f));

        Img<UnsignedByteType> maskedImage = ArrayImgs.unsignedBytes(image.dimension(0), image.dimension(1));
        Cursor<UnsignedByteType> maskCur = maskedImage.localizingCursor();
        RandomAccess<UnsignedByteType> imRA = image.randomAccess();

        long[] min = new long[3];
        long[] max = new long[3];

        // Make a mask
        while (maskCur.hasNext()) {
            maskCur.fwd();
            imRA.setPosition(maskCur);
            if (imRA.get().get() > 0 && imRA.get().get() < 255) {
                maskCur.get().set(imRA.get());
            }
        }

        // Loop over all columns in the slice
        for (int x = 0; x < cost.dimension(0); x++) {
            IntervalView<UnsignedByteType> column = Views.hyperSlice(image, 0, x);// Source image
            IntervalView<FloatType> costColumn = Views.hyperSlice(cost, 0, x);// Output image

            costColumn(image, maskedImage, x, column, costColumn);
        }

        return cost;
    }


    private void costColumn(
            RandomAccessibleInterval<UnsignedByteType> image,
            Img<UnsignedByteType> maskedImage,
            int x,
            IntervalView<UnsignedByteType> columnView,
            IntervalView<FloatType> costColumn) {

        // Copy column into separate memory
        Img<UnsignedByteType> column = ArrayImgs.unsignedBytes(Intervals.dimensionsAsLongArray(columnView));
        Cursor<UnsignedByteType> colCur = column.cursor();
        Cursor<UnsignedByteType> cvCur = columnView.cursor();
        while (cvCur.hasNext()) {
            cvCur.fwd();
            colCur.fwd();
            colCur.get().set(cvCur.get());
        }

        // Working memory for result
        Img<FloatType> result = ArrayImgs.floats(column.size());
        Cursor<FloatType> resultCur;

        // Find the max value in the column
        int colMax = Integer.MIN_VALUE;
        colCur = column.cursor();
        while (colCur.hasNext()) {
            colCur.fwd();
            int v = colCur.get().get();
            if (colMax < v)
                colMax = v;
        }

        // Skip any columns where all values are 0 or the column is empty
        if (colMax != 0 && column.dimension(0) > 0) {

            // Do a first pass to compute bands before (and if) slope correction is performed to get initial column stats
            Object[] res = doBands(column, bandSize, startThresh);
            double[] firstBand = (double[]) res[0];
            long startIdx = (long) res[1];
            long countMaxedOut = (long) res[2];
            long countTotal = (long) res[3];
            double[] contraBand = (double[]) res[4];
            long endIdx = (long) res[5];

            double median;
            double stddev;
            double slope = Double.NaN;

            if (contraBand == null) contraBand = new double[]{1e6};

            if (doSlopeCorrection) {
                long minCol = Math.max(image.min(0), x - slopeCorrXRange);
                long maxCol = Math.min(x + slopeCorrXRange + 1, image.max(0));
                long minRow = startIdx;
                long maxRow = (long) Math.min(startIdx + slopeCorrBandFactor * bandSize, endIdx);

                FunctionRandomAccessible<LongType> indYRA = new FunctionRandomAccessible<>(
                        2,
                        (location, value) -> value.set(location.getLongPosition(1)),
                        LongType::new
                );

                //System.out.println("pre: " + startIdx + "\t" + endIdx);

                RandomAccessibleInterval<LongType> indY = Views.interval(indYRA, maskedImage);
                slope = Utils.slopeCalc(maskedImage, indYRA, minRow, maxRow, minCol, maxCol,
                        bandSize, (int) startIdx, slopeCorrBandFactor, minSlope, maxSlope);
                //System.out.println("slope: " + slope);
                //log.info("slope correction: slope: " + slope + " " + minCol + " " + maxCol + " " + minRow + " " + maxRow );

                Utils.slopeCorrection(column, startIdx, endIdx, slope, indY);

                res = doBands(column, bandSize, startThresh);// this method should update: first_band, sa, count_maxed_out, count_total, contra_band, sac
                firstBand = (double[]) res[0];
                startIdx = (long) res[1];
                countMaxedOut = (long) res[2];
                countTotal = (long) res[3];
                contraBand = (double[]) res[4];
                endIdx = (long) res[5];
            }

            if (firstBand == null) firstBand = new double[]{1e6};
            if (contraBand == null) contraBand = new double[]{1e6};

            // Calculate statistics from firstBand and contraBand
            int count = firstBand.length;
            if (countMaxedOut <= (0.2 * countTotal)) {
                median = firstBand[count / 2];
                stddev = 0.5 * (firstBand[(int) (0.84 * count)] - firstBand[(int) (0.16 * count)]);
            } else {
                median = firstBand[(int) (0.3 * count)];
                stddev = firstBand[firstBand.length - 1] - firstBand[(int) (0.16 * count)];
            }
            int countContra = contraBand.length;
            double medianContra = contraBand[countContra / 2];
            double iqrContra = contraBand[(int) (0.75 * countContra)] - contraBand[(int) (0.25 * countContra)];

            //log.info("col: " + x + " median: " + median + " stddev: " + stddev + " colmax: " + colMax + " startIdx: " + startIdx + " endIdx: " + endIdx + " countMaxedOut: " + countMaxedOut + " countTotal: " + countTotal + " count: " + count + " | medianContra: " + medianContra + " iqrContra: " + iqrContra + " countContra: " + countContra );

            // Initialize result vector to 255
            result.forEach(t -> t.set(255));


            // Ensure startIdx at least 1 FIXME
            startIdx = Math.max(startIdx, 1);

            // ---- Iterative result ----- (cost result calculation begins here)
            boolean resultFound = false;
            double avg = firstBand[(int) (0.3 * count)];


            // Column must contain positive values
            column.forEach(t -> t.set(t.get() < 0 ? 0 : t.get()));

            // median: 143, stdev: 9, median_contra 172, iqr_contra, 10
            double initialAvg = avg;

            for (int idx = 0; idx < 1; idx++) {
                if (idx <= 1 && (countMaxedOut > 0.2 * countTotal))
//                # Too much white to use simple median
                    continue;
                if (idx == 6 && !doLowAvg)
                    continue;
                if (idx > 6 && resultFound)
                    continue;
                if (idx == 7)
                    //                # contra_band may be much larger *and* brighter
                    endIdx = (long) (0.3 * startIdx + 0.7 * endIdx);
                else if (idx == 8)
                    //                # go through even less of the data to try and find surface
                    endIdx = Math.min(endIdx, startIdx + 30 * bandSize);

                //log.info("idx: " + idx + " avg: " + avg + " median: " + median + " stddev: " + stddev + " medianContra: " + medianContra + " iqrContra: " + iqrContra );


                // The idea of calcAvg is to test with decreasingly strict zScores per iteration
                Pair<Double, Double> pr = Utils.calcAverage(idx, avg, median, stddev, medianContra, iqrContra);
                avg = pr.getA();
                Double belowAvg = pr.getB();

//                if( outputTesting) {
//                    HashMap<String, Double> argmap = new HashMap<String, Double>();
//                    argmap.put("j", (double) x);
//                    argmap.put("idx", (double) idx);
//                    argmap.put("initial_avg", initialAvg);
//                    argmap.put("avg", avg);
//                    argmap.put("below_avg", belowAvg);
//                    argmap.put("median", median);
//                    argmap.put("stdev", stddev);
//                    argmap.put("median_contra", medianContra);
//                    argmap.put("iqr_contra", iqrContra);
//                    argmap.put("sa", (double) startIdx);
//                    argmap.put("sac", (double) endIdx);
//                    argmap.put("slope", slope);
//                    argmap.put("count_maxed_out", (double) countMaxedOut);
//                    argmap.put("count_total", (double) countTotal);
//                    argmap.put("count", (double) count);
//                    // ['j', 'idx', 'avg', 'below_avg', 'median', 'stdev', 'median_contra', 'iqr_contra', 'sa', 'sac', 'slope', 'count_maxed_out', 'count_total']
//
//                    if (!testCurrLine(currentObservation, argmap, log))
//                        throw new Exception("test failed");
//                    currentObservation++;
//                }

                //log.info("idx: " + idx + " avg: " + avg + " belowAvg: " + belowAvg);
                //if (startIdx >= endIdx)
                if ((endIdx - startIdx) <= kernelSize)
                    continue;
                if (avg < 255) {
                    double maxDiff = Math.max(1, avg - belowAvg);

                    // Try result eventually stores a variation of the cumulativeDiffAvg
                    Img<FloatType> tryResult = ArrayImgs.floats((long) (endIdx - startIdx + 1));
                    tryResult.forEach(t -> t.set(255f));

                    // diffAvg is the difference of input column from the average of the column

                    Img<FloatType> diffAvg = Utils.differenceFrom(column, avg, maxDiff, startIdx, endIdx);
                    IntervalView<DoubleType> cumulativeDiffAvg = Utils.cumulativeSum(diffAvg);

                    Img<FloatType> diffBelowAvg = Utils.differenceFromBelow(column, belowAvg.floatValue(), (float) maxDiff, startIdx, endIdx);
                    IntervalView<DoubleType> cumulativeDiffBelowAvg = Utils.cumulativeSum(diffBelowAvg);

                    RandomAccess<DoubleType> cdaRA = cumulativeDiffAvg.randomAccess();
                    cdaRA.setPosition(cumulativeDiffAvg.dimension(0) - 1, 0);
                    RandomAccess<DoubleType> cdbaRA = cumulativeDiffBelowAvg.randomAccess();
                    cdbaRA.setPosition(cumulativeDiffBelowAvg.dimension(0) - 1, 0);
                    //log.info("last element cda: " + cdaRA.get().get() + " cdba: " + cdbaRA.get().get() + " avg: " + avg + " belowavg: " + belowAvg.floatValue() );

                    Cursor<FloatType> trCur = tryResult.localizingCursor();
                    RandomAccess<DoubleType> cumulativeDiffAvgRA = cumulativeDiffAvg.randomAccess();
                    RandomAccess<DoubleType> cumulativeDiffBelowAvgRA = cumulativeDiffBelowAvg.randomAccess();

                    cumulativeDiffBelowAvgRA.setPosition((int) cumulativeDiffBelowAvg.dimension(0) - 1, 0);
                    double lastCumulativeDiffBelowAvg = cumulativeDiffBelowAvgRA.get().get();
                    while (trCur.hasNext()) {
                        trCur.fwd();
                        cumulativeDiffAvgRA.setPosition(trCur);
                        cumulativeDiffBelowAvgRA.setPosition(trCur);
                        trCur.get().set((float) (
                                cumulativeDiffAvgRA.get().get()
                                        + lastCumulativeDiffBelowAvg
                                        - cumulativeDiffBelowAvgRA.get().get()));

                        //log.info("try result: " + trCur.get().get() + " cDiffAvg: " + cumulativeDiffAvgRA.get().get() + " lastCDiffBelowAvg: " + lastCumulativeDiffBelowAvg + " cDiffBelowAvg: " + cumulativeDiffBelowAvgRA.get().get() + " maxdiff: " + maxDiff);
                    }

                    // Find the index of the minimum value in tryResult [replace w/ localizingCursor]
                    int minIdx = 0;
                    float minRes = Float.MAX_VALUE;
                    RandomAccess<FloatType> trRA = tryResult.randomAccess();
                    for (long k = tryResult.min(0); k < tryResult.max(0) - 1; k++) {
                        trRA.setPosition(k, 0);
                        if (trRA.get().get() < minRes) {
                            minIdx = (int) k;
                            minRes = trRA.get().get();
                            //log.info("min idx: " + minIdx + " v: " + minRes );
                        }
                    }
                    minIdx += startIdx;


                    // Subtract minRes such that tryResult is now [0, ...]
                    for (long k = tryResult.min(0); k < tryResult.max(0) - 1; k++) {
                        trRA.setPosition(k, 0);
                        trRA.get().set(trRA.get().get() - minRes);
                    }

                    // Bound tryResult below 255
                    tryResult.forEach(t -> t.set(t.get() > 255 ? 255 : t.get()));

                    // Bound column between 0.5 and 254.5
                    // NOTE: Matt's implementation used a vectorization trick here to simultaneously check if the idx loop should break
                    // This odd code reproduces the effect
                    boolean startIdxPass = false;
                    boolean endIdxPass = false;

                    Cursor<UnsignedByteType> cc = column.localizingCursor();
                    while (cc.hasNext()) {
                        cc.fwd();
                        UnsignedByteType t = cc.get();
                        int v = t.get();
                        int nv = (v > 254.5 || v < 0.5) ? 0 : v;
                        t.set(nv);

                        if (!startIdxPass && (nv != 0) && cc.getDoublePosition(0) <= (startIdx - 1))
                            startIdxPass = true;

                        if (!endIdxPass && (nv != 0) && cc.getDoublePosition(0) > (endIdx - 1))
                            endIdxPass = true;
                    }

                    if ((endIdx + 1 <= startIdx - 1) || (column.dimension(0) < endIdx) || !(startIdxPass && endIdxPass))// (endIdx + 1)?
                        continue;


                    Img<FloatType> valsCount = ArrayImgs.floats(tryResult.dimension(0));// TODO these can probably be kernelSize smaller
                    Img<FloatType> valsConv = ArrayImgs.floats(tryResult.dimension(0));

                    RandomAccess<FloatType> valsCountAccess = valsCount.randomAccess();
                    RandomAccess<FloatType> valsConvAccess = valsConv.randomAccess();
                    RandomAccess<UnsignedByteType> columnAccess = column.randomAccess();

                    long[] pos = new long[1];

                    int valsCountKernelSize = 2;
                    for (pos[0] = startIdx - 1 + valsCountKernelSize; pos[0] < endIdx + 1 - valsCountKernelSize; pos[0]++) {
                        float vc = 0;
                        float vs = 0;
                        for (int kidx = -valsCountKernelSize; kidx <= valsCountKernelSize; kidx++) {
                            columnAccess.setPosition(pos[0] + kidx, 0);
                            int cval = columnAccess.get().get();
                            vc += (cval > 0 ? 1 : 0);
                            vs += cval;
                        }
                        valsCountAccess.setPosition(pos[0] - startIdx - 1, 0);
                        valsCountAccess.get().set(vc == 0 ? Float.NaN : vc);
                        valsConvAccess.setPosition(pos[0] - startIdx - 1, 0);
                        valsConvAccess.get().set(vs);
                    }


                    RandomAccess<FloatType> vcRA = valsCount.randomAccess();
                    RandomAccess<FloatType> vvRA = valsConv.randomAccess();
                    RandomAccess<UnsignedByteType> colRA = column.randomAccess();


                    Img<FloatType> grad = ArrayImgs.floats(valsCount.dimension(0) - kernelSize);

                    RandomAccess<FloatType> gradRA = grad.randomAccess();
                    vcRA = valsCount.randomAccess();
                    vvRA = valsConv.randomAccess();
                    int gradCount = 0;
                    for (int i = 0; i < grad.dimension(0); i++) {
                        gradRA.setPosition(i, 0);
                        vcRA.setPosition(i + kernelSize, 0);
                        vvRA.setPosition(i + kernelSize, 0);

                        float vcStart = vcRA.get().get();
                        float vvStart = vvRA.get().get();

                        vcRA.setPosition(i, 0);
                        vvRA.setPosition(i, 0);

                        float vcEnd = vcRA.get().get();
                        float vvEnd = vvRA.get().get();

                        float val = (vvStart / vcStart) - (vvEnd / vcEnd);

                        if (!Float.isNaN(val))
                            gradCount++;

                        gradRA.get().set(val);
                    }

                    if (gradCount == 0) {
                        continue;
                    }

                    Img<BitType> mask = ArrayImgs.bits(grad.dimension(0));

                    //                # Fill in nans with previous values
                    Cursor<FloatType> gradCur = grad.cursor();
                    Cursor<BitType> mCur = mask.cursor();
                    while (gradCur.hasNext()) {
                        gradCur.fwd();
                        mCur.fwd();
                        if (Float.isNaN(gradCur.get().get())) {
                            mCur.get().set(true);
                            gradCur.get().set(0);
                        }
                    }


                    // This fills masked regions of the gradient with the most recent unmasked gradient value in traversal order
                    Img<IntType> maskIdx = ArrayImgs.ints(grad.dimension(0));
                    Cursor<IntType> miCur = maskIdx.cursor();
                    mCur = mask.cursor();
                    gradCur = grad.cursor();
                    int currMaxIdx = 0;
                    long[] p = new long[1];
                    while (mCur.hasNext()) {
                        mCur.fwd();
                        miCur.fwd();
                        gradCur.fwd();

                        mCur.localize(p);
                        if (!mCur.get().get()) {
                            currMaxIdx = max(currMaxIdx, (int) p[0]);
                        }
                        miCur.get().set((int) currMaxIdx);

                        p[0] = currMaxIdx;
                        gradRA.setPosition(p);
                        float gradMaxVal = (gradRA.get().get());

                        float currGrad = gradCur.get().get();
                        if (mCur.get().get()) {
                            currGrad = gradMaxVal;
                        }
                        currGrad = Math.abs(currGrad);

                        if (currGrad > Math.abs(minGradient)) {
                            currGrad = Math.abs(minGradient);
                        }

                        gradCur.get().set(currGrad);
                    }

                    // Difference of tryResult and grad w/in grad's interval
                    gradCur = grad.localizingCursor();
                    trRA = tryResult.randomAccess();

                    minRes = Float.MAX_VALUE;
                    float maxRes = Float.MIN_VALUE;
                    while (gradCur.hasNext()) {
                        gradCur.fwd();
                        gradCur.localize(pos);

                        trRA.setPosition(pos[0] + valsCountKernelSize * 2, 0);
                        float trVal = trRA.get().get() - gradCur.get().get();
                        trRA.get().set(trVal);
                        minRes = Math.min(minRes, trVal);
                        maxRes = Math.max(maxRes, trVal);
                    }

                    // Normalize try result and scale to [0, 255]
                    float span = Math.max(maxRes - minRes, 1);
                    trCur = Views.iterable(tryResult).cursor();

                    while (trCur.hasNext()) {
                        trCur.fwd();
                        float trVal = trCur.get().get();
                        trCur.get().set((trVal - minRes) / span * 255);
                    }

                    //log.info("minIdx: " + minIdx + " startIdx: " + startIdx + " floorhalfband: " + Math.floor( bandSize / 2 ) + " endIdx: " + endIdx);

                    // minIdx cannot be within either  the start or end bands
                    if ((minIdx >= startIdx + Math.floor(bandSize / 2))
                            && (minIdx <= endIdx - Math.floor(bandSize / 2))) {
                        resultFound = true;

                        //log.info("col: " + x + " idx: " + idx + " minIdx: " + minIdx + " median: " + median + " stddev: " + stddev + " colmax: " + colMax + " startIdx: " + startIdx + " endIdx: " + endIdx + " countMaxedOut: " + countMaxedOut + " countTotal: " + countTotal + " count: " + count + " | medianContra: " + medianContra + " iqrContra: " + iqrContra + " countContra: " + countContra );

                        //double resultViewMin = startIdx + Math.floor(bandSize / 2);// from original
                        double resultViewMin = startIdx;
                        double resultViewMax = (endIdx - Math.floor(bandSize / 2) + 1);

//                        log.info("Result found on attempt col: " + x +
//                                " idx: " + idx +
//                                " avg: " + avg +
//                                " median: " + median +
//                                " stddev: " + stddev +
//                                " belowAvg: " + belowAvg +
//                                " medianContra: " + medianContra +
//                                " iqrContra: " + iqrContra +
//                                " resultViewMin: " + resultViewMin +
//                                " resultViewMax: " + resultViewMax +
//                                " minIdx: " + minIdx +
//                                " startIdx: " + startIdx +
//                                " endIdx: " + endIdx );

                        IntervalView<FloatType> resultView = Views.interval(result,
                                new FinalInterval(
                                        new long[]{(long) resultViewMin},
                                        new long[]{(long) resultViewMax}));

                        Cursor<FloatType> rvCur = resultView.cursor();
                        trCur = Views.iterable(tryResult).cursor();
                        while (rvCur.hasNext()) {
                            rvCur.fwd();
                            trCur.fwd();

                            float resultVal = Math.min(rvCur.get().get(), trCur.get().get());

                            //log.info("resultVal: " + resultVal + " prevRV: " + rvCur.get().get() + " tryResult: " + trCur.get().get() );

                            rvCur.get().set(resultVal);
                        }
                        //
                        //                        RandomAccess<FloatType> resultAccess = resultView.randomAccess();
                        //                        resultAccess.setPosition(600, 0);
                        //                        float result600 = resultAccess.get().get();
                        //                        resultAccess.setPosition(800, 0);
                        //                        float result800 = resultAccess.get().get();

//                        if( outputTesting ) {
//                            HashMap<String, Double> argmap = new HashMap<String, Double>();
//                            argmap.put("j", (double) x);
//                            argmap.put("idx", (double) idx);
//                            argmap.put("initial_avg", initialAvg);
//                            argmap.put("avg", avg);
//                            argmap.put("below_avg", belowAvg);
//                            argmap.put("median", median);
//                            argmap.put("stdev", stddev);
//                            argmap.put("median_contra", medianContra);
//                            argmap.put("iqr_contra", iqrContra);
//                            argmap.put("sa", (double) startIdx);
//                            argmap.put("sac", (double) endIdx);
//                            argmap.put("slope", slope);
//                            argmap.put("count_maxed_out", (double) countMaxedOut);
//                            argmap.put("count_total", (double) countTotal);
//                            argmap.put("count", (double) count);
//                            //argmap.put("min_idx", (double) minIdx);
//                            argmap.put("result_600", (double) result600);
//                            argmap.put("result_800", (double) result800);
//
//                            System.out.println("Testing: " + x);
//                            if (!testCurrLine(currentObservation, argmap, log))
//                                throw new Exception("test failed");
//                            currentObservation++;
//                        }
                    }
                }
            }

//            if( !resultFound ) {
//                log.info("result not found: " +
//                        " avg: " + avg +
//                        " median: " + median +
//                        " stddev: " + stddev +
//                        " medianContra: " + medianContra +
//                        " iqrContra: " + iqrContra +
//                        " startIdx: " + startIdx +
//                        " endIdx: " + endIdx );
//
//            }
        }

        resultCur = Views.iterable(result).cursor();
        Cursor<FloatType> costColCur = Views.iterable(costColumn).cursor();
        while (resultCur.hasNext()) {
            resultCur.fwd();
            costColCur.fwd();
            costColCur.get().set(resultCur.get().get());
        }
    }

    /**
     * This method finds 2 bands at the top and bottom of a column for use in computing intensity distributions
     *
     * @param column the column of pixels that we are computing the bands on
     * @param bandSize the width of both bands
     * @param startThresh the threshold for the first pixel to use for band calculations
     * @return an Object[] that packs multiple outputs:
     * [0], firstBand: This is the band at the top of the image (used to compute intensity statistics)
     * [1], startIdx: This is the index of the first pixel of image data (related to the start thresh parameter
     * [2], countMaxedOut: number of oversaturated pixels (where oversaturated value == 255)
     * [3], countTotal: number of pixels in column
     * [4], contraBand: This is the band at the bottom of the column
     * [5], endIdx: This is the index of the last pixel of image data
     */
    private Object[] doBands(RandomAccessibleInterval<UnsignedByteType> column, int bandSize, int startThresh) {
        Object[] bcResult = bandCalc(column, bandSize, startThresh);
        double[] firstBand = (double[]) bcResult[0];
        long startIdx = (long) bcResult[1];
        long countMaxedOut = (long) bcResult[2];
        long countTotal = (long) bcResult[3];

        double upperQuantile = -1;
        if (firstBand.length > 0) {
            upperQuantile = Math.min(firstBand[(int) Math.floor(3.0 * firstBand.length / 4)], 253.5);

            bcResult = bandCalc(column, bandSize, upperQuantile);
            firstBand = (double[]) bcResult[0];
            startIdx = (long) bcResult[1];
            countMaxedOut = (long) bcResult[2];
            countTotal = (long) bcResult[3];
        }
        if (firstBand.length == 0) {
            startIdx = (column.dimension(0) - 1);
        }

        // Calculate contra band by flipping the column and doing the same thing
        IntervalView<UnsignedByteType> contraColumn =
                Views.zeroMin(
                        Views.invertAxis(column, 0));
        bcResult = bandCalc(
                contraColumn,
                2 * bandSize, startThresh);

        double[] contraBand = (double[]) bcResult[0];

        long endIdx = -1;
        double upperQuantileContra;
        if (contraBand.length > 0) {
            upperQuantileContra = Math.min(contraBand[(int) (Math.floor(3.0 * contraBand.length / 4))], 253.5);

            bcResult = bandCalc(contraColumn, bandSize, upperQuantileContra);

            contraBand = (double[]) bcResult[0];
            endIdx = (long) bcResult[1];
            endIdx = (column.dimension(0) - endIdx - 1);
        }

        if (contraBand.length == 0) {
            endIdx = 0;
        }

        return new Object[]{firstBand, startIdx, countMaxedOut, countTotal, contraBand, endIdx};
    }

    /**
     * Find one band on the column. This method is called 2x to compute the first and contra bands
     *
     * @param column the column of pixels that we are computing our band on
     * @param band_size the width of the band we are finding
     * @param start_thresh the threshold of the first pixel we can use in the band
     * @return an Object[] that packs multiple return values
     * [0], outBand: this is the band we have calculated
     * [1], startIdx: the index of the first value we used for our band calculation
     * [2], countMaxedOut: the number of oversaturated pixels (where oversaturated value == 255)
     * [3], countTotal: the number of pixels that we used from the column
     */
    private Object[] bandCalc(RandomAccessibleInterval<UnsignedByteType> column, double band_size, double start_thresh) {
        long count = 0;
        long count_maxed_out = 0;

        // Find the start index along the column as the first value less that startThresh
        long start = 0;
        long[] k = new long[1];
        int startVal = 0;
        RandomAccess<UnsignedByteType> cRA = column.randomAccess();
        for (k[0] = 0; k[0] < column.dimension(0); k[0]++) {
            cRA.setPosition(k);
            int val = cRA.get().get();
            if ((val > start_thresh) && (val < 254.5)) {
                start = k[0];
                startVal = val;
                break;
            }
        }

        // From start until the end of the column, count valid/invalid values, store valid values
        // Loop terminates after band_size # valid values
        ArrayList<Integer> lBand = new ArrayList<Integer>();
        for (k[0] = start; k[0] < column.dimension(0); k[0]++) {
            cRA.setPosition(k);
            //System.out.println("k[0]: " + k[0]);
            int val = cRA.get().get();

            if (val > 0.5 && val < 254.5) {
                lBand.add(val);
                count++;
                if (count == band_size)
                    break;
            } else if (val >= 254.5) {
                count_maxed_out++;
            }

        }
        k[0] -= start;

        // Sort all values
        lBand.sort(Integer::compare);

        // Return an array (not necessary?)
        double[] outBand = new double[lBand.size()];
        for (int i = 0; i < lBand.size(); i++) {
            outBand[i] = lBand.get(i);
        }

        return new Object[]{
                outBand,
                start,
                count_maxed_out,
                (k[0] + 1)};
    }

    public int getStepSize() {
        return stepSize;
    }

    public void setStepSize(int stepSize) {
        this.stepSize = stepSize;
    }

    public boolean isWithGraphics() {
        return withGraphics;
    }

    public void setWithGraphics(boolean withGraphics) {
        this.withGraphics = withGraphics;
    }

    public boolean isDoSlopeCorrection() {
        return doSlopeCorrection;
    }

    public void setDoSlopeCorrection(boolean doSlopeCorrection) {
        this.doSlopeCorrection = doSlopeCorrection;
    }

    public boolean isDoLowAvg() {
        return doLowAvg;
    }

    public void setDoLowAvg(boolean doLowAvg) {
        this.doLowAvg = doLowAvg;
    }

    public int getBandSize() {
        return bandSize;
    }

    public void setBandSize(int bandSize) {
        this.bandSize = bandSize;
    }

    public int getMinGradient() {
        return minGradient;
    }

    public void setMinGradient(int minGradient) {
        this.minGradient = minGradient;
    }

    public int getSlopeCorrXRange() {
        return slopeCorrXRange;
    }

    public void setSlopeCorrXRange(int slopeCorrXRange) {
        this.slopeCorrXRange = slopeCorrXRange;
    }

    public float getSlopeCorrBandFactor() {
        return slopeCorrBandFactor;
    }

    public void setSlopeCorrBandFactor(float slopeCorrBandFactor) {
        this.slopeCorrBandFactor = slopeCorrBandFactor;
    }

    public float getMaxSlope() {
        return maxSlope;
    }

    public void setMaxSlope(float maxSlope) {
        this.maxSlope = maxSlope;
    }

    public float getMinSlope() {
        return minSlope;
    }

    public void setMinSlope(float minSlope) {
        this.minSlope = minSlope;
    }

    public int getStartThresh() {
        return startThresh;
    }

    public void setStartThresh(int startThresh) {
        this.startThresh = startThresh;
    }

    public int getKernelSize() {
        return kernelSize;
    }

    public void setKernelSize(int kernelSize) {
        this.kernelSize = kernelSize;
    }

    public double getSmallGauss() {
        return smallGauss;
    }

    public void setSmallGauss(double smallGauss) {
        this.smallGauss = smallGauss;
    }

    public double getBigGauss() {
        return bigGauss;
    }

    public void setBigGauss(double bigGauss) {
        this.bigGauss = bigGauss;
    }

    public double getGaussMaskThreshold() {
        return gaussMaskThreshold;
    }

    public void setGaussMaskThreshold(double gaussMaskThreshold) {
        this.gaussMaskThreshold = gaussMaskThreshold;
    }

    public HashMap<String, Object> columnStats(
            RandomAccessibleInterval<UnsignedByteType> image,
            Img<UnsignedByteType> maskedImage,
            int x,
            IntervalView<UnsignedByteType> columnView,
            OpService opService,
            LogService log) {

        // Copy column into separate memory
        Img<UnsignedByteType> column = ArrayImgs.unsignedBytes(Intervals.dimensionsAsLongArray(columnView));
        Cursor<UnsignedByteType> colCur = column.cursor();
        Cursor<UnsignedByteType> cvCur = columnView.cursor();
        while (cvCur.hasNext()) {
            cvCur.fwd();
            colCur.fwd();
            colCur.get().set(cvCur.get());
        }

        Img<FloatType> result = ArrayImgs.floats(column.size());// Working memory for result

        int colMax = opService.stats().max(column).get();

        if (colMax != 0 && column.dimension(0) > 0) {

            Object[] res = doBands(column, bandSize, startThresh);
            double[] firstBand = (double[]) res[0];
            long startIdx = (long) res[1];
            long countMaxedOut = (long) res[2];
            long countTotal = (long) res[3];
            double[] contraBand = (double[]) res[4];
            long endIdx = (long) res[5];

            double median;
            double stddev;
            double slope = Double.NaN;

            if (contraBand == null) contraBand = new double[]{1e6};

            if (doSlopeCorrection) {
                long minCol = Math.max(image.min(0), x - slopeCorrXRange);
                long maxCol = Math.min(x + slopeCorrXRange + 1, image.max(0));
                long minRow = startIdx;
                long maxRow = (long) Math.min(startIdx + slopeCorrBandFactor * bandSize, endIdx);

                FunctionRandomAccessible<LongType> indYRA = new FunctionRandomAccessible<>(
                        2,
                        (location, value) -> value.set(location.getLongPosition(1)),
                        LongType::new
                );

                //System.out.println("pre: " + startIdx + "\t" + endIdx);

                RandomAccessibleInterval<LongType> indY = Views.interval(indYRA, maskedImage);
                slope = Utils.slopeCalc(maskedImage, indYRA, minRow, maxRow, minCol, maxCol,
                        bandSize, (int) startIdx, slopeCorrBandFactor, minSlope, maxSlope);
                //System.out.println("slope: " + slope);
                //log.info("slope correction: slope: " + slope + " " + minCol + " " + maxCol + " " + minRow + " " + maxRow );

                Utils.slopeCorrection(column, startIdx, endIdx, slope, indY);

                res = doBands(column, bandSize, startThresh);// this method should update: first_band, sa, count_maxed_out, count_total, contra_band, sac
                firstBand = (double[]) res[0];
                startIdx = (long) res[1];
                countMaxedOut = (long) res[2];
                countTotal = (long) res[3];
                contraBand = (double[]) res[4];
                endIdx = (long) res[5];

                //System.out.println("post: " + startIdx + "\t" + endIdx + "\tslope: " + slope);
            }

            if (firstBand == null) firstBand = new double[]{1e6};
            if (contraBand == null) contraBand = new double[]{1e6};

            // Calculate statistics from firstBand and contraBand
            int count = firstBand.length;
            if (countMaxedOut <= (0.2 * countTotal)) {
                median = firstBand[count / 2];
                stddev = 0.5 * (firstBand[(int) (0.84 * count)] - firstBand[(int) (0.16 * count)]);
            } else {
                median = firstBand[(int) (0.3 * count)];
                stddev = firstBand[firstBand.length - 1] - firstBand[(int) (0.16 * count)];
            }
            int countContra = contraBand.length;
            double medianContra = contraBand[countContra / 2];
            double iqrContra = contraBand[(int) (0.75 * countContra)] - contraBand[(int) (0.25 * countContra)];

            //log.info("col: " + x + " median: " + median + " stddev: " + stddev + " colmax: " + colMax + " startIdx: " + startIdx + " endIdx: " + endIdx + " countMaxedOut: " + countMaxedOut + " countTotal: " + countTotal + " count: " + count + " | medianContra: " + medianContra + " iqrContra: " + iqrContra + " countContra: " + countContra );

            // Initialize result vector to 255
            result.forEach(t -> t.set(255));


            // Ensure startIdx at least 1 FIXME
            startIdx = Math.max(startIdx, 1);

            // ---- Iterative result ----- (cost result calculation begins here)
            boolean resultFound = false;
            double avg = firstBand[(int) (0.3 * count)];

            HashMap<String, Object> cStats = new HashMap<>();
            cStats.put("startIdx", startIdx);
            cStats.put("endIdx", endIdx);
            cStats.put("avg", avg);
            cStats.put("median", median);
            cStats.put("stddev", stddev);
            cStats.put("medianContra", medianContra);
            cStats.put("iqrContra", iqrContra);

            return cStats;
        }
        return null;
    }

    public static void main(String... args) {

        ij.ImageJ ij1 = new ij.ImageJ();
        ij1.setIconImage(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB));


//        String inputFilename = new File(com.kephale.vnc.DagmarCost.class.getResource("Sec26_zcorr.01601_crop001.png").getPath()).getAbsolutePath();

//        String inputFilename = new File(com.kephale.vnc.DagmarCost.class.getResource("Sec26_zcorr.01601_crop001_masked.png").getPath()).getAbsolutePath();
//        String costFilename = new File(com.kephale.vnc.DagmarCost.class.getResource("Sec26_zcorr.01601-resinCost_crop001.png").getPath()).getAbsolutePath();

        //String inputFilename = new File(com.kephale.vnc.DagmarCost.class.getResource("Sec26_zcorr.01601_crop001_masked.png").getPath()).getAbsolutePath();
//        String costFilename = new File(com.kephale.vnc.DagmarCost.class.getResource("Sec26_zcorr.01601_crop001_cost.png").getPath()).getAbsolutePath();

        //String inputFilename = new File(com.kephale.vnc.DagmarCost.class.getResource("Sec26_zcorr.01601.png").getPath()).getAbsolutePath();
        //String costFilename = new File(com.kephale.vnc.DagmarCost.class.getResource("Sec26_zcorr.01601-resinCost.tif").getPath()).getAbsolutePath();

//        String inputFilename = new File(com.kephale.vnc.DagmarCost.class.getResource("Sec26_zcorr.01601.png").getPath()).getAbsolutePath();
//        String costFilename = new File(com.kephale.vnc.DagmarCost.class.getResource("Sec26_zcorr.01601-resinCost.tif").getPath()).getAbsolutePath();

//        String inputFilename = new File("/home/kharrington/git/sema-cost/src/main/resources/Sec26_zcorr.01601.png").getAbsolutePath();
//        String costFilename = new File("/home/kharrington/git/sema-cost/src/main/resources/Sec26_zcorr.01601-resinCost.tif").getAbsolutePath();

        //String inputFilename = new File(com.kephale.vnc.DagmarCost.class.getResource("z33_v2_crop001.png").getPath()).getAbsolutePath();
        //String inputFilename = new File(DagmarCost.class.getResource("sec25_slice_4633.tif").getPath()).getAbsolutePath();

        String inputFilename = new File("/home/kharrington/git/sema-cost/src/main/resources/com/kephale/vnc/sec25_slice_4633.tif").getAbsolutePath();

        System.out.println(inputFilename);

        ImagePlus inputImp = IJ.openImage(inputFilename);

        RandomAccessibleInterval<UnsignedByteType> image = ImageJFunctions.wrap(inputImp);

        //ij.command().run(DagmarCost.class, true, argmap);

        RandomAccess<UnsignedByteType> imageAccess = Views.zeroMin(image).randomAccess();
        Img<UnsignedByteType> sliceCopy = ArrayImgs.unsignedBytes(image.dimension(0), image.dimension(1));

        System.out.println("Slice dimensions: " + Arrays.toString(Intervals.dimensionsAsIntArray(image)));
        System.out.println("Slice copy dimensions: " + Arrays.toString(Intervals.dimensionsAsIntArray(sliceCopy)));

        Cursor<UnsignedByteType> cc = Views.zeroMin(sliceCopy).localizingCursor();
        try {
            while (cc.hasNext()) {
                cc.fwd();
                imageAccess.setPosition(cc);
                cc.get().set(imageAccess.get());
            }
        } catch (Exception e) {
            System.out.println("Exception: " + cc.getDoublePosition(0) + ", " + cc.getDoublePosition(1));
            e.printStackTrace();
        }

        ExecutorService executorService = Executors.newFixedThreadPool(2);

        DagmarCost costFunc = new DagmarCost();

        costFunc.setWithGraphics(true);
        costFunc.setStepSize(100);

        Img<FloatType> costSlice;
        try {
            costSlice = costFunc.computeResin(sliceCopy, executorService);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        //BdvFunctions.show(image, "image");
        //BdvFunctions.show(costSlice, "cost");

        ImageJFunctions.show(image, "image");
        ImageJFunctions.show(costSlice, "cost");

        System.out.println("done");

        //executorService.shutdown();
    }
}
