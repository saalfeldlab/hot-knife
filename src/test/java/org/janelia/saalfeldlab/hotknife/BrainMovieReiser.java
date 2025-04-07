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
import bdv.cache.SharedQueue;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
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
public class BrainMovieReiser implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/groups/scicompsoft/home/preibischs/record3";
	private final String n5Path = "/Volumes/flyem/render/n5/Z0720_07m_CNS-dvid-coords.n5/";
	private final String n5Group = "/";
	//String n5Group = "/22-34";

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

		new CommandLine(new BrainMovie()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		//final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, true, false, true );
		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, true, false, false );

		//final BdvStackSource<?> bdv = BdvFunctions.show(mipmapSource, BdvOptions.options().numRenderingThreads((Runtime.getRuntime().availableProcessors() - 1) / 2));
		final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
		final BdvStackSource<?> bdv = BdvFunctions.show((Source)mipmapSource.asVolatile(queue));

		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);
		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);

		SimpleMultiThreading.threadHaltUnClean();

		Thread.sleep(1000);

		/* animate */
		final AffineTransform3D[] transforms = new AffineTransform3D[8];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(1.6522276101665108E-18,-4.585852836820647E-35,0.009921295532636184,-450.0204671618677,2.188018690849767E-34,0.009921295532636184,4.5858528368206426E-35,-348.8958646809869,-0.009921295532636184,2.188018690849767E-34,1.6522276101665108E-18,85.20210055249748);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(1.6522276101665108E-18,-4.585852836820647E-35,0.009921295532636184,-450.0204671618677,2.188018690849767E-34,0.009921295532636184,4.5858528368206426E-35,-348.8958646809869,-0.009921295532636184,2.188018690849767E-34,1.6522276101665108E-18,85.20210055249748);
		frames[1] = 0;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(1.6522276101665108E-18,-4.585852836820647E-35,0.009921295532636184,-450.0204671618677,2.188018690849767E-34,0.009921295532636184,4.5858528368206426E-35,-348.8958646809869,-0.009921295532636184,2.188018690849767E-34,1.6522276101665108E-18,475.20210055249754);
		frames[2] = 240;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(1.168014971819789E-32,0.3852705533756205,7.013695722467684E-17,-11007.883798986051,-6.416043586015346E-17,7.013695722467684E-17,-0.3852705533756205,13982.219454464168,-0.3852705533756207,8.496664261707594E-33,6.416043586015346E-17,11463.49045478806);
		frames[3] = 240;
		accel[3] = 0;

		transforms[4] = new AffineTransform3D();
		transforms[4].set(0.40453408104440214,-4.4912305102107416E-17,-2.2456152551053724E-17,-11279.501533788429,2.245615255105371E-17,0.40453408104440214,4.4912305102107453E-17,-9991.524643083518,4.491230510210743E-17,-6.736845765316115E-17,0.40453408104440214,-20329.021835785967);
		frames[4] = 240;
		accel[4] = 3;

		transforms[5] = new AffineTransform3D();
		transforms[5].set(0.385270553375621,0.0,-1.2465147490095222E-16,-11515.490454788065,-2.465190328815662E-32,0.3852705533756208,-1.0160938435154267E-32,-10991.883798986057,1.246514749009522E-16,2.465190328815662E-32,0.3852705533756208,-16478.219454464182);
		frames[5] = 240;
		accel[5] = 0;

		transforms[6] = new AffineTransform3D();
		transforms[6].set(-1.8340560952523347E-18,1.959111600666342E-34,0.010417360309267983,-491.493121998005,-3.3328236042423397E-34,0.010417360309267983,1.959111600666342E-34,-326.3992243939583,-0.010417360309267983,3.3328236042423397E-34,-1.8340560952523324E-18,310.1514791218605);
		frames[6] = 240;
		accel[6] = 3;

		transforms[7] = new AffineTransform3D();
		transforms[7].set(1.6522276101665108E-18,-4.585852836820647E-35,0.009921295532636184,-450.0204671618677,2.188018690849767E-34,0.009921295532636184,4.5858528368206426E-35,-348.8958646809869,-0.009921295532636184,2.188018690849767E-34,1.6522276101665108E-18,85.20210055249748);
		frames[7] = 240;
		accel[7] = 3;


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
