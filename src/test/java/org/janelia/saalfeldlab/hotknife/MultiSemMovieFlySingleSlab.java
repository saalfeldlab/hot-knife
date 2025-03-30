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
public class MultiSemMovieFlySingleSlab implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/Users/preibischs/Downloads/msemstack/";
	private final String n5Path = "/Users/preibischs/Downloads/msemstack/nrs/hess/data/hess_wafers_60_61/export/hess_wafers_60_61.n5/render/w60_serial_360_to_369/w60_s360_r00_d20_gc_align_b_ic";
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

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, true, false, true );

		//BdvFunctions.show((Source)mipmapSource.asVolatile(new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))));
		//SimpleMultiThreading.threadHaltUnClean();

//		Thread.sleep(10000);

		final BdvStackSource<?> bdv = BdvFunctions.show(mipmapSource, BdvOptions.options().numRenderingThreads((Runtime.getRuntime().availableProcessors() - 1)));

		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);
		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);

		Thread.sleep(1000);

		/* animate */
		int i = 0;
		final AffineTransform3D[] transforms = new AffineTransform3D[11];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.0013443307145500156,-0.003693518282505465,-8.201258078223062E-19,200.6964082544024,0.003693518282505465,0.0013443307145500156,2.9850138240084326E-19,-407.5561055756799,0.0,-8.7275965531835E-19,0.003930560058475724,-0.0804207775305562);
		frames[i] = 0;
		accel[i++] = 0;

		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.0013443307145500156,-0.003693518282505465,-8.201258078223062E-19,200.6964082544024,0.003693518282505465,0.0013443307145500156,2.9850138240084326E-19,-407.5561055756799,0.0,-8.7275965531835E-19,0.003930560058475724,-0.0804207775305562);
		frames[i] = 0;
		accel[i++] = 0;

		// zoom in
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.36826273907146584,0.21261659153541945,4.7210367067988987E-17,-53874.90319315746,-0.21261659153541945,0.36826273907146584,8.177075440573358E-17,-31092.054706213607,2.3586316433948987E-32,-9.44207341359781E-17,0.42523318307083935,-8.700435232024452);
		frames[i] = 200;
		accel[i++] = 0;

		// up
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.36826273907146584,0.21261659153541945,4.7210367067988987E-17,-53874.90319315746,-0.21261659153541945,0.36826273907146584,8.177075440573358E-17,-31092.054706213607,2.3586316433948987E-32,-9.44207341359781E-17,0.42523318307083935,-4.352320244631336E-4);
		frames[i] = 30;
		accel[i++] = 0;

		// down
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.36826273907146584,0.21261659153541945,4.7210367067988987E-17,-53874.90319315746,-0.21261659153541945,0.36826273907146584,8.177075440573358E-17,-31092.054706213607,2.3586316433948987E-32,-9.44207341359781E-17,0.42523318307083935,-17.500435232024447);
		frames[i] = 60;
		accel[i++] = 0;

		// back
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.36826273907146584,0.21261659153541945,4.7210367067988987E-17,-53874.90319315746,-0.21261659153541945,0.36826273907146584,8.177075440573358E-17,-31092.054706213607,2.3586316433948987E-32,-9.44207341359781E-17,0.42523318307083935,-8.700435232024452);
		frames[i] = 30;
		accel[i++] = 0;

		// over to the left
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.05767192057098594,0.033296898866341414,7.393396754005502E-18,-4527.045515780181,-0.033296898866341414,0.05767192057098594,1.2805738818452373E-17,-5564.424038906326,2.8729117572700328E-33,-1.4786793508011026E-17,0.06659379773268284,-2.662534832873602);
		frames[i] = 120;
		accel[i++] = 0;

		// to the middle
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.04303567508367431,0.024846658594316617,5.517066491282167E-18,-5611.114407825471,-0.024846658594316617,0.04303567508367431,9.555839471636489E-18,-3101.5491974526017,2.1438109864532852E-33,-1.1034132982564346E-17,0.049693317188633235,-1.9868244863716087);
		frames[i] = 120;
		accel[i++] = 0;

		// end of VNC
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.030584650811513375,0.01765805637909794,3.920876152440731E-18,-3611.7961160476025,-0.01765805637909794,0.030584650811513375,6.791156706212535E-18,1422.5906242999808,1.523566443400188E-33,-7.841752304881474E-18,0.03531611275819588,-1.41199906870967);
		frames[i] = 120;
		accel[i++] = 0;

		// back to origin
		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.0013443307145500156,-0.003693518282505465,-8.201258078223062E-19,200.6964082544024,0.003693518282505465,0.0013443307145500156,2.9850138240084326E-19,-407.5561055756799,0.0,-8.7275965531835E-19,0.003930560058475724,-0.0804207775305562);
		frames[i] = 240;
		accel[i++] = 0;

		transforms[i] = new AffineTransform3D();
		transforms[i].set(0.0013443307145500156,-0.003693518282505465,-8.201258078223062E-19,200.6964082544024,0.003693518282505465,0.0013443307145500156,2.9850138240084326E-19,-407.5561055756799,0.0,-8.7275965531835E-19,0.003930560058475724,-0.0804207775305562);
		frames[i] = 0;
		accel[i++] = 0;

		// play at 60 FPS
		for ( i = 0; i < frames.length; ++i )
			frames[ i ] *= 1.2;

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
