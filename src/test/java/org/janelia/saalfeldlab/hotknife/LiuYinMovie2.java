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
public class LiuYinMovie2 implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/groups/yinliu/yinliulab/Shared Data/Uniclear_VG/movie";
	private final String n5Path = "/groups/yinliu/yinliulab/Shared Data/Uniclear_VG";
	private final String n5Group = "/Uniclear_VG.n5";//"/wafer-52-align/run_20230329_104500/pass12";

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

		new CommandLine(new LiuYinMovie2()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, Normalization.NONE );

		final BdvStackSource<?> bdv = BdvFunctions.show((Source)mipmapSource.asVolatile(new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))));
		//final BdvStackSource<?> bdv = BdvFunctions.show(mipmapSource, BdvOptions.options().numRenderingThreads((Runtime.getRuntime().availableProcessors() - 1)));

		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);
		bdv.setDisplayRange(0, 150);
		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);

		Thread.sleep(1000);

		SimpleMultiThreading.threadHaltUnClean();

		/* animate */
		final AffineTransform3D[] transforms = new AffineTransform3D[9];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(0.4029578077424968,0.0,0.0,-282.46750761200525,0.0,0.4029578077424968,0.0,-463.4985210961288,0.0,0.0,0.4029578077424968,-52.787472814267076);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(0.4029578077424968,0.0,0.0,-282.46750761200525,0.0,0.4029578077424968,0.0,-463.4985210961288,0.0,0.0,0.4029578077424968,-52.787472814267076);
		frames[1] = 0;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(3.4481705171572514,0.0,0.0,-455.2129468453912,0.0,3.4481705171572514,0.0,-528.6855224867369,0.0,0.0,3.4481705171572514,-187.29439411052545);
		frames[2] = 60;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(4.851922192082792,0.0,2.154686292536031E-15,-469.68311352505975,-2.154686292536031E-15,0.0,4.851922192082792,-297.01770859375404,0.0,-4.851922192082792,0.0,612.3497326249689);
		frames[3] = 60;
		accel[3] = 0;

		transforms[4] = new AffineTransform3D();
		transforms[4].set(4.851922192082792,0.0,2.154686292536031E-15,-890.6831135250598,-2.154686292536031E-15,0.0,4.851922192082792,-511.01770859375404,0.0,-4.851922192082792,0.0,2782.349732624969);
		frames[4] = 120;
		accel[4] = 0;

		transforms[5] = new AffineTransform3D();
		transforms[5].set(4.851922192082792,0.0,2.154686292536031E-15,-1802.6831135250598,-2.154686292536031E-15,0.0,4.851922192082792,-735.017708593754,0.0,-4.851922192082792,0.0,4482.349732624969);
		frames[5] = 120;
		accel[5] = 0;

		transforms[6] = new AffineTransform3D();
		transforms[6].set(4.191272814670371,1.0332285208153396E-31,1.861299032533013E-15,-4034.1304782258258,-1.861299032533013E-15,9.306495162665065E-16,4.191272814670371,-626.7600385911288,-1.0332285208153396E-31,-4.191272814670371,9.306495162665065E-16,6434.948327291129);
		frames[6] = 120;
		accel[6] = 0;

		transforms[7] = new AffineTransform3D();
		transforms[7].set(0.4029578077424968,0.0,0.0,-282.46750761200525,0.0,0.4029578077424968,0.0,-463.4985210961288,0.0,0.0,0.4029578077424968,-52.787472814267076);
		frames[7] = 60;
		accel[7] = 0;

		transforms[8] = new AffineTransform3D();
		transforms[8].set(0.4029578077424968,0.0,0.0,-282.46750761200525,0.0,0.4029578077424968,0.0,-463.4985210961288,0.0,0.0,0.4029578077424968,-52.787472814267076);
		frames[8] = 3;
		accel[8] = 0;

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
