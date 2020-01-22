/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.hotknife;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

/**
 *
 * @author Stephan Saalfeld
 */
public class FillNaN
{
	final protected FloatProcessor fp;

	public FillNaN( final FloatProcessor fp )
	{
		this.fp = fp;
	}

	/**
	 *
	 * @param blockRadiusX
	 * @param blockRadiusY
	 * @param meanFactor
	 */
	public void run()
	{
		final int width = fp.getWidth();
		final int height = fp.getHeight();
		final int wh = width * height;

		final int w = width - 1;
		final int h = height - 1;

		final FloatProcessor fpCopy = ( FloatProcessor )fp.duplicate();

		int numSaturatedPixels = 0, numSaturatedPixelsBefore;
		do
		{
			numSaturatedPixelsBefore = numSaturatedPixels;
			numSaturatedPixels = 0;
			for ( int i = 0; i < wh; ++i )
			{
				final float v = fp.getf( i );

				if ( Float.isNaN( v ) )
				{
					++numSaturatedPixels;

					final int y = i / width;
					final int x = i % width;

					float s = 0;
					float n = 0;
					if ( y > 0 )
					{
						if ( x > 0 )
						{
							final float tl = fp.getf( x - 1, y - 1 );
							if ( !Float.isNaN( tl ) )
							{
								s += 0.5f * tl;
								n += 0.5f;
							}
						}
						final float t = fp.getf( x, y - 1 );
						if ( !Float.isNaN( t ) )
						{
							s += t;
							n += 1;
						}
						if ( x < w )
						{
							final float tr = fp.getf( x + 1, y - 1 );
							if ( !Float.isNaN( tr ) )
							{
								s += 0.5f * tr;
								n += 0.5f;
							}
						}
					}

					if ( x > 0 )
					{
						final float l = fp.getf( x - 1, y );
						if ( !Float.isNaN( l ) )
						{
							s += l;
							n += 1;
						}
					}
					if ( x < w )
					{
						final float r = fp.getf( x + 1, y );
						if ( !Float.isNaN( r ) )
						{
							s += r;
							n += 1;
						}
					}

					if ( y < h )
					{
						if ( x > 0 )
						{
							final float bl = fp.getf( x - 1, y + 1 );
							if ( !Float.isNaN( bl ) )
							{
								s += 0.5f * bl;
								n += 0.5f;
							}
						}
						final float b = fp.getf( x, y + 1 );
						if ( !Float.isNaN( b ) )
						{
							s += b;
							n += 1;
						}
						if ( x < w )
						{
							final float br = fp.getf( x + 1, y + 1 );
							if ( !Float.isNaN( br ) )
							{
								s += 0.5f * br;
								n += 0.5f;
							}
						}
					}

					if ( n > 0 )
						fpCopy.setf( i, s / n );
				}
			}
			fp.setPixels( fpCopy.getPixelsCopy() );
			System.out.println(numSaturatedPixels);
		}
		while ( numSaturatedPixels != numSaturatedPixelsBefore );
	}

	public static void main(final String... args) {

		new ImageJ();

		final ImagePlus imp = IJ.openImage("/nrs/flyem/alignment/test_flow/Sec06_24129_test.tif");
		final ImagePlus mask = IJ.openImage("/nrs/flyem/alignment/test_flow/Sec06_24129_mask.tif");

		final ImageProcessor ipImp = imp.getProcessor();
		final ImageProcessor ipMask = mask.getProcessor();

		imp.show();
		mask.show();

		final int n = imp.getWidth() * imp.getHeight();

		for (int i = 0; i < n; ++i) {

			if (ipMask.getf(i) > 0) {
				ipImp.setf(i, Float.NaN);
			}
		}

		new FillNaN((FloatProcessor)ipImp).run();
	}
}
