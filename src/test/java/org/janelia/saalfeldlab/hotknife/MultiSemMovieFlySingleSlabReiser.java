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

import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.janelia.saalfeldlab.hotknife.VNCMovie.Normalization;

import bdv.cache.CacheControl;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.ViewerPanel;
import bdv.viewer.ViewerState;
import bdv.viewer.animate.SimilarityTransformAnimator;
import bdv.viewer.overlay.MultiBoxOverlayRenderer;
import bdv.viewer.overlay.ScaleBarOverlayRenderer;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.render.PainterThread;
import ij.process.ColorProcessor;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform3D;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command
public class MultiSemMovieFlySingleSlabReiser implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/home/preibischs@hhmi.org/Documents/msemreiser";
	private final String n5Path = /*"/Users/preibischs/Downloads/msemstack*/"/nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.n5/render/w60_serial_360_to_369/w60_s360_r00_d20_gc_align_b_ic";
	private final String n5Group = "/";//"/wafer-52-align/run_20230329_104500/pass12";

	private final AffineTransform3D viewerScale = new AffineTransform3D();
	private final AffineTransform3D viewerTranslation = new AffineTransform3D();
	{
		viewerScale.set(
				1.0, 0, 0, 0,
				0, 1.0, 0, 0,
				0, 0, 1.0, 0);
		viewerTranslation.set(
				1, 0, 0, 0.5 * screenWidth,
				0, 1, 0, 0.5 * screenHeight,
				0, 0, 1, 0);
	}

	private void recordMovie(
			final ViewerPanel viewer,
			final int width,
			final int height,
			final AffineTransform3D[] transforms,
			final int[] frames,
			final int[] accel,
			final int firstTransformIndex,
			final String dir) throws IOException {

		final ViewerState renderState = viewer.state();
		final ScaleBarOverlayRenderer scalebar = new ScaleBarOverlayRenderer();
		final MultiBoxOverlayRenderer box = new MultiBoxOverlayRenderer(width, height);

		final VNCMovie.Target target = new VNCMovie.Target(width, height);

		final MultiResolutionRenderer renderer = new MultiResolutionRenderer(
				target,
				new PainterThread(null),
				new double[]{1.0},
				0l,
				12,
				null,
				false,
				viewer.getOptionValues().getAccumulateProjectorFactory(),
				new CacheControl.Dummy());

		/* count i up to firstFrame */
		int i = 0;
		for (int k = 0; k < firstTransformIndex; ++k)
			i += frames[k];

		for (int k = firstTransformIndex; k < transforms.length; ++k) {
			final SimilarityTransformAnimator animator = new SimilarityTransformAnimator(
					transforms[k - 1],
					transforms[k],
					width / 2,
					height / 2,
					0);

			for (int d = 0; d < frames[k]; ++d) {
				final AffineTransform3D tkd = animator.get(VNCMovie.accel((double)d / (double)frames[k], accel[k]));
				tkd.preConcatenate(viewerTranslation.inverse());
				tkd.preConcatenate(viewerScale);
				tkd.preConcatenate(viewerTranslation);
				viewer.state().setViewerTransform(tkd);
				renderState.setViewerTransform(tkd);
				renderer.requestRepaint();
				try {
					renderer.paint(renderState);
				} catch (final Exception e) {
					e.printStackTrace();
					return;
				}

				/* clahe */
				final BufferedImage bi = target.renderResult.getBufferedImage();
				final ColorProcessor ip = new ColorProcessor(bi);
//				final ImagePlus imp = new ImagePlus("", ip);
//				Flat.getFastInstance().run(imp, 128, 256, 1.5f, null, false);

				final Graphics2D g2 = bi.createGraphics();
				g2.drawImage(ip.createImage(), 0, 0, null);

				/* scalebar */
				g2.setClip(0, 0, width, height);
				scalebar.setViewerState(renderState);
				scalebar.paint(g2);
				box.setViewerState(renderState);
				box.paint(g2);

				/* save image */
				ImageIO.write(bi, "png", new File(String.format("%s/img-%04d.png", dir, i++)));

				System.out.println(String.format("%s/img-%04d.png", dir, i));
			}
		}
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		new CommandLine(new MultiSemMovieFlySingleSlab()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, Normalization.CLLCN, false, true );

		//BdvFunctions.show((Source)mipmapSource.asVolatile(new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))));

	//	Thread.sleep(10000);

		final BdvStackSource<?> bdv = BdvFunctions.show(mipmapSource, BdvOptions.options().numRenderingThreads((Runtime.getRuntime().availableProcessors() - 1)));

		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);
		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);

		Thread.sleep(1000);

		//SimpleMultiThreading.threadHaltUnClean();

		/* animate */
		int i = 0;
		final AffineTransform3D[] transforms = new AffineTransform3D[8];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		/*
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.014489339342299963,0.0,0.0,-1486.2297494649208,0.0,0.014489339342299963,0.0,-2102.1785180872384,0.0,0.0,0.014489339342299963,-0.5505948950073986);
		frames[i] = 0;
		accel[i++] = 0;

		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.014489339342299963,0.0,0.0,-1486.2297494649208,0.0,0.014489339342299963,0.0,-2102.1785180872384,0.0,0.0,0.014489339342299963,-0.5505948950073986);
		frames[i] = 0;
		accel[i++] = 0;
		*/

		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.0020933038087551353,-0.004489104504604405,4.648068172030973E-19,170.33247628452432,0.004489104504604405,0.0020933038087551353,9.967814361920635E-19,-557.5006943159339,-1.099826626726043E-18,3.389102454473741E-51,0.004953178786295557,-0.18822079387911833);
		frames[i] = 0;
		accel[i++] = 0;

		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.0020933038087551353,-0.004489104504604405,4.648068172030973E-19,170.33247628452432,0.004489104504604405,0.0020933038087551353,9.967814361920635E-19,-557.5006943159339,-1.099826626726043E-18,3.389102454473741E-51,0.004953178786295557,-0.18822079387911833);
		frames[i] = 0;
		accel[i++] = 0;

		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.014489339342299963,0.0,0.0,-1486.2297494649208,0.0,0.014489339342299963,0.0,-2102.1785180872384,0.0,0.0,0.014489339342299963,-0.5505948950073986);
		frames[i] = 240;
		accel[i++] = 0;

		// zoom in
		transforms[i] = new AffineTransform3D();
		transforms[i].set(2.100666399634009,0.0,0.0,-209141.31995319773,0.0,2.100666399634009,0.0,-325171.6972765285,0.0,0.0,2.100666399634009,-79.82532318609236);
		frames[i] = 240;
		accel[i++] = 0;

		// zoom out
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.03844453082598323,0.0,0.0,-4025.8534601081838,0.0,0.03844453082598323,0.0,-5615.597236398843,0.0,0.0,0.03844453082598323,-1.4608921713873628);
		frames[i] = 240;
		accel[i++] = 0;

		// zoom in 
		transforms[i] = new AffineTransform3D();
		transforms[i].set(1.4929043910515476,0.0,0.0,-167490.94727876032,0.0,1.4929043910515476,0.0,-213524.15223648536,0.0,0.0,1.4929043910515476,-56.73036685995885);
		frames[i] = 240;
		accel[i++] = 0;

		// back
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.014489339342299963,0.0,0.0,-1486.2297494649208,0.0,0.014489339342299963,0.0,-2102.1785180872384,0.0,0.0,0.014489339342299963,-0.5505948950073986);
		frames[i] = 240;
		accel[i++] = 0;

		// over to the left
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.014489339342299963,0.0,0.0,-1486.2297494649208,0.0,0.014489339342299963,0.0,-2102.1785180872384,0.0,0.0,0.014489339342299963,-0.5505948950073986);
		frames[i] = 0;
		accel[i++] = 0;

		// play at 60 FPS
		for ( i = 0; i < frames.length; ++i )
			frames[ i ] *= 2.0;

		recordMovie(
				bdv.getBdvHandle().getViewerPanel(),
				screenWidth,
				screenHeight,
				transforms,
				frames,
				accel,
				1,
				outDir);

		return null;
	}
}
