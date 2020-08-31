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
package net.imglib2.realtransform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

import net.imglib2.RealLocalizable;
import net.imglib2.RealPositionable;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class RealTransformStack<T extends RealTransform> implements RealTransform {

	protected final int numSourceDimensions;
	protected final int numTargetDimensions;
	protected final ArrayList<T> transforms;

	protected static <T extends RealTransform> boolean transformsHaveSameDimensionality(final Iterable<T> transforms) {

		final Iterator<T> iter = transforms.iterator();
		final T first = iter.next();
		final int numSourceDimensions = first.numSourceDimensions();
		final int numTargetDimensions = first.numTargetDimensions();

		while (iter.hasNext()) {
			final T transform = iter.next();
			if (transform.numSourceDimensions() != numSourceDimensions) return false;
			if (transform.numTargetDimensions() != numTargetDimensions) return false;
		}

		return true;
	}

	public RealTransformStack(final ArrayList<T> transforms) {

		assert transformsHaveSameDimensionality(transforms) : "Dimensionality of transforms does not match.";

		this.transforms = transforms;
		numSourceDimensions = transforms.get(0).numSourceDimensions() + 1;
		numTargetDimensions = transforms.get(0).numTargetDimensions() + 1;
	}

	public RealTransformStack(final Collection<T> transforms) {

		this(new ArrayList<>(transforms));
	}

	@SafeVarargs
	public RealTransformStack(final T... transforms) {

		this(Arrays.asList(transforms));
	}

	@Override
	public int numSourceDimensions() {

		return numSourceDimensions + 1;
	}

	@Override
	public int numTargetDimensions() {

		return numTargetDimensions + 1;
	}

	public T getTransform(final double i) {

		return transforms.get((int)Math.round(i));
	}

	@Override
	public void apply(final double[] source, final double[] target) {

		final double indexPosition = source[numSourceDimensions];
		getTransform(indexPosition).apply(source, target);
		target[numTargetDimensions] = indexPosition;
	}

	@Override
	public void apply(final RealLocalizable source, final RealPositionable target) {

		final double indexPosition = source.getDoublePosition(numSourceDimensions);
		getTransform(indexPosition).apply(source, target);
		target.setPosition(indexPosition, numTargetDimensions);

	}

	@SuppressWarnings("unchecked")
	@Override
	public RealTransformStack<T> copy() {

		final ArrayList<T> copy = new ArrayList<>();
		for (final T t : transforms)
			copy.add((T)t.copy());

		return new RealTransformStack<>(copy);
	}
}
