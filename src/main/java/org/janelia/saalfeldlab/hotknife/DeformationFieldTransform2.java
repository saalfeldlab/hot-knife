/*
 * #%L
 * ImgLib2: a general-purpose, multidimensional image processing library.
 * %%
 * Copyright (C) 2009 - 2017 Tobias Pietzsch, Stephan Preibisch, Stephan Saalfeld,
 * John Bogovic, Albert Cardona, Barry DeZonia, Christian Dietz, Jan Funke,
 * Aivar Grislis, Jonathan Hale, Grant Harris, Stefan Helfrich, Mark Hiner,
 * Martin Horn, Steffen Jaensch, Lee Kamentsky, Larry Lindsey, Melissa Linkert,
 * Mark Longair, Brian Northan, Nick Perry, Curtis Rueden, Johannes Schindelin,
 * Jean-Yves Tinevez and Michael Zinsmaier.
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

package org.janelia.saalfeldlab.hotknife;

import net.imglib2.RealLocalizable;
import net.imglib2.RealPositionable;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.RealType;

/**
 * A {@link RealTransform} by continuous offset lookup.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class DeformationFieldTransform2<T extends RealType<T>> implements RealTransform {

	private final RealRandomAccess<T> positionAccess;

	private final int n;

	public DeformationFieldTransform2(final RealRandomAccess<T> positionAccess) {

		this.positionAccess = positionAccess;
		n = positionAccess.numDimensions() - 1;
	}

	@SuppressWarnings("unchecked")
	public DeformationFieldTransform2(final RealRandomAccessible<T> position) {

		positionAccess = position.realRandomAccess();
		n = position.numDimensions() - 1;
	}

	@Override
	public int numSourceDimensions() {

		return n;
	}

	@Override
	public int numTargetDimensions() {

		return n;
	}

	@Override
	public void apply(final double[] source, final double[] target) {

		for ( int d = 0; d < n; d++ )
			positionAccess.setPosition( source[ d ], d );

		for (int d = 0; d < n; d++) {
			positionAccess.setPosition( d, n);
			target[d] = positionAccess.get().getRealDouble() + source[d];
		}
	}

	@Override
	public void apply(final float[] source, final float[] target) {

		for ( int d = 0; d < n; d++ )
			positionAccess.setPosition( source[ d ], d );

		for (int d = 0; d < n; d++) {
			positionAccess.setPosition( d, n);
			target[d] = (float)(positionAccess.get().getRealDouble() + source[d]);
		}
	}

	@Override
	public void apply(final RealLocalizable source, final RealPositionable target) {

		for ( int d = 0; d < n; d++ )
			positionAccess.setPosition(source.getDoublePosition(d), d );

		for (int d = 0; d < n; d++) {
			positionAccess.setPosition( d, n);
			target.setPosition(positionAccess.get().getRealDouble() + source.getDoublePosition(d), d);
		}

	}

	@Override
	public RealTransform copy() {

		return new DeformationFieldTransform2<>(positionAccess.copyRealRandomAccess());
	}
}
