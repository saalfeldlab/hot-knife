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
import java.util.function.Consumer;

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
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
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

		final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() * 2));

		/*
		final int scaleIndex = 4;
		final double scale = 1.0 / Math.pow(2, scaleIndex);
		final N5Reader n5 = new N5FSReader("/nrs/flyem/tmp/VNC-export.n5");
		final RandomAccessibleInterval<UnsignedByteType> img = N5Utils.openVolatile(n5, "/2-26/s" + scaleIndex);
		*/

		final int scaleIndex = 4;
		final double scale = 1.0 / Math.pow(2, scaleIndex);
		//final N5Reader n5 = new N5FSReader("/nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5");
		//RandomAccessibleInterval<UnsignedByteType> img = N5Utils.openVolatile(n5, "/render/slab_070_to_079/s070_m104_align_no35_horiz_avgshd_ic___20240504_085307/s" + scaleIndex);
		final N5Reader n5 = new N5FSReader("/Volumes/cellmap/data/jrc_mus-pancreas-5/jrc_mus-pancreas-5.n5");
		RandomAccessibleInterval<UnsignedByteType> img = N5Utils.openVolatile(n5, "/render/jrc_mus_pancreas_5/v3_acquire_align_destreak_ic2d___20240613_065406/s" + scaleIndex);
		/*final long[] min = img.minAsLongArray();
		final long[] max = img.maxAsLongArray();
		final long[] midpoint = new long[] {(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, min[2]};
		img = Views.zeroMin( Views.interval(img, midpoint, new long[] {midpoint[0] + 1024, midpoint[1] + 1024, max[2]}) ); // has to sit on 0,0,0,... because of the known issue with Lazy (is fixed meanwhile)
		*/

		final boolean invert = false;//true; // for multisem

		if ( invert )
		{
			final RandomAccessibleInterval<UnsignedByteType> refimg = img;
	
			img = Lazy.process(
					img,
					new int[] {256, 256, 32},
					new UnsignedByteType(),
					AccessFlags.setOf(AccessFlags.VOLATILE),
					new Consumer<RandomAccessibleInterval<UnsignedByteType>>() {
						@Override
						public void accept(final RandomAccessibleInterval<UnsignedByteType> t)
						{
							final Cursor<UnsignedByteType> c1 = Views.flatIterable( t ).cursor();
							final Cursor<UnsignedByteType> c2 = Views.flatIterable( Views.interval( refimg, t ) ).cursor();
	
							while ( c1.hasNext() )
							{
								c1.next().set( 255 - c2.next().get() );
							}
						}
					});
		}

		final int blockRadius = (int)Math.round(1023 * scale);

		final ImageJStackOp<UnsignedByteType> clahe =
				new ImageJStackOp<>(
						Views.extendMirrorSingle(img),
						(fp) -> Flat.getFastInstance().run(new ImagePlus("", fp), blockRadius, 256, 2f, null, false),
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
						Views.extendMirrorSingle(img),
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
						Views.extendMirrorSingle(img),
						(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 5, 0.95f, true, true, true),
//						(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 25, 0.5f, true, true, true),
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
