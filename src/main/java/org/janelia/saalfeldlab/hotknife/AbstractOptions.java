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

import java.io.Serializable;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@SuppressWarnings("serial")
public class AbstractOptions implements Serializable {

	protected boolean parsedSuccessfully = false;

	protected static final boolean parseCSLongArray(final String csv, final long[] array) {

		final String[] stringValues = csv.split(",");
		if (stringValues.length != array.length)
			return false;
		try {
			for (int i = 0; i < array.length; ++i)
				array[i] = Long.parseLong(stringValues[i]);
		} catch (final NumberFormatException e) {
			e.printStackTrace(System.err);
			return false;
		}
		return true;
	}

	protected static final boolean parseCSIntArray(final String csv, final int[] array) {

		final String[] stringValues = csv.split(",");
		if (stringValues.length != array.length)
			return false;
		try {
			for (int i = 0; i < array.length; ++i)
				array[i] = Integer.parseInt(stringValues[i]);
		} catch (final NumberFormatException e) {
			e.printStackTrace(System.err);
			return false;
		}
		return true;
	}

	protected static final boolean parseCSDoubleArray(final String csv, final double[] array) {

		final String[] stringValues = csv.split(",");
		if (stringValues.length != array.length)
			return false;
		try {
			for (int i = 0; i < array.length; ++i)
				array[i] = Double.parseDouble(stringValues[i]);
		} catch (final NumberFormatException e) {
			e.printStackTrace(System.err);
			return false;
		}
		return true;
	}

	protected static final long[] parseCSLongArray(final String csv) {

		final String[] stringValues = csv.split(",");
		final long[] array = new long[stringValues.length];
		try {
			for (int i = 0; i < array.length; ++i)
				array[i] = Long.parseLong(stringValues[i]);
		} catch (final NumberFormatException e) {
			e.printStackTrace(System.err);
			return null;
		}
		return array;
	}

	protected static final int[] parseCSIntArray(final String csv) {

		final String[] stringValues = csv.split(",");
		final int[] array = new int[stringValues.length];
		try {
			for (int i = 0; i < array.length; ++i)
				array[i] = Integer.parseInt(stringValues[i]);
		} catch (final NumberFormatException e) {
			e.printStackTrace(System.err);
			return null;
		}
		return array;
	}

	protected static final double[] parseCSDoubleArray(final String csv) {

		final String[] stringValues = csv.split(",");
		final double[] array = new double[stringValues.length];
		try {
			for (int i = 0; i < array.length; ++i)
				array[i] = Double.parseDouble(stringValues[i]);
		} catch (final NumberFormatException e) {
			e.printStackTrace(System.err);
			return null;
		}
		return array;
	}

	public boolean isParsedSuccessfully() {

		return parsedSuccessfully;
	}
}
