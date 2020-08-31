/*
 * #%L
 * ImgLib2: a general-purpose, multidimensional image processing library.
 * %%
 * Copyright (C) 2009 - 2016 Tobias Pietzsch, Stephan Preibisch, Stephan Saalfeld,
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

package net.imglib2.realtransform;

import java.util.ArrayList;
import java.util.Arrays;

import Jama.Matrix;
import net.imglib2.EuclideanSpace;
import net.imglib2.Localizable;
import net.imglib2.Point;
import net.imglib2.Positionable;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPositionable;
import net.imglib2.concatenate.Concatenable;
import net.imglib2.concatenate.PreConcatenable;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.array.ArrayRandomAccess;
import net.imglib2.img.basictypeaccess.array.LongArray;
import net.imglib2.transform.Transform;
import net.imglib2.transform.integer.BoundingBox;
import net.imglib2.transform.integer.BoundingBoxTransform;
import net.imglib2.type.numeric.integer.LongType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;

/**
 * An <em>n</em>-dimensional long precision affine transformation. This
 * transformation supports all transformations that can be expressed by long
 * integers such as:
 * <ul>
 * <li>discrete translation</li>
 * <li>90 degrees rotations</li>
 * <li>axis permutations</li>
 * <li>discrete subsampling</li>
 * <li>discrete shearing</li>
 * </ul>
 *
 * The inverse of this transform is typically not a {@link LongAffineTransform}
 * but an {@link AffineTransform}.
 *
 * @author Stephan Saalfeld
 */
public class
LongAffineTransform implements EuclideanSpace, Transform, RealTransform, Concatenable< LongAffineTransform >, PreConcatenable< LongAffineTransform >, BoundingBoxTransform
{
	private static class Element
	{
		public final int r;
		public final int c;
		public final double diff;
		public final double value;
		public final double newValue;

		public Element( final int r, final int c, final double diff, final double value, final double newValue )
		{
			this.r = r;
			this.c = c;
			this.diff = diff;
			this.value = value;
			this.newValue = newValue;
		}

		@Override
		public String toString()
		{
			return "(" + r + ", " + c + ") : " + diff + ", " + value + ", " + newValue;
		}
	}

	final protected int n;

	protected final long[] atArray;

	protected final ArrayImg< LongType, LongArray > at;

	protected final ArrayRandomAccess< LongType > atAccess;

	protected final long[] tmpLong;

	protected final double[] tmp;

	protected final Point[] ds;

	protected void identity()
	{

		Arrays.fill( atArray, 0 );
		Arrays.fill( tmpLong, 0 );
		for ( int d = 0; d < n; ++d )
		{
			atAccess.setPosition( d, 0 );
			atAccess.setPosition( d, 1 );
			atAccess.get().set( 1 );
		}
		atAccess.setPosition( n, 0 );
		for ( int d = 0; d < n; ++d )
		{
			atAccess.setPosition( d, 1 );
			atAccess.get().set( 0 );
			ds[ d ].setPosition( tmpLong );
			ds[ d ].setPosition( 1, d );
		}
	}

	protected void updateD( final int d )
	{

		atAccess.setPosition( d, 0 );
		for ( int l = 0; l < n; ++l )
		{
			atAccess.setPosition( l, 1 );
			ds[ d ].setPosition( atAccess.get().get(), l );
		}
	}

	protected void updateDs()
	{

		for ( int d = 0; d < n; ++d )
		{
			updateD( d );
		}
	}

	public LongAffineTransform( final int n )
	{
		atArray = new long[ ( n + 1 ) * n ];
		this.n = n;
		at = ArrayImgs.longs( atArray, n + 1, n );
		atAccess = at.randomAccess();
		tmp = new double[ n ];
		tmpLong = new long[ n ];
		ds = new Point[ n ];
		for ( int d = 0; d < n; ++d )
			ds[ d ] = new Point( n );
		identity();
	}

	public LongAffineTransform( final long... atArray )
	{
		this.atArray = atArray;
		n = ( int ) Math.sqrt( atArray.length );
		at = ArrayImgs.longs( atArray, n + 1, n );
		atAccess = at.randomAccess();
		tmp = new double[ n ];
		tmpLong = new long[ n ];
		ds = new Point[ n ];
		for ( int d = 0; d < n; ++d )
			ds[ d ] = new Point( n );
		updateDs();
	}

	public LongAffineTransform( final LongAffineTransform template )
	{
		this.atArray = template.atArray.clone();
		n = template.n;
		at = ArrayImgs.longs( atArray, n + 1, n );
		atAccess = at.randomAccess();
		tmp = template.tmp.clone();
		tmpLong = template.tmpLong.clone();
		ds = new Point[ n ];
		for ( int d = 0; d < n; ++d )
		{
			ds[ d ] = new Point( n );
			ds[ d ].setPosition( template.ds[ d ] );
		}
	}

	public AffineTransform toAffineTransform()
	{
		final double[] doubleParameters = new double[ atArray.length ];
		for ( int i = 0; i < atArray.length; ++i )
			doubleParameters[ i ] = atArray[ i ];

		return new AffineTransform( doubleParameters );
	}

	public static void fullRank(
			final Matrix affineMatrix,
			final Matrix roundMatrix )
	{
		final int n = roundMatrix.getRowDimension();
		int rank = roundMatrix.rank();
		if ( rank == n )
			return;

		final Element[] sortedMatrix = new Element[ n * n ];
		for ( int r = 0, i = 0; r < n; ++r )
		{
			for ( int c = 0; c < n; ++c, ++i )
			{
				final double real = affineMatrix.get( r, c );
				final double round = roundMatrix.get( r, c ); // TODO could this be just round?
				final double diff = round - real;
				if ( diff < 0 )
					sortedMatrix[ i ] = new Element( r, c, -diff, round, round + 1 );
				else
					sortedMatrix[ i ] = new Element( r, c, diff, round, round - 1 );

			}
		}

		Arrays.sort( sortedMatrix, ( a, b ) -> a.diff < b.diff ? 1 : a.diff > b.diff ? -1 : 0 );

//		System.out.println( Arrays.toString( sortedMatrix ) );

//		System.out.println( "before " );
//		roundMatrix.print( 5, 2 );

		for ( final Element e : sortedMatrix  )
		{
			roundMatrix.set( e.r, e.c, e.newValue );
			final int newRank = roundMatrix.rank();
			if ( newRank == n )
			{
//				System.out.println( "changed " );
//				roundMatrix.print( 5, 2 );
				return;
			}
			if ( newRank <= rank )
				roundMatrix.set( e.r, e.c, e.value );
			else
			{
				rank = newRank;
//				System.out.println( "changed " );
//				roundMatrix.print( 5, 2 );
			}
		}
	}

	/**
	 * A = A_{rest} * A_{round}
	 *
	 * A * A_{round}^{-1} = A_{rest}
	 *
	 *
	 * @param affine
	 * @return
	 */
	public static Pair< LongAffineTransform, AffineTransform > decomposeLongReal(
			final AffineGet affine )
	{
		final int n = affine.numDimensions();

		final Matrix affineMatrix = new Matrix( n, n );

		final Matrix roundMatrix = new Matrix( n, n );

		for ( int r = 0; r < n; ++r )
		{
			for ( int c = 0; c < n; ++c )
			{
				final double value = affine.get( r, c );
				affineMatrix.set( r, c, value );
				roundMatrix.set( r, c, Math.round( value ) );
			}
		}

		fullRank( affineMatrix, roundMatrix );

		final long[] atLong = new long[ n * n + n ];
		final double[] atRound = new double[ atLong.length ];
		for ( int r = 0, i = 0; r < n; ++r, ++i )
		{
			for ( int c = 0; c < n; ++c, ++i )
			{
				atRound[ i ] = atLong[ i ] = ( long ) roundMatrix.get( r, c );
			}
			atRound[ i ] = atLong[ i ] = Math.round( affine.get( r, n ) );
		}

		final LongAffineTransform longAffine = new LongAffineTransform( atLong );
		final AffineTransform rest = new AffineTransform( atRound ).inverse();
		rest.preConcatenate( affine );

		return new ValuePair<>(
				longAffine,
				rest );
	}

	private static boolean nonZero( final double[] array )
	{
		for ( int i = 0; i < array.length; ++i )
			if ( array[ i ] == 0 )
				return false;
		return true;
	}

	private static void affineToMatrix( final AffineGet affine, final Matrix matrix )
	{
		final int n = affine.numDimensions();
		for ( int r = 0; r < n; ++r )
			for ( int c = 0; c < n; ++c )
				matrix.set( r,  c,  affine.get( r, c ) );
	}

	private static void scale( final Matrix source, final double[] scale, final Matrix target )
	{
		final int n = scale.length;
		for ( int r = 0; r < n; ++r )
			for ( int c = 0; c < n; ++c )
				target.set( r,  c,  source.get( r, c ) * scale[ c ] );
	}

	private static void unscale( final Matrix source, final double[] scale, final Matrix target )
	{
		final int n = scale.length;
		for ( int c = 0; c < n; ++c )
		{
			final double s = 1.0 / scale[ c ];
			for ( int r = 0; r < n; ++r )
				target.set( r,  c,  source.get( r, c ) * s );
		}
	}

	private static double det( final double[] scale )
	{
		final int n = scale.length;
		double det = scale[ 0 ];
		for ( int i = 1; i < n; ++i )
			det *= scale[ i ];

		return det;
	}

	private static double ratioToOne( final double a )
	{
		if ( a > 1 )
			return 1.0 / a;
		else
			return a;

	}

	private static void round( final Matrix matrix, final long[] round )
	{
		final int n = matrix.getRowDimension();
		for ( int r = 0, i = 0; r < n; ++r, ++i )
			for ( int c = 0; c < n; ++c, ++i )
				round[ i ] = Math.round( matrix.get( r, c ) );
	}

	private static double findInvertibleLongMatrix(
			final double[] scale,
			final AffineGet affine,
			final int[] indices,
			final Matrix affineMatrix,
			final LongAffineTransform roundAffine,
			double bestDetRatio,
			final double[] bestScale )
	{
		final int n = scale.length;
		Arrays.setAll( scale, j -> Math.abs( affine.get( indices[ j ], j ) ) );
    	System.out.print( Arrays.toString( indices ) );
    	System.out.print( " -> " );
    	System.out.println( Arrays.toString( scale ) );

		if ( nonZero( scale ) ) {
			affineToMatrix( affine, affineMatrix );
			unscale( affineMatrix, scale, affineMatrix );

			round( affineMatrix, roundAffine.atArray );

			System.out.println( Arrays.toString( roundAffine.atArray ) );

			if ( roundAffine.createInverse() != null )
			{
				final double detRatio = ratioToOne( affineMatrix.det() );
				if ( detRatio > bestDetRatio )
				{
					System.arraycopy( scale, 0, bestScale, 0, n );
					bestDetRatio = detRatio;
				}

				System.out.println( "Found invertible round matrix." );

				System.out.println( Arrays.toString( bestScale ) + " : det " + affineMatrix.det() );
				affineMatrix.print( 4, 2 );
			}
		}

		return bestDetRatio;
	}

	public LongAffineTransform createInverse()
	{
		final AffineTransform inverseAffine = toAffineTransform().inverse();

		System.out.println(Arrays.toString(inverseAffine.getRowPackedCopy()));

		final long[] inverseAtArray = new long[ ( n + 1 ) * n ];
		final double[] inverseAffineValues = inverseAffine.getRowPackedCopy();
		for ( int i = 0; i < inverseAtArray.length; ++i )
		{
			final double value = inverseAffineValues[ i ];
			final long rounded = Math.round( value );
			if ( Math.abs( rounded - value ) > Math.ulp( value ) )
				return null;

			inverseAtArray[ i ] = rounded;
		}

		final Matrix roundMatrix = new Matrix( n, n );

		for ( int i = 0, r = 0; r < n; ++r, ++i )
			for ( int c = 0; c < n; ++c, ++i )
				roundMatrix.set( r, c, inverseAtArray[ i ] );

		if ( roundMatrix.rank() < n )
			return null;

		return new LongAffineTransform( inverseAtArray );
	}

	/**
	 * A = A_{round} * A_{rest}
	 *
	 * A_{round}^{-1} * A = A_{rest}
	 *
	 *
	 * @param affine
	 * @return
	 */
	public static Pair< AffineTransform, LongAffineTransform > decomposeRealLong(
			final AffineGet affine )
	{
		final int n = affine.numDimensions();

		final Matrix affineMatrix = new Matrix( n, n );

		final Matrix roundMatrix = new Matrix( n, n );

		/* test all permutations (Heap's algorithm from https://www.baeldung.com/java-array-permutations) */
		final int[] permutationIndices = new int[ n ];

		final int[] indices = new int[ n ];
		Arrays.setAll( indices, i -> i );
		System.out.println( Arrays.toString( indices ) );

		final ArrayList< double[] > scales = new ArrayList<>();
		final double[] scale = new double[ n ];
		final double[] bestScale = new double[ n ];

		final long[] atLong = new long[ n * n + n ];
		final LongAffineTransform roundAffine = new LongAffineTransform( atLong );

		double bestDetRatio = findInvertibleLongMatrix(
				scale,
				affine,
				indices,
				affineMatrix,
				roundAffine,
				0,
				bestScale);

		for (int i = 0; i < n;) {
		    if ( permutationIndices[ i ] < i )
		    {
		    	final int a = indices[ i ];
		    	if ( ( i & 0x1 ) == 0 ) {
		    		indices[ i ] = indices[ 0 ];
		    		indices[ 0 ] = a;
		    	}
		    	else
		    	{
		    		indices[ i ] = indices[ permutationIndices[ i ] ];
		    		indices[ permutationIndices[ i ] ] = a;
		    	}

		    	bestDetRatio = findInvertibleLongMatrix(
						scale,
						affine,
						indices,
						affineMatrix,
						roundAffine,
						bestDetRatio,
						bestScale);

		        ++permutationIndices[ i ];
		        i = 0;
		    }
		    else {
		        permutationIndices[ i ] = 0;
		        ++i;
		    }
		}

		/* fill and scale affine matrix */
		affineToMatrix( affine, affineMatrix );
		unscale( affineMatrix, bestScale, affineMatrix );

		/* round */
		for ( int r = 0; r < n; ++r )
			for ( int c = 0; c < n; ++c )
				roundMatrix.set( r, c, Math.round( affineMatrix.get( r, c ) ) );

		affineMatrix.print(4, 2);
		roundMatrix.print(4, 2);

		fullRank( affineMatrix, roundMatrix );

		final double[] atRound = new double[ atLong.length ];
		for ( int r = 0, i = 0; r < n; ++r, ++i )
		{
			for ( int c = 0; c < n; ++c, ++i )
			{
				atRound[ i ] = atLong[ i ] = ( long ) roundMatrix.get( r, c );
			}
			atRound[ i ] = atLong[ i ] = Math.round( affine.get( r, n ) );
		}

		final LongAffineTransform longAffine = new LongAffineTransform( atLong );
		final AffineTransform rest = new AffineTransform( atRound ).inverse();
		rest.concatenate( affine );

		return new ValuePair<>(
				rest,
				longAffine );
	}

	public void set( final LongAffineTransform template )
	{
		assert n == template.numDimensions(): "Dimensions do not match.";

		System.arraycopy( template.atArray.clone(), 0, atArray, 0, n );
		System.arraycopy( template.tmp, 0, tmp, 0, n );
		System.arraycopy( template.tmpLong, 0, tmpLong, 0, n );
		for ( int i = 0; i < n; ++i )
		{
			ds[ i ].setPosition( template.ds[ i ] );
		}
	}

	@Override
	public LongAffineTransform concatenate( final LongAffineTransform other )
	{
		assert other.numDimensions() == n: "Dimensions do not match.";

		final ArrayRandomAccess< LongType > atAccessOther = other.atAccess;

		final long[] result = new long[ atArray.length ];

		for ( int i = 0, r = 0; r < n; ++r, ++i )
		{
			atAccess.setPosition( r, 1 );
			for ( int c = 0; c < n; ++c, ++i )
			{
				atAccessOther.setPosition( c, 0 );
				long value = 0;
				for ( int l = 0; l < n; ++l )
				{
					atAccess.setPosition( l, 0 );
					atAccessOther.setPosition( l, 1 );
					value += atAccess.get().get() * atAccessOther.get().get();
				}
				result[ i ] = value;
			}
			atAccess.setPosition( n, 0 );
			atAccessOther.setPosition( n, 0 );
			long value = atAccess.get().get();
			for ( int l = 0; l < n; ++l )
			{
				atAccess.setPosition( l, 0 );
				atAccessOther.setPosition( l, 1 );
				value += atAccess.get().get() * atAccessOther.get().get();
			}
			result[ i ] = value;
		}

		System.arraycopy( result, 0, atArray, 0, atArray.length );

		updateDs();

		return this;
	}

	@Override
	public Class< LongAffineTransform > getConcatenableClass()
	{
		return LongAffineTransform.class;
	}

	@Override
	public LongAffineTransform preConcatenate( final LongAffineTransform other )
	{
		assert other.numDimensions() == n: "Dimensions do not match.";

		final ArrayRandomAccess< LongType > atAccessOther = other.atAccess;

		final long[] result = new long[ atArray.length ];

		for ( int i = 0, r = 0; r < n; ++r, ++i )
		{
			atAccessOther.setPosition( r, 1 );
			for ( int c = 0; c < n; ++c, ++i )
			{
				atAccess.setPosition( c, 0 );
				long value = 0;
				for ( int l = 0; l < n; ++l )
				{
					atAccessOther.setPosition( l, 0 );
					atAccess.setPosition( l, 1 );
					value += atAccessOther.get().get() * atAccess.get().get();
				}
				result[ i ] = value;
			}
			atAccessOther.setPosition( n, 0 );
			atAccess.setPosition( n, 0 );
			long value = atAccessOther.get().get();
			for ( int l = 0; l < n; ++l )
			{
				atAccessOther.setPosition( l, 0 );
				atAccess.setPosition( l, 1 );
				value += atAccessOther.get().get() * atAccess.get().get();
			}
			result[ i ] = value;
		}

		System.arraycopy( result, 0, atArray, 0, atArray.length );

		updateDs();

		return this;
	}

	@Override
	public Class< LongAffineTransform > getPreConcatenableClass()
	{
		return LongAffineTransform.class;
	}

	public void set( final long value, final int row, final int column )
	{
		atAccess.setPosition( column, 0 );
		atAccess.setPosition( row, 1 );
		atAccess.get().set( value );

		if ( column < n )
			updateD( column );
	}

	public void set( final long... values )
	{
		System.arraycopy( values, 0, atArray, 0, Math.min( atArray.length, values.length ) );

		updateDs();
	}

	@Override
	public LongAffineTransform copy()
	{
		return new LongAffineTransform( this );
	}

	@Override
	public boolean isIdentity()
	{
		for ( int r = 0; r < n; ++r )
		{
			atAccess.setPosition( r, 1 );
			for ( int c = 0; c <= n; ++c )
			{
				atAccess.setPosition( c, 0 );
				final long value = atAccess.get().get();
				if ( ( r == c && value != 1 ) || value != 0 )
					return false;
			}
		}
		return true;
	}

	@Override
	public void apply( final double[] source, final double[] target )
	{
		for ( int r = 0; r < n; ++r )
		{
			atAccess.setPosition( r, 1 );
			double value = 0;
			for ( int c = 0; c < n; ++c )
			{
				atAccess.setPosition( c, 0 );
				value += atAccess.get().get() * source[ c ];
			}
			atAccess.setPosition( n, 0 );
			value += atAccess.get().get();
			tmp[ r ] = value;
		}

		System.arraycopy( tmp, 0, target, 0, tmp.length );
	}

	@Override
	public void apply( final RealLocalizable source, final RealPositionable target )
	{
		for ( int r = 0; r < n; ++r )
		{
			atAccess.setPosition( r, 1 );
			long value = 0;
			for ( int c = 0; c < n; ++c )
			{
				atAccess.setPosition( c, 0 );
				value += atAccess.get().get() * source.getDoublePosition( c );
			}
			atAccess.setPosition( n, 0 );
			value += atAccess.get().get();
			tmp[ r ] = value;
		}

		target.setPosition( tmp );
	}

	@Override
	public int numSourceDimensions()
	{
		return numDimensions();
	}

	@Override
	public int numTargetDimensions()
	{
		return numDimensions();
	}

	@Override
	public void apply( final long[] source, final long[] target )
	{
		for ( int r = 0; r < n; ++r )
		{
			atAccess.setPosition( r, 1 );
			long value = 0;
			for ( int c = 0; c < n; ++c )
			{
				atAccess.setPosition( c, 0 );
				value += atAccess.get().get() * source[ c ];
			}
			atAccess.setPosition( n, 0 );
			value += atAccess.get().get();
			tmpLong[ r ] = value;
		}

		System.arraycopy( tmpLong, 0, target, 0, tmpLong.length );
	}

	@Override
	public void apply( final int[] source, final int[] target )
	{
		for ( int r = 0; r < n; ++r )
		{
			atAccess.setPosition( r, 1 );
			long value = 0;
			for ( int c = 0; c < n; ++c )
			{
				atAccess.setPosition( c, 0 );
				value += atAccess.get().get() * source[ c ];
			}
			atAccess.setPosition( n, 0 );
			value += atAccess.get().get();
			tmpLong[ r ] = value;
		}

		for ( int d = 0; d < tmpLong.length; ++d )
			target[ d ] = ( int ) tmpLong[ d ];
	}

	@Override
	public void apply( final Localizable source, final Positionable target )
	{
		for ( int r = 0; r < n; ++r )
		{
			atAccess.setPosition( r, 1 );
			long value = 0;
			for ( int c = 0; c < n; ++c )
			{
				atAccess.setPosition( c, 0 );
				value += atAccess.get().get() * source.getLongPosition( c );
			}
			atAccess.setPosition( n, 0 );
			value += atAccess.get().get();
			tmpLong[ r ] = value;
		}

		target.setPosition( tmpLong );
	}

	@Override
	public int numDimensions()
	{
		return n;
	}

	public Point d( final int d )
	{
		return ds[ d ];
	}

	/**
	 * TODO This would be a fine default implementation
	 */
	@Override
	public BoundingBox transform(final BoundingBox boundingBox) {

		final long[][] corners = new long[][] {
			boundingBox.corner1,
			boundingBox.corner2 };

		final long[] corner = new long[ n ];
		final long[] min = new long[ n ];
		Arrays.fill( min, Long.MAX_VALUE );
		final long[] max = new long[ n ];
		Arrays.fill( max, Long.MIN_VALUE );
		final int[] position = new int[ n ];

		for ( int d = 0; d < n; ) {

			for ( int i = 0; i < n; ++i )
				corner[ i ] = corners[ position[ i ] ][ i ];

			apply( corner, corner );

			for ( int i = 0; i < n; ++i )
			{
				if ( min[ i ] > corner[ i ] )
					min[ i ] = corner[ i ];
				if ( max[ i ] < corner[ i ] )
					max[ i ] = corner[ i ];
			}

			for ( d = 0; d < n; ++d ) {
				if ( position[ d ] == 0 )
				{
					position[ d ] = 1;
					break;
				}
				else
					position[ d ] = 0;
			}
		}

		System.arraycopy( min, 0, boundingBox.corner1, 0, n );
		System.arraycopy( max, 0, boundingBox.corner2, 0, n );

		return null;
	}
}
