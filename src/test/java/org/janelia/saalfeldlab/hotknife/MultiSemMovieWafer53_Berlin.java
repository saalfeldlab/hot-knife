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
public class MultiSemMovieWafer53_Berlin implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 800;
	private final String outDir = "/groups/scicompsoft/home/preibischs/recordMultiSemWafer53-Berlin/";
	private final String n5Path = "/nrs/hess/data/hess_wafer_53/export/hess_wafer_53_center7.n5/";
	private final String n5Group = "/wafer-53-align/run_20240604_104500/pass12";//12";//"/wafer-52-align/run_20230329_104500/pass12";

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

		new CommandLine(new MultiSemMovieWafer53_Berlin()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, false, true, false );

		boolean setUpTransforms = false;
		final BdvStackSource<?> bdv;

		if ( setUpTransforms )
		{
			// volatile for setting up transformations
			bdv = BdvFunctions.show((Source)mipmapSource.asVolatile(new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))));
			bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
			bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);
			final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
			frame.setSize(screenWidth, screenHeight);
			SimpleMultiThreading.threadHaltUnClean();
		}
		else
		{
			// non-volatile for movie
			bdv = BdvFunctions.show(mipmapSource, BdvOptions.options().numRenderingThreads((Runtime.getRuntime().availableProcessors() - 1)));
			bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
			bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);
			final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
			frame.setSize(screenWidth, screenHeight);
	
			Thread.sleep(2000);
		}

		/* animate */
		final AffineTransform3D[] transforms = new AffineTransform3D[10];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(0.0122538061665851, 0.0, 0.0, -474.67919207417896, 0.0, 0.012253806166585099, 4.0813373236309525E-18, -463.19387309691666, 0.0, -4.0813373236309525E-18, 0.012253806166585099, 0.005);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D(); // z=0
		transforms[1].set(0.0122538061665851, 0.0, 0.0, -474.67919207417896, 0.0, 0.012253806166585099, 4.0813373236309525E-18, -463.19387309691666, 0.0, -4.0813373236309525E-18, 0.012253806166585099, 0.005);
		frames[1] = 0;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D(); // z=end
		transforms[2].set(0.0122538061665851, 0.0, 0.0, -474.67919207417896, 0.0, 0.012253806166585099, 4.0813373236309525E-18, -463.19387309691666, 0.0, -4.0813373236309525E-18, 0.012253806166585099, -159.16833904313424);
		frames[2] = 160;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D(); // z=middle+zoom-in
		transforms[3].set(6.579532889242826E-17, 0.6376821067782059, 1.4159387146732836E-16, -22266.147865369756, -0.6376821067782059, 6.579532889242826E-17, 0.0, 23708.05356588698, 0.0, -1.415938714673285E-16, 0.6376821067782059, -3993.4537357004856);
		frames[3] = 120;
		accel[3] = 0;

		transforms[4] = new AffineTransform3D(); // step through z
		transforms[4].set(1.4159387146732836E-16, 0.6376821067782059, 2.0784318863440765E-16, -22266.147865369756, -0.6376821067782059, 1.4159387146732836E-16, -6.374851374646151E-17, 23708.05356588698, -6.374851374646156E-17, -2.078431886344076E-16, 0.6376821067782059, -4502.453735700481);
		frames[4] = 240;
		accel[4] = 0;

		transforms[5] = new AffineTransform3D(); // all slabs orthogonal 
		transforms[5].set(1.0512578058281057E-18, 1.6517096550903686E-17, 0.0951089894453882, -620.619890088475, -5.2796094965550275E-18, 0.0951089894453882, -1.6517096550903683E-17, -3233.723408947371, -0.0951089894453882, -1.0559218993110087E-17, 6.330867302383132E-18, 3523.330399871545);
		frames[5] = 30;
		accel[5] = 0;

		/*
		transforms[6] = new AffineTransform3D(); // go through slabs  
		transforms[6].set(1.0512578058281057E-18, 1.6517096550903686E-17, 0.0951089894453882, -620.619890088475, -5.2796094965550275E-18, 0.0951089894453882, -1.6517096550903683E-17, -3233.723408947371, -0.0951089894453882, -1.0559218993110087E-17, 6.330867302383132E-18, 3363.330399871545 );
		frames[6] = 150;
		accel[6] = 0;

		transforms[7] = new AffineTransform3D(); // turn around axis
		transforms[7].set( 0.09510898944538818 -1.5407439555097887E-33, 1.0559218993110086E-17, -3310.3303998715437, 1.5407439555097887E-33, 0.09510898944538819, 1.9845370556821E-33, -3233.72340894737, -1.0559218993110086E-17, -4.3543850344344235E-34, 0.09510898944538818, -673.6198900884727 );
		frames[7] = 60;
		accel[7] = 0;
		*/

		transforms[6] = new AffineTransform3D(); // go through slabs and zoom in at the same time 
		transforms[6].set(3.0908166389664937E-16, 2.031029209420573E-16, 1.391980066352003, -11217.100597845474, -1.1590562396124324E-16, 1.391980066352003, -2.031029209420573E-16, -46183.47172595864, -1.391980066352003, -1.159056239612432E-16, 3.0908166389664937E-16, 49224.462382340185 );
		frames[6] = 240;
		accel[6] = 0;

		transforms[7] = new AffineTransform3D(); // turn around axis
		transforms[7].set( 1.391980066352003, 4.930380657631324E-32, 0.0, -49182.46238234019, -2.465190328815662E-32, 1.391980066352003, -2.5736218481063475E-32, -46183.47172595865, 0.0, -1.0843151929068615E-33, 1.391980066352003, -11259.100597845456 );
		frames[7] = 30;
		accel[7] = 0;

		transforms[8] = new AffineTransform3D(); // back to start
		transforms[8].set(0.0122538061665851, 0.0, 0.0, -474.67919207417896, 0.0, 0.012253806166585099, 4.0813373236309525E-18, -463.19387309691666, 0.0, -4.0813373236309525E-18, 0.012253806166585099, 0.005);
		frames[8] = 60;
		accel[8] = 0;

		transforms[9] = new AffineTransform3D();
		transforms[9].set(0.0122538061665851, 0.0, 0.0, -474.67919207417896, 0.0, 0.012253806166585099, 4.0813373236309525E-18, -463.19387309691666, 0.0, -4.0813373236309525E-18, 0.012253806166585099, 0.005);
		frames[9] = 3;
		accel[9] = 0;

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
