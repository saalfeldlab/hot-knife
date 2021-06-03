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

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition_DDRM;

import it.unimi.dsi.fastutil.doubles.DoubleArrays;
import it.unimi.dsi.fastutil.doubles.DoubleComparator;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

/**
 * Gradient
 *
 * @author Stephan Saalfeld
 */
public class TubenessCenter<T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>> {

	private final static DoubleComparator absDoubleComparator = new DoubleComparator() {

		@Override
		public int compare(final double k1, final double k2) {

			final double absK1 = Math.abs(k1);
			final double absK2 = Math.abs(k2);

			return absK1 == absK2 ? 0 : absK1 < absK2 ? -1 : 1;
		}
	};


	private final RandomAccessible<T>[][] gradientsA;
	private final RandomAccessible<T>[][] gradientsB;
	private final double[] norms;

	public TubenessCenter(final RandomAccessible<T>[] gradients, final double[] sigmas) {

		final int n = gradients[0].numDimensions();
		gradientsA = new RandomAccessible[n][n];
		gradientsB = new RandomAccessible[n][n];

		norms = new double[n];

		for (int d = 0; d < n; ++d) {
			norms[d] = sigmas[d] / 2.0;
			final long[] offset = new long[n];
			offset[d] = -1;
			for (int e = d; e < n; ++e) {
				gradientsA[d][e] = Views.offset(gradients[e], offset);
				gradientsB[d][e] = Views.translate(gradients[e], offset);
			}
		}
	}

	@Override
	public void accept(final RandomAccessibleInterval<T> output) {

		final int n = gradientsA[0].length;
		final Cursor<T>[][] a = new Cursor[n][n];
		final Cursor<T>[][] b = new Cursor[n][n];
		for (int d = 0; d < n; ++d) {
			for (int e = d; e < n; ++e) {
				a[d][e] = Views.flatIterable(Views.interval(gradientsA[d][e], output)).cursor();
				b[d][e] = Views.flatIterable(Views.interval(gradientsB[d][e], output)).cursor();
			}
		}
		final Cursor<T> c = Views.flatIterable(output).cursor();

		final DMatrixRMaj hessian = new DMatrixRMaj(n, n);
		final SymmetricQRAlgorithmDecomposition_DDRM eigen = new SymmetricQRAlgorithmDecomposition_DDRM(false);
		final double[] eigenvalues = new double[n];

		final int n1 = n - 1;
		final double oneOverN1 = 1.0 / n1;

A:		while (c.hasNext()) {

			/* TODO Is test if n == 1 and set to 1 meaningful? */

			final T t = c.next();
			for (int d = 0; d < n; ++d) {
				for (int e = d; e < n; ++e) {
					final double hde = (b[d][e].next().getRealDouble() - a[d][e].next().getRealDouble()) * norms[e];
//					final double hde = (b[d][e].next().getRealDouble() - a[d][e].next().getRealDouble());
					hessian.set(d, e, hde);
					hessian.set(e, d, hde);
				}
			}

			eigen.decompose(hessian);
			for (int d = 0; d < n; ++d)
				eigenvalues[d] = eigen.getEigenvalue(d).getReal();

			DoubleArrays.quickSort(eigenvalues, absDoubleComparator);
//			System.out.println(Arrays.toString(eigenvalues));

			if (eigenvalues[n1] < 0) {
				double product = -eigenvalues[n1];
				for (int d = n - 2; d > 1; --d) {
					if (eigenvalues[d] < 0) {
						product *= -eigenvalues[d];
					} else {
						t.setZero();
						continue A;
					}
				}
				if (eigenvalues[1] < 0) {
					product *= (-eigenvalues[1] - Math.abs(eigenvalues[0]));
					t.setReal(Math.pow(product, oneOverN1) / -eigenvalues[n1]);
				} else {
					t.setZero();
					continue A;
				}
			} else {
				double product = eigenvalues[n1];
				for (int d = n - 2; d > 1; --d) {
					if (eigenvalues[d] > 0) {
						product *= eigenvalues[d];
					} else {
						t.setZero();
						continue A;
					}
				}
				if (eigenvalues[1] > 0) {
					product *= (eigenvalues[1] - Math.abs(eigenvalues[0]));
					t.setReal(Math.pow(product, oneOverN1) / -eigenvalues[n1]);
				} else {
					t.setZero();
					continue A;
				}
			}
		}
	}
}
