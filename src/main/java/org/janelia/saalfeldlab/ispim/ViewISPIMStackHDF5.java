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

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import ij.ImageJ;
import loci.formats.FormatException;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.util.Util;

public class ViewISPIMStackHDF5 implements Serializable {



	public static void main( String args[] ) throws IOException, InterruptedException, ExecutionException, FormatException {

		// test HDF5 opening through N5
		final N5HDF5Reader h5reader = new N5HDF5Reader( "/Volumes/saalfeld/from_mdas/h5write/Pos001.gz.h5", 0, 0, 0 );
		String[] datasets = h5reader.list( "t00000/s00/0/" );
		for ( final String d : datasets )
			System.out.println( d );

		RandomAccessibleInterval img = N5Utils.open( h5reader, "t00000/s00/0/cells" );
		
		System.out.println( Util.printInterval( img ) );

		new ImageJ();
		
		ImageJFunctions.show( img );

	}
}
