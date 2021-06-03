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
package org.janelia.saalfeldlab.ispim;

import java.io.Serializable;

import net.imglib2.realtransform.AffineTransform2D;

/**
 * Data object for iSPIM stack slices.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Slice implements Serializable
{
	private static final long serialVersionUID = -4717949790891566190L;

	public String path;
	public int index;
	public double[] affine = null; // needs to be serializable!
	public AffineTransform2D affineTransform()
	{
		if ( affine == null )
			return null;
		else
		{
			final AffineTransform2D t = new AffineTransform2D();
			t.set( affine[ 0 ], affine[ 1 ], affine[ 2 ], affine[ 3 ], affine[ 4 ], affine[ 5 ] );
			return t;
		}
	}
}
