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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.n5.N5FSReader;

import com.google.common.reflect.TypeToken;
import com.google.gson.GsonBuilder;

import loci.formats.FormatException;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import net.imglib2.realtransform.AffineTransform2D;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Align all channels again, initialized with the global average shear in x and y.
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command(
		name = "CompareAlignments",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Compare alignments with their average shear in x and y")
public class CompareAlignments implements Callable<Void>, Serializable {

	private static final long serialVersionUID = 6708886268386777152L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--excludeIds", split=",", required = false, description = "ids to be exluded")
	private HashSet<String> excludeIds = new HashSet<>();

	@Option(names = "--excludeChannels", split=",", required = false, description = "channels to be exluded")
	private HashSet<String> excludeChannels = new HashSet<>();

	@Option(names = "--outPath", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6")
	private String outPath = null;

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException {

		System.out.println(Arrays.toString(excludeIds.toArray()));

		final N5FSReader n5 = new N5FSReader(
				n5Path,
				new GsonBuilder().registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final HashMap<String, HashMap<String, double[]>> camTransforms = n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, AffineTransform2D>>>() {}.getType());
		final ArrayList<String> ids = n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());

		for (final String channel : camTransforms.keySet()) {

			if (excludeChannels.contains(channel))
				continue;

			System.out.println(channel);

			double sumShearX = 0;
			double sumShearY = 0;
			int count = 0;
			int length = 0;
			for (final String id : ids) {

				if (excludeIds.contains(id))
					continue;

				final ArrayList<AffineTransform2D> transforms = n5.getAttribute(n5.groupPath(id, channel), "transforms", new TypeToken<ArrayList<AffineTransform2D>>() {}.getType());
				final HashSet<AffineTransform2D> consider = new HashSet<>(transforms);

				length = Math.max(length, transforms.size());

				final double[] shearX = AlignChannel.fit(transforms, consider, 0, 2);
				final double[] shearY = AlignChannel.fit(transforms, consider, 1, 2);

				sumShearX += shearX[0];
				sumShearY += shearY[0];
				++count;
			}

			final double shearX = sumShearX / count;
			final double shearY = sumShearY / count;

			final double[] sumDiffX = new double[length];
			final double[] sumDiffY = new double[length];
			final int[] counts = new int[length];
			for (final String id : ids) {

				if (excludeIds.contains(id))
					continue;

				System.out.println(id);

				final ArrayList<AffineTransform2D> transforms = n5.getAttribute(n5.groupPath(id, channel), "transforms", new TypeToken<ArrayList<AffineTransform2D>>() {}.getType());

				try (PrintStream out = new PrintStream(new FileOutputStream(outPath + "/" + channel + "." + id + ".dat"))) {
					for (int i = 0; i < transforms.size(); ++i) {

						final AffineTransform2D transform = transforms.get(i);
						final double diffX = transform.get(0, 2) - i * shearX;
						final double diffY = transform.get(1, 2) - i * shearY;

						sumDiffX[i] += diffX;
						sumDiffY[i] += diffY;
						++counts[i];

						out.println(diffX + " " + diffY);
					}
				}
			}

			try (PrintStream out = new PrintStream(new FileOutputStream(outPath + "/" + channel + ".dat"))) {

				for (int i = 0; i < length; ++i) {

					out.println(sumDiffX[i] / counts[i] + " " + sumDiffY[i] / counts[i]);
				}
			}
		}

		System.out.println("Done.");

		return null;
	}

	public static final void main(final String... args) {

		System.out.println(Arrays.toString(args));

		System.exit(new CommandLine(new CompareAlignments()).execute(args));
	}
}
