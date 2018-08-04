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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonSyntaxException;

import ij.ImageJ;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.array.ArrayRandomAccess;
import net.imglib2.img.basictypeaccess.array.ByteArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ShowMask {

	/**
	 * @param args
	 * @throws FileNotFoundException
	 * @throws JsonIOException
	 * @throws JsonSyntaxException
	 * @throws InterruptedException
	 */
	public static void main(final String[] args) throws JsonSyntaxException, JsonIOException, FileNotFoundException, InterruptedException {

		new ImageJ();

		final int[] blockSize = new int[] {650, 650, 71};

		final long[][] blocks = new Gson().fromJson(new FileReader(new File(args[0])), long[][].class);

		final ArrayImg<UnsignedByteType, ByteArray> img = ArrayImgs.unsignedBytes(
				248156 / blockSize[0] + 1,
				133718 / blockSize[1] + 1,
				7062 / blockSize[2] + 1);
		ImageJFunctions.show(img);

		final ArrayRandomAccess<UnsignedByteType> access = img.randomAccess();
		for (int i = 0; i < blocks.length / 10; ++i) {
			final long[] block = blocks[i];
			access.setPosition(new long[] {
					block[2] / blockSize[0],
					block[1] / blockSize[1],
					block[0] / blockSize[2]
			});
			access.get().set(255);
//			if (i / 100 == i / 100.0) Thread.sleep(0, 1);
		}
	}
}
