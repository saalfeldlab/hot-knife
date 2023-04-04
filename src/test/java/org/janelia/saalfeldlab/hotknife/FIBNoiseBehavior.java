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

import org.janelia.saalfeldlab.hotknife.ops.CLLCN;
import org.janelia.saalfeldlab.hotknife.ops.ImageJStackOp;
import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command
public class FIBNoiseBehavior implements Callable<Void> {

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new FIBNoiseBehavior(), args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

		final int scaleIndex = 1;
		final double scale = 1.0 / Math.pow(2, scaleIndex);

		final N5Reader n51 = new N5FSReader("/groups/futusa/futusa/projects/fib4nm/FB-Z0519-11-4x4x4nm.n5");
		final N5Reader n52 = new N5FSReader("/nrs/cosem/cosem/training/other/denoise/v0001/denoise-setup02/fib4nm/FB-Z0519-11-4x4x4nm_s1_it500000.n5");

		final RandomAccessibleInterval<UnsignedByteType> img1 = N5Utils.openVolatile(n51, "/volumes/raw/s" + scaleIndex);
		final RandomAccessibleInterval<UnsignedByteType> img2 = N5Utils.openVolatile(n52, "/raw_predicted");

		final int blockRadius = (int)Math.round(1023 * scale);

		final ImageJStackOp<UnsignedByteType> cllcn2 =
				new ImageJStackOp<>(
						Views.extendZero(img2),
						(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 4f, 10, 0.5f, true, true, true),
						blockRadius,
						0,
						255,
						true );
		final RandomAccessibleInterval<UnsignedByteType> cllcned2 = Lazy.process(
				img2,
				new int[] {256, 256, 32},
				new UnsignedByteType(),
				AccessFlags.setOf(AccessFlags.VOLATILE),
				cllcn2);

		/* show it */
		BdvStackSource<?> bdv = null;

		bdv = BdvFunctions.show(
				VolatileViews.wrapAsVolatile(
						img1,
						queue),
				"original",
				BdvOptions.options());

		bdv = BdvFunctions.show(
				VolatileViews.wrapAsVolatile(
						cllcned2,
						queue),
				"denoised",
				BdvOptions.options().addTo(bdv));

//		new ImageJ();

//		ImageJFunctions.show(img);

		return null;
	}
}
