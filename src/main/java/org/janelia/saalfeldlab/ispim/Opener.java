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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.NativeType;

/**
 * Methods to open slices from Tiff files.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public interface Opener {

	/**
	 * Open a slice from an image file using a LOCI bioformats
	 * {@link IFormatReader}.
	 *
	 * Currently supports UINT8, INT8, UINT16, INT16, FLOAT because those are
	 * what we are currently facing.
	 *
	 * @param <T>
	 * @param reader
	 * @param slice
	 * @param width
	 * @param height
	 * @return
	 * @throws IOException
	 * @throws FormatException
	 */
	public static <T extends NativeType<T>> RandomAccessibleInterval<T> openSlice(
			final IFormatReader reader,
			final int slice,
			final int width,
			final int height) throws IOException, FormatException {

		final byte[] bytes = new byte[width * height * (int)Math.ceil(reader.getBitsPerPixel() / 8.0)];
		reader.openBytes(slice, bytes, 0, 0, width, height);
		switch (reader.getPixelType()) {
		case FormatTools.UINT8:
			return (RandomAccessibleInterval<T>)ArrayImgs.unsignedBytes(bytes, width, height);
		case FormatTools.INT8:
			return (RandomAccessibleInterval<T>)ArrayImgs.bytes(bytes, width, height);
		}

		final ByteBuffer buffer = ByteBuffer.wrap(bytes);
		buffer.order(reader.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		switch (reader.getPixelType()) {
		case FormatTools.UINT16: {
			final short[] shorts = new short[width * height];
			buffer.asShortBuffer().get(shorts);
			return (RandomAccessibleInterval<T>)ArrayImgs.unsignedShorts(shorts, width, height);
		}
		case FormatTools.INT16: {
			final short[] shorts = new short[width * height];
			buffer.asShortBuffer().get(shorts);
			return (RandomAccessibleInterval<T>)ArrayImgs.shorts(shorts, width, height);
		}
		case FormatTools.FLOAT: {
			final float[] floats = new float[width * height];
			buffer.asFloatBuffer().get(floats);
			return (RandomAccessibleInterval<T>)ArrayImgs.floats(floats, width, height);
		}
		}
		return null;
	}

	/**
	 * Open a slice using a known {@link IFormatReader} with known width and
	 * height from an unknown path.
	 *
	 * @param <T>
	 * @param reader
	 * @param path
	 * @param slice
	 * @param width
	 * @param height
	 * @return
	 * @throws IOException
	 * @throws FormatException
	 */
	public static <T extends NativeType<T>> RandomAccessibleInterval<T> openSlice(
			final IFormatReader reader,
			final String path,
			final int slice,
			final int width,
			final int height) throws IOException, FormatException {

		reader.setId(path);
		//System.out.println("Setting path " + path );
		return openSlice(reader, slice, width, height);
	}
}
