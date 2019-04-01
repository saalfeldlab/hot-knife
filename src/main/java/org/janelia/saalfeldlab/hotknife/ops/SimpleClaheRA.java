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

import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.ops.special.computer.AbstractUnaryComputerOp;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.view.MixedTransformView;
import net.imglib2.view.Views;

/**
 * Simple Gaussian filter Op
 *
 * @author Stephan Saalfeld
 * @author Christian Dietz (University of Konstanz)
 * @param <T> type of input and output
 */
@Plugin(type = org.janelia.saalfeldlab.hotknife.ops.SimpleClaheRA.class, priority = 0.5)
public class SimpleClaheRA<T extends NumericType<T> & NativeType<T>> extends
	AbstractUnaryComputerOp<RandomAccessible<T>, RandomAccessibleInterval<T>> {

	@Parameter
	final private int radius;
	final int bins;
	final int threshold;

	public SimpleClaheRA(
			final int radius,
			final int bins,
			final int threshold) {

		this.radius = radius;
		this.bins = bins;
		this.threshold = threshold;
	}

	@Override
	public void compute(
			final RandomAccessible<T> input,
			final RandomAccessibleInterval<T> output) {

		final FinalInterval sliceInterval =
				new FinalInterval(
						new long[] {output.min(0) - radius, output.min(1) - radius},
						new long[] {output.max(0) + radius, output.max(1) + radius});

		/* assuming output is 3D */
		for (long z = output.min(2); z <= output.max(2); ++z) {

			final MixedTransformView<T> inputSlice = Views.hyperSlice(input, 2, z);
//			Flat.getFastInstance().run(imp, radius, bins, slope, mask, composite);

		}
//		try {
//			SeparableSymmetricConvolution.convolve(
//					Gauss3.halfkernels(sigmas),
//					input,
//					output,
//					Executors.newSingleThreadExecutor());
//		} catch (final IncompatibleTypeException e) {
//			throw new RuntimeException(e);
//		}
	}
}
