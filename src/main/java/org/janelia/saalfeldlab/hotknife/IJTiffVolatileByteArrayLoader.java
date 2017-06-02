/*
 * #%L
 * BigDataViewer core classes with minimal dependencies
 * %%
 * Copyright (C) 2012 - 2016 Tobias Pietzsch, Stephan Saalfeld, Stephan Preibisch,
 * Jean-Yves Tinevez, HongKee Moon, Johannes Schindelin, Curtis Rueden, John Bogovic
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

import bdv.img.cache.CacheArrayLoader;
import ij.ImagePlus;
import ij.io.Opener;
import net.imglib2.img.basictypeaccess.volatiles.array.VolatileByteArray;

public class IJTiffVolatileByteArrayLoader implements CacheArrayLoader< VolatileByteArray >
{
	private final String urlFormat;

	private final int tileWidth;

	private final int tileHeight;

	/**
	 * <p>Create a {@link CacheArrayLoader} for a CATMAID source.  Tiles are
	 * addressed, in this order, by their</p>
	 * <ul>
	 * <li>scale level,</li>
	 * <li>scale,</li>
	 * <li>x,</li>
	 * <li>y,</li>
	 * <li>z,</li>
	 * <li>tile width,</li>
	 * <li>tile height,</li>
	 * <li>tile row, and</li>
	 * <li>tile column.</li>
	 * </ul>
	 * <p><code>urlFormat</code> specifies how these parameters are used
	 * to generate a URL referencing the tile.  Examples:</p>
	 *
	 * <dl>
	 * <dt>"http://catmaid.org/my-data/xy/%5$d/%8$d_%9$d_%1$d.jpg"</dt>
	 * <dd>CATMAID DefaultTileSource (type 1)</dd>
	 * <dt>"http://catmaid.org/my-data/xy/?x=%3$d&amp;y=%4$d&amp;width=%6d&amp;height=%7$d&amp;row=%8$d&amp;col=%9$d&amp;scale=%2$f&amp;z=%4$d"</dt>
     * <dd>CATMAID RequestTileSource (type 2)</dd>
	 * <dt>"http://catmaid.org/my-data/xy/%1$d/%5$d/%8$d/%9$d.jpg"</dt>
	 * <dd>CATMAID LargeDataTileSource (type 5)</dd>
	 * </dl>
	 *
	 * @param urlFormat
	 * @param tileWidth
	 * @param tileHeight
	 */
	public IJTiffVolatileByteArrayLoader(final String urlFormat, final int tileWidth, final int tileHeight) {
		this.urlFormat = urlFormat;
		this.tileWidth = tileWidth;
		this.tileHeight = tileHeight;
	}

	@Override
	public int getBytesPerElement() {
		return 1;
	}

	@Override
	public VolatileByteArray loadArray(
			 final int timepoint,
			 final int setup,
			 final int level,
			 final int[] dimensions,
			 final long[] min) throws InterruptedException {

		final double scale = 1.0 / Math.pow(2.0, level);

		final long c0 = min[ 0 ] / tileWidth;
		final long r0 = min[ 1 ] / tileHeight;
		final long x0 = c0 * tileWidth;
		final long y0 = r0 * tileHeight;

		final String urlString = String.format( urlFormat, level, scale, x0, y0, min[2], tileWidth, tileHeight, r0, c0);
		final ImagePlus imp = new Opener().openImage(urlString);

		return new VolatileByteArray((byte[])imp.getProcessor().convertToByteProcessor().getPixels(), true);
	}
}
