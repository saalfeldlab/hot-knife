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
import ij.ImagePlus;
import mpicbg.ij.clahe.Flat;
import mpicbg.ij.plugin.NormalizeLocalContrast;
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
public class CLAHEBehavior implements Callable<Void> {

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new CLAHEBehavior(), args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

		final int scaleIndex = 4;
		final double scale = 1.0 / Math.pow(2, scaleIndex);
		final N5Reader n5 = new N5FSReader("/nrs/flyem/tmp/VNC-export.n5");
		final RandomAccessibleInterval<UnsignedByteType> img = N5Utils.openVolatile(n5, "/2-26/s" + scaleIndex);

		final int blockRadius = (int)Math.round(511 * scale);

		final ImageJStackOp<UnsignedByteType> clahe =
				new ImageJStackOp<>(
						Views.extendZero(img),
						(fp) -> Flat.getFastInstance().run(new ImagePlus("", fp), blockRadius, 256, 2.5f, null, false),
						blockRadius,
						0,
						255,
						true );
		final RandomAccessibleInterval<UnsignedByteType> clahed = Lazy.process(
				img,
				new int[] {256, 256, 32},
				new UnsignedByteType(),
				AccessFlags.setOf(AccessFlags.VOLATILE),
				clahe);

		final ImageJStackOp<UnsignedByteType> lcn =
				new ImageJStackOp<>(
						Views.extendZero(img),
						(fp) -> NormalizeLocalContrast.run(fp, blockRadius, blockRadius, 3f, true, true),
						blockRadius,
						0,
						255,
						true );
		final RandomAccessibleInterval<UnsignedByteType> lcned = Lazy.process(
				img,
				new int[] {512, 512, 32},
				new UnsignedByteType(),
				AccessFlags.setOf(AccessFlags.VOLATILE),
				lcn);

		final ImageJStackOp<UnsignedByteType> cllcn =
				new ImageJStackOp<>(
						Views.extendZero(img),
//						(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 10, 0.5f, true, true, true),
						(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 10, 0.5f, true, true, true),
						blockRadius,
						0,
						255,
						true );
		final RandomAccessibleInterval<UnsignedByteType> cllcned = Lazy.process(
				img,
				new int[] {512, 512, 32},
				new UnsignedByteType(),
				AccessFlags.setOf(AccessFlags.VOLATILE),
				cllcn);


//		final FinalInterval cropInterval = Intervals.createMinSize(
//				Math.round(22624 * scale), Math.round(25849* scale), Math.round(45531 * scale),
//				8048, 8048 , 8048);

		/* show it */
		BdvStackSource<?> bdv = null;

		bdv = BdvFunctions.show(
				VolatileViews.wrapAsVolatile(
						img,
						queue),
				"original",
				BdvOptions.options());

		bdv = BdvFunctions.show(
				VolatileViews.wrapAsVolatile(
						clahed,
						queue),
				"CLAHE",
				BdvOptions.options().addTo(bdv));

		bdv = BdvFunctions.show(
				VolatileViews.wrapAsVolatile(
						lcned,
						queue),
				"LCN",
				BdvOptions.options().addTo(bdv));

		bdv = BdvFunctions.show(
				VolatileViews.wrapAsVolatile(
						cllcned,
						queue),
				"CLLCN",
				BdvOptions.options().addTo(bdv));

//		new ImageJ();

//		ImageJFunctions.show(img);

		return null;
	}
}
