/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2014 - 2017 Board of Regents of the University of
 * Wisconsin-Madison, University of Konstanz and Brian Northan.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.janelia.saalfeldlab.hotknife.ops;

import java.util.function.Consumer;

import org.janelia.saalfeldlab.hotknife.util.Util;

import ij.ImagePlus;
import ij.process.FloatProcessor;
import mpicbg.ij.clahe.Flat;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 * Simple CLAHE loader, applying CLAHE to all 2D slices of a cell.
 *
 * @author Stephan Saalfeld
 * @param <T> type of input and output
 */
public class CLAHE2D<T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>> {

	final RandomAccessible<T> input;
	final double minIntensity, maxIntensity;
	final private int radius;
	final int bins;
	final float slope;

	public CLAHE2D(
			final RandomAccessible<T> input,
			final double minIntensity,
			final double maxIntensity,
			final int radius,
			final int bins,
			final float slope) {

		this.input = input;
		this.minIntensity = minIntensity;
		this.maxIntensity = maxIntensity;
		this.radius = radius;
		this.bins = bins;
		this.slope = slope;
	}

	@Override
	public void accept(final RandomAccessibleInterval<T> output) {

		final T type = net.imglib2.util.Util.getTypeFromInterval(output).createVariable();
		final int n = output.numDimensions();
		final long[] min = Intervals.minAsLongArray(output);
		final long[] max = Intervals.maxAsLongArray(output);

		min[0] -= radius;
		min[1] -= radius;
		max[0] += radius;
		max[1] += radius;

		final IntervalView<FloatType> inputInterval = Views.interval(
				Converters.convert(
						input,
						(a, b) -> {
							b.set(a.getRealFloat());
						},
						new FloatType()),
				min,
				max);

		RandomAccessibleInterval<FloatType> inputSlice = inputInterval;
		RandomAccessibleInterval<T> outputSlice = output;
		final long[] slicePosition = min.clone();

		for (int d = 2; d < n;) {

			for (int i = n; i >= 2; --i) {
				inputSlice = Views.hyperSlice(inputInterval, i, slicePosition[i]);
				outputSlice = Views.hyperSlice(output, i, slicePosition[i]);
			}

			final FloatProcessor fp = Util.materialize(inputSlice);
			fp.setMinAndMax(minIntensity, maxIntensity);
			final ImagePlus imp = new ImagePlus("", fp);

			Flat.getFastInstance().run(imp, radius, bins, slope, null, false);

			Util.copy(
					Views.translate(
							Converters.convert(
									(RandomAccessibleInterval<T>)ImagePlusImgs.from(imp),
									(a, b ) -> {},
									type),
							min),
					outputSlice);

			// increase slicePositions
			for (d = 2; d < n; ++d) {
				++slicePosition[d];
				if (slicePosition[d] <= max[d])
					break;
				else
					slicePosition[d] = min[d];
			}
		}
	}
}
