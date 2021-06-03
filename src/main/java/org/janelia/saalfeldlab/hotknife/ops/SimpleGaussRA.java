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

import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.ops.Ops;
import net.imagej.ops.special.computer.AbstractUnaryComputerOp;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.algorithm.gauss3.SeparableSymmetricConvolution;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;

/**
 * Simple Gaussian filter Op
 *
 * @author Stephan Saalfeld
 * @author Christian Dietz (University of Konstanz)
 * @param <T> type of input and output
 */
@Plugin(type = Ops.Filter.Gauss.class, priority = 0.5)
public class SimpleGaussRA<T extends NumericType<T> & NativeType<T>> extends
	AbstractUnaryComputerOp<RandomAccessible<T>, RandomAccessibleInterval<T>>
	implements Ops.Filter.Gauss, Consumer<RandomAccessibleInterval<T>> {

	@Parameter
	final private double[] sigmas;

	public SimpleGaussRA(final double[] sigmas) {

		this.sigmas = sigmas;
	}

	@Override
	public void compute(
			final RandomAccessible<T> input,
			final RandomAccessibleInterval<T> output) {

		try {
			SeparableSymmetricConvolution.convolve(
					Gauss3.halfkernels(sigmas),
					input,
					output,
					Executors.newSingleThreadExecutor());
		} catch (final IncompatibleTypeException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void run() {

		compute(in(), out());

	}

	@Override
	public RandomAccessibleInterval<T> run(final RandomAccessibleInterval<T> output) {

		compute(in(), output);
		return output;
	}

	@Override
	public void accept(final RandomAccessibleInterval<T> output) {

		compute(in(), output);
	}
}
