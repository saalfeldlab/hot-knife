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
package org.janelia.saalfeldlab.hotknife.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.real.FloatType;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Show {

	private Show() {}

	/**
	 * Quickly visualize the slab-face series as transformed by a corresponding
	 * list of target to source transforms.
	 * @throws IOException
	 */
	public static Bdv transformedTopBotStack(
			final String n5Path,
			final List<String> datasetNames,
			final int scaleIndex,
			final List<? extends RealTransform> slabTransforms,
			final Interval targetInterval,
			final Bdv bdv) throws IOException {

		final ArrayList<RealTransform> transforms = new ArrayList<>();
		slabTransforms.forEach(t -> {
			transforms.add(t);
			transforms.add(t);
		});

		return transformedStack(n5Path, datasetNames, scaleIndex, transforms, targetInterval, bdv);
	}

	/**
	 * Quickly visualize the slab-face series as transformed by a corresponding
	 * list of target to source transforms.
	 * @throws IOException
	 */
	public static <T extends NumericType<T>> BdvStackSource<T> transformedStack(
			final RandomAccessibleInterval<T> stack,
			final Bdv bdv) throws IOException {

		final BdvOptions options = bdv == null ? Bdv.options() : Bdv.options().addTo(bdv);
		options.numRenderingThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
		final BdvStackSource<T> stackSource = BdvFunctions.show(stack, "transformed", options);
		stackSource.setDisplayRange(0, 255);
		return stackSource;
	}

	/**
	 * Quickly visualize the slab-face series as transformed by a corresponding
	 * list of target to source transforms.
	 * @throws IOException
	 */
	public static Bdv transformedStack(
			final String n5Path,
			final List<String> datasetNames,
			final int scaleIndex,
			final List<? extends RealTransform> transforms,
			final Interval targetInterval,
			final Bdv bdv) throws IOException {

		final RandomAccessibleInterval<FloatType> stack = Transform.createTransformedStack(n5Path, datasetNames, scaleIndex, transforms, targetInterval);
		return transformedStack(stack, bdv);
	}

	/**
	 * Quickly visualize a source as transformed by a target to source transform.
	 * @throws IOException
	 */
	public static Bdv transformed(
			final String n5Path,
			final String datasetName,
			final int scaleIndex,
			final RealTransform transforms,
			final Interval targetInterval,
			final Bdv bdv) throws IOException {

		final N5Reader n5Reader = N5.openFSReader(n5Path);
		final RandomAccessibleInterval<FloatType> source = N5Utils.open(n5Reader, datasetName + "/s" + scaleIndex);
		final RandomAccessibleInterval<FloatType> transformedInterval = Transform.createTransformedInterval(
				source,
				targetInterval,
				Transform.createScaledRealTransform(transforms, scaleIndex),
				new FloatType(255));

		final BdvOptions options = bdv == null ? Bdv.options() : Bdv.options().addTo(bdv);
		final BdvStackSource<?> stackSource = BdvFunctions.show(transformedInterval, "transformed", options);
		stackSource.setDisplayRange(0, 255);
		return stackSource;
	}
}
