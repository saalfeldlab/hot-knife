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

import java.util.ArrayList;
import java.util.function.Consumer;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

/**
 * Absolute Gradient (magintude)
 *
 * @author Stephan Preibisch
 */
public class AbsoluteGradientCenter<T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>>
{
	final private ArrayList<RandomAccessible<T>> sourcesA;
	final private ArrayList<RandomAccessible<T>> sourcesB;

	final private int n;
	final double[] norm;

	public AbsoluteGradientCenter(final RandomAccessible<T> source, final double[] sigma)
	{
		this.n = source.numDimensions();
		this.sourcesA = new ArrayList<>( n );
		this.sourcesB = new ArrayList<>( n );
		this.norm = new double[ n ];

		for ( int axis = 0; axis < n; ++axis )
		{
			final long[] offset = new long[source.numDimensions()];
			offset[axis] = -1;
			sourcesA.add( Views.offset(source, offset) );
			sourcesB.add( Views.translate(source, offset) );
			this.norm[ axis ] = 2.0 / sigma[ axis ];
		}
	}

	@Override
	public void accept(final RandomAccessibleInterval<T> output)
	{
		final Cursor<T> c = Views.flatIterable(output).cursor();

		final ArrayList<Cursor<T>> as = new ArrayList<>();
		final ArrayList<Cursor<T>> bs = new ArrayList<>();

		for ( int d = 0; d < n; ++d )
		{
			as.add( Views.flatIterable(Views.interval(sourcesA.get( d ), output)).cursor() );
			bs.add( Views.flatIterable(Views.interval(sourcesB.get( d ), output)).cursor() );
		}

		while (c.hasNext())
		{
			final T t = c.next();

			double sumSquare = 0;

			for ( int d = 0; d < n; ++d )
			{
				final double gradient = (bs.get( d ).next().getRealDouble() - as.get( d ).next().getRealDouble()) * norm[ d ];
				sumSquare += gradient * gradient;
			}

			t.setReal( Math.sqrt( sumSquare ));
		}
	}
}
