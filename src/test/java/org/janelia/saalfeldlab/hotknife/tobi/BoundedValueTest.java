/*-
 * #%L
 * BigDataViewer core classes with minimal dependencies.
 * %%
 * Copyright (C) 2012 - 2021 BigDataViewer developers.
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
package org.janelia.saalfeldlab.hotknife.tobi;

import org.junit.Assert;
import org.junit.Test;

// TODO: move to bdv-core
public class BoundedValueTest
{
	@Test
	public void testConstructor()
	{
		new BoundedValue( 0, 1, 0 );
	}

	@Test( expected = IllegalArgumentException.class )
	public void testConstructorFails1()
	{
		new BoundedValue( 0, 1, -1 );
	}

	@Test( expected = IllegalArgumentException.class )
	public void testConstructorFails2()
	{
		new BoundedValue( 0, -1, 0 );
	}

	@Test( expected = IllegalArgumentException.class )
	public void testConstructorFails3()
	{
		new BoundedValue( 0, 1, 2 );
	}

	@Test
	public void testWithValue()
	{
		Assert.assertEquals(
				new BoundedValue( 0, 1, 0 ).withValue( -1 ),
				new BoundedValue( -1, 1, -1 )
		);
		Assert.assertEquals(
				new BoundedValue( 0, 1, 0 ).withValue( 0.5 ),
				new BoundedValue( 0, 1, 0.5 )
		);
		Assert.assertEquals(
				new BoundedValue( 0, 1, 0 ).withValue( 2 ),
				new BoundedValue( 0, 2, 2 )
		);
	}

	@Test
	public void testWithMinBound()
	{
		Assert.assertEquals(
				new BoundedValue( 0, 3, 1 ).withMinBound( -1 ),
				new BoundedValue( -1, 3, 1 )
		);
		Assert.assertEquals(
				new BoundedValue( 0, 3, 1 ).withMinBound( 0.5 ),
				new BoundedValue( 0.5, 3, 1 )
		);
		Assert.assertEquals(
				new BoundedValue( 0, 3, 1).withMinBound( 1.5 ),
				new BoundedValue( 1.5, 3, 1.5 )
		);
		Assert.assertEquals(
				new BoundedValue( 0, 3, 1 ).withMinBound( 4 ),
				new BoundedValue( 4, 4, 4 )
		);
	}

	@Test
	public void testWithMaxBound()
	{
		Assert.assertEquals(
				new BoundedValue( 0, 3, 1 ).withMaxBound( 4 ),
				new BoundedValue( 0, 4, 1 )
		);
		Assert.assertEquals(
				new BoundedValue( 0, 3, 1 ).withMaxBound( 2.5 ),
				new BoundedValue( 0, 2.5, 1 )
		);
		Assert.assertEquals(
				new BoundedValue( 0, 3, 2 ).withMaxBound( 1.5 ),
				new BoundedValue( 0, 1.5, 1.5 )
		);
		Assert.assertEquals(
				new BoundedValue( 0, 3, 1 ).withMaxBound( -1 ),
				new BoundedValue( -1, -1, -1 )
		);
	}
}
