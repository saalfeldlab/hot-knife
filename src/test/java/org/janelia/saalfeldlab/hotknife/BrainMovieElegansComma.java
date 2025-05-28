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
public class BrainMovieElegansComma implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/Users/preibischs/Downloads/movie";
//	private final String n5Path = "/nrs/cellmap/data/jrc_c-elegans-comma-1/jrc_c-elegans-comma-1.zarr";
//	private final String n5Group = "/recon-1/em/fibsem-uint8/";

	private final String n5Path = "/nrs/cellmap/data/jrc_mosquito-stylet-5/jrc_mosquito-stylet-5.n5";
	private final String n5Group = "/render/jrc_mosquito_stylet_5/v2_acquire_align_16bit_destreak_sc___20250523_173706";


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

		new CommandLine(new BrainMovieElegansComma()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		//final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, true, false, false );
		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, Normalization.CLLCN, /*31226*/35000, 39648, false, false );

		//min=31226
		//max=39648
		final BdvStackSource<?> bdv = BdvFunctions.show(mipmapSource, BdvOptions.options().numRenderingThreads((Runtime.getRuntime().availableProcessors() - 1)));

		/*
		final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
		final BdvStackSource<?> bdv = BdvFunctions.show((Source)mipmapSource.asVolatile(queue));
		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);
		*/

		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);

		SimpleMultiThreading.threadHaltUnClean();

		Thread.sleep(1000);

		/* animate */
		final AffineTransform3D[] transforms = new AffineTransform3D[10];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(0.10818310061287871,0.0,2.4021473835151566E-17,-266.66936233593407,0.0,0.10818310061287871,0.0,-360.4263576237911,-2.4021473835151566E-17,0.0,0.10818310061287871,-0.4230732120542091);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(0.10818310061287871,0.0,2.4021473835151566E-17,-266.66936233593407,0.0,0.10818310061287871,0.0,-360.4263576237911,-2.4021473835151566E-17,0.0,0.10818310061287871,-0.4230732120542091);
		frames[1] = 0;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(0.10818310061287871,0.0,2.4021473835151566E-17,-266.66936233593407,0.0,0.10818310061287871,0.0,-360.4263576237911,-2.4021473835151566E-17,0.0,0.10818310061287871,-870.9230732120532);
		frames[2] = 240;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(1.2010736917575775E-17,0.0,0.10818310061287874,-447.72307321205324,0.0,0.10818310061287871,0.0,-358.4263576237911,-0.10818310061287874,0.0,1.2010736917575775E-17,292.6693623359339);
		frames[3] = 240;
		accel[3] = 0;

		transforms[4] = new AffineTransform3D();
		transforms[4].set(4.129488516195969E-33,0.7996881975151768,7.439025177108364E-17,-2828.4554334828777,-8.878322494023392E-17,7.439025177108364E-17,-0.7996881975151768,4689.595859039826,-0.7996881975151782,0.0,8.878322494023392E-17,2025.7224925613057);
		frames[4] = 240;
		accel[4] = 3;

		transforms[5] = new AffineTransform3D();
		transforms[5].set(-6.214825745816385E-16,0.7996881975151788,6.214825745816385E-16,-2828.4554334828827,6.214825745816385E-16,6.214825745816385E-16,-0.7996881975151788,4689.595859039835,-0.7996881975151788,-6.214825745816385E-16,-6.214825745816385E-16,2433.7224925613145);
		frames[5] = 240;
		accel[5] = 0;

		transforms[6] = new AffineTransform3D();
		transforms[6].set(-7.194437654000685E-16,0.9257390496485082,7.194437654000685E-16,-3376.7903145306914,7.194437654000685E-16,7.194437654000685E-16,-0.9257390496485082,2699.8190317026147,-0.9257390496485082,-7.194437654000685E-16,-7.194437654000685E-16,1586.1576383554275);
		frames[6] = 240;
		accel[6] = 3;

		transforms[7] = new AffineTransform3D();
		transforms[7].set(0.11927186842569881,2.6483674903254544E-17,2.6483674903254442E-17,-289.35994918457425,-2.6483674903254442E-17,0.11927186842569881,2.6483674903254544E-17,-406.2427148428142,-2.6483674903254544E-17,-2.6483674903254442E-17,0.11927186842569881,-361.37182139410623);
		frames[7] = 240;
		accel[7] = 3;


		transforms[8] = new AffineTransform3D();
		transforms[8].set(0.10818310061287871,0.0,2.4021473835151566E-17,-266.66936233593407,0.0,0.10818310061287871,0.0,-360.4263576237911,-2.4021473835151566E-17,0.0,0.10818310061287871,-0.4230732120542091);
		frames[8] = 240;
		accel[8] = 3;

		transforms[9] = new AffineTransform3D();
		transforms[9].set(0.10818310061287871,0.0,2.4021473835151566E-17,-266.66936233593407,0.0,0.10818310061287871,0.0,-360.4263576237911,-2.4021473835151566E-17,0.0,0.10818310061287871,-0.4230732120542091);
		frames[9] = 0;
		accel[9] = 0;

		// play at 60 FPS
		//for ( i = 0; i < frames.length; ++i )
		//	frames[ i ] *= 2.0;

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
