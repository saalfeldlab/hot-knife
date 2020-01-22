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

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.hotknife.util.Transform;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 * Display the target surfaces of a {@link FlattenTransform} as a heat map.
 * The {@link FlattenTransform} is virtually generated with a Gaussian
 * bump on both sides of the block and not cached to keep the example simple.
 * This is why it's not crazy fast.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class FlattenTransformBehavior implements Callable<Void> {

	@Option(names = {"--minY"}, required = false, description = "min value, e.g. --minY 50")
	private double minY = 50;

	@Option(names = {"--maxY"}, required = false, description = "max value, e.g. --maxY 150")
	private double maxY = 150;

	@Option(names = {"--width"}, required = false, description = "width, e.g. --width 300")
	private long width = 300;

	@Option(names = {"--height"}, required = false, description = "height, e.g. --height 200")
	private long height = 200;

	@Option(names = {"--bumpACenter"}, required = false, description = "bumpA center, e.g. --bumpACenter 150,100")
	private double[] bumpACenter = new double[] {150, 100};

	@Option(names = {"--bumpASigma"}, required = false, description = "bumpA sigma, e.g. --bumpASigma 100")
	private double bumpASigma = 100;

	@Option(names = {"--bumpAHeight"}, required = false, description = "bumpA height, e.g. --bumpAHeight 50")
	private double bumpAHeight = 20;

	@Option(names = {"--bumpBCenter"}, required = false, description = "bumpB center, e.g. --bumpACenter 150,100")
	private double[] bumpBCenter = new double[] {100, 150};

	@Option(names = {"--bumpBSigma"}, required = false, description = "bumpB sigma, e.g. --bumpASigma 100")
	private double bumpBSigma = 50;

	@Option(names = {"--bumpBHeight"}, required = false, description = "bumpB height, e.g. --bumpAHeight 50")
	private double bumpBHeight = -10;

	@Option(names = {"--padding"}, required = false, description = "padding above and below min and max, e.g. --padding 200")
	private long padding = 200;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new FlattenTransformBehavior(), args);
	}

	public static final FunctionRandomAccessible<DoubleType> gaussField2(
			final double meanX,
			final double meanY,
			final double offset,
			final double sigma,
			final double scale) {

		final double invVarTwice = -1.0 / sigma / sigma * 2;

		return new FunctionRandomAccessible<>(
				2,
				(location, value) -> {
					final double x = location.getDoublePosition(0) - meanX;
					final double y = location.getDoublePosition(1) - meanY;
					value.set(offset + scale * Math.exp((x * x + y * y) * invVarTwice));
				},
				DoubleType::new);
	}

	public static final FunctionRandomAccessible<DoubleType> zPlane(
			final double offset,
			final double scale,
			final double stretch) {

		return new FunctionRandomAccessible<>(
				3,
				(location, value) -> {
					final double z = location.getDoublePosition(2);
					value.set(scale / (stretch * Math.abs(z - offset) + 1));
				},
				DoubleType::new);
	}

	public static final FunctionRandomAccessible<DoubleType> zRange(
			final double min,
			final double max,
			final double scale,
			final double stretch) {

		return new FunctionRandomAccessible<>(
				3,
				(location, value) -> {
					final double z = location.getDoublePosition(2);
					value.set(
							scale / (stretch * Math.abs(z - min) + 1) +
							scale / (stretch * Math.abs(z - max) + 1));
				},
				DoubleType::new);
	}


	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {


		/* transformation */
		final IntervalView<DoubleType> topFace = Views.offsetInterval(
				gaussField2(bumpACenter[0], bumpACenter[0], minY, bumpASigma, bumpAHeight),
				new long[] {0, 0},
				new long[] {width, height});
		final IntervalView<DoubleType> bottomFace = Views.offsetInterval(
				gaussField2(bumpBCenter[0], bumpBCenter[1], maxY, bumpBSigma, bumpBHeight),
				new long[] {0, 0},
				new long[] {width, height});

		final FlattenTransform ft = new FlattenTransform(
				topFace,
				bottomFace,
				minY,
				maxY);

		/* range visualization */
		final IntervalView<DoubleType> zRange = Views.interval(
				zRange(minY, maxY, 255, 1),
				new long[] {0, 0, Math.round(minY) - padding},
				new long[] {width, height, Math.round(maxY) + padding});

		final Interval cropInterval = zRange;

		final RandomAccessibleInterval<DoubleType> transformedSource =
				Transform.createTransformedInterval(
						zRange,
						cropInterval,
						ft,
						new DoubleType(0));


		/* show it */
		BdvStackSource<?> bdv = null;

		bdv = BdvFunctions.show(Views.permute(zRange, 1, 2), "", BdvOptions.options());
		bdv.setDisplayRangeBounds(0, 255);
		bdv.setDisplayRange(0, 255);
		bdv.setColor(new ARGBType(0xffff00ff));
		bdv = BdvFunctions.show(Views.permute(transformedSource, 1, 2), "", BdvOptions.options().addTo(bdv));
		bdv.setDisplayRangeBounds(0, 255);
		bdv.setDisplayRange(0, 255);
		bdv.setColor(new ARGBType(0xff00ff00));

		return null;
	}
}
