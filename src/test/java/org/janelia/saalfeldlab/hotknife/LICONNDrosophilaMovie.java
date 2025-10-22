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
public class LICONNDrosophilaMovie implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/home/preibischs@hhmi.org/Downloads/movie_dropsophila_60";
	private final String n5Path = "/nrs/keller/data_external/s12a/samples_for_stitching/fly_brain_3/preibischs/fly_brain_3_basic_intensity.n5";
	private final String n5Group = "/ch0tp0/";
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
		//final ScaleBarOverlayRenderer scalebar = new ScaleBarOverlayRenderer();
		final MultiBoxOverlayRenderer box = new MultiBoxOverlayRenderer(width, height);

		final VNCMovie.Target target = new VNCMovie.Target(width, height);

		final MultiResolutionRenderer renderer = new MultiResolutionRenderer(
				target,
				new PainterThread(null),
				new double[]{1.0},
				0l,
				32,
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
				//scalebar.setViewerState(renderState);
				//scalebar.paint(g2);
				box.setViewerState(renderState);
				box.paint(g2);

				/* save image */
				ImageIO.write(bi, "png", new File(String.format("%s/img-%04d.png", dir, i++)));

				System.out.println(String.format("%s/img-%04d.png", dir, i));
			}
		}
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		new CommandLine(new LICONNDrosophilaMovie()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		//final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, true, false, false );
		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, Normalization.CLLCN );

		// TODO: downsampling seems wrong
		
		// for recording
		//final BdvStackSource<?> bdv = BdvFunctions.show(mipmapSource, BdvOptions.options().numRenderingThreads((Runtime.getRuntime().availableProcessors() - 1)));

		// for viewing
		final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
		final BdvStackSource<?> bdv = BdvFunctions.show((Source)mipmapSource.asVolatile(queue));

		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);

		bdv.setDisplayRange( 0 , 255 );

		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);

		SimpleMultiThreading.threadHaltUnClean();

		Thread.sleep(3000);

		/* animate */
		final AffineTransform3D[] transforms = new AffineTransform3D[12];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(0.006538729331609365,-0.015404280965353092,1.4692917081596245E-18,338.521098408012,0.01540428096535308,0.0065387293316093685,5.431414732184772E-18,-629.5960662283549,-5.747738326325976E-18,-3.3907572175044878E-19,0.01673460048317821,-372.32465949305646);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(0.006538729331609365,-0.015404280965353092,1.4692917081596245E-18,338.521098408012,0.01540428096535308,0.0065387293316093685,5.431414732184772E-18,-629.5960662283549,-5.747738326325976E-18,-3.3907572175044878E-19,0.01673460048317821,-372.32465949305646);
		frames[1] = 0;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(0.006538729331609365,-0.015404280965353092,1.4692917081596245E-18,338.521098408012,0.01540428096535308,0.0065387293316093685,5.431414732184772E-18,-629.5960662283549,-5.747738326325976E-18,-3.3907572175044878E-19,0.01673460048317821,-1.3246594930564584);
		frames[2] = 240;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(0.006538729331609365,-0.015404280965353092,1.4692917081596245E-18,338.521098408012,0.01540428096535308,0.0065387293316093685,5.431414732184772E-18,-629.5960662283549,-5.747738326325976E-18,-3.3907572175044878E-19,0.01673460048317821,-139.32465949305646);
		frames[3] = 240;
		accel[3] = 3;

		transforms[4] = new AffineTransform3D();
		transforms[4].set(0.2193425526183651,-0.5167386715117969,4.9287587459964223E-17,10016.35565206154,0.5167386715117963,0.21934255261836522,1.8219753583119804E-16,-17402.117656657083,-1.9280865323239342E-16,-1.1374340574111586E-17,0.561364418203467,-4601.66439350226);
		frames[4] = 240;
		accel[4] = 0;

		transforms[5] = new AffineTransform3D();
		transforms[5].set(0.2193425526183651,-0.5167386715117969,4.9287587459964223E-17,10016.35565206154,0.5167386715117963,0.21934255261836522,1.8219753583119804E-16,-17402.117656657083,-1.9280865323239342E-16,-1.1374340574111586E-17,0.561364418203467,-5111.66439350226);
		frames[5] = 240;
		accel[5] = 3;

		transforms[6] = new AffineTransform3D();
		transforms[6].set(0.01652304336201876,-0.038925850795020166,4.2928154910210805E-18,943.8262924868504,0.038925850795020166,0.01652304336201876,1.3326299451266314E-17,-1226.5873782008828,-1.3944259964654137E-17,-1.2554425287185684E-18,0.042287501960500386,-385.0609533704004);
		frames[6] = 240;
		accel[6] = 3;


		transforms[7] = new AffineTransform3D();
		transforms[7].set(0.12824504773130999,-0.3021263991029861,3.33190632915246E-17,10216.638483111097,0.3021263991029861,0.12824504773130999,1.0343323997671684E-16,-9589.328228205039,-1.082295946069815E-16,-9.744227107067052E-18,0.3282181489536237,-3511.505803532713);
		frames[7] = 240;
		accel[7] = 3;

		transforms[8] = new AffineTransform3D();
		//transforms[8].set(0.41360309621981,-0.9743878327414641,1.0745730914608899E-16,32898.50289811943,0.9743878327414641,0.41360309621981,3.3358253642703506E-16,-30805.273982103918,-3.490512594751332E-16,-3.1426106294536016E-17,1.0585363337161464,-12606.419362456767);
		transforms[8].set(1.0314061160605266,0.23811886433073803,3.4717443405303147E-16,-24624.01517113601,-0.23811886433073803,1.0314061160605266,-4.7898791612830935E-17,-37748.393581996614,-3.49051259475133E-16,-3.142610629453596E-17,1.0585363337161466,-12606.419362456769);
		frames[8] = 240;
		accel[8] = 3;

		transforms[9] = new AffineTransform3D();
		transforms[9].set(1.0585363337161473,8.326672684688675E-17,1.1177979073118497E-17,-15519.450969817097,-1.1369706488059169E-17,7.900154109194753E-18,1.0585363337161473,-12685.919362456796,8.326672684688673E-17,-1.0585363337161473,6.2173841850362746E-18,42220.84084636987);
		frames[9] = 120;
		accel[9] = 0;

		transforms[10] = new AffineTransform3D();
		transforms[10].set(0.006538729331609365,-0.015404280965353092,1.4692917081596245E-18,338.521098408012,0.01540428096535308,0.0065387293316093685,5.431414732184772E-18,-629.5960662283549,-5.747738326325976E-18,-3.3907572175044878E-19,0.01673460048317821,-372.32465949305646);
		frames[10] = 240;
		accel[10] = 0;

		transforms[11] = new AffineTransform3D();
		transforms[11].set(0.006538729331609365,-0.015404280965353092,1.4692917081596245E-18,338.521098408012,0.01540428096535308,0.0065387293316093685,5.431414732184772E-18,-629.5960662283549,-5.747738326325976E-18,-3.3907572175044878E-19,0.01673460048317821,-372.32465949305646);
		frames[11] = 0;
		accel[11] = 0;

		// play at 60 FPS
		for ( int i = 0; i < frames.length; ++i )
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
