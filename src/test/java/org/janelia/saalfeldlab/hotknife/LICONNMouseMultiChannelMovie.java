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
import java.util.function.BiFunction;

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
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command
public class LICONNMouseMultiChannelMovie implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/home/preibischs@hhmi.org/Downloads/movie_multichannel_mouse";
	private final String n5Path = "/nrs/tavakoli/data_internal/s12c/samples_for_stitching/20250902_mouse_hipp_3_channels/fused.n5";
	private final String n5GroupCh0 = "/ch0tp0/";
	private final String n5GroupCh1 = "/ch1tp0/";
	private final String n5GroupCh2 = "/ch2tp0/";

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
				32,
				null,
				false,
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

		new CommandLine(new LICONNMouseMultiChannelMovie()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		//final ARGBType color0 = new ARGBType( ARGBType.rgba( 255, 255, 255, 255) );
		final ARGBType color0 = new ARGBType( ARGBType.rgba( 255, 204, 153, 255) );
		final ARGBType color1 = new ARGBType( ARGBType.rgba( 0, 255, 255, 255) );
		final ARGBType color2 = new ARGBType( ARGBType.rgba( 255, 0, 255, 255) );

		final double expansionFactor = 15.5;
		final VoxelDimensions vx = new FinalVoxelDimensions("um", new double[]{0.157 / expansionFactor, 0.157 / expansionFactor, 0.157 / expansionFactor });

		final BiFunction<Integer, Integer, double[]> computeScales = (scaleIndex, scale) -> {
			if ( scaleIndex == 0 )
				return new double[]{scale, scale, scale * 4 };
			else
				return new double[]{scale, scale, scale / 2 * 4 };
		};

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource0 = VNCMovie.createMipmapSource( n5Path, n5GroupCh0, Normalization.CLAHE, false, false, 100, 3000, vx, computeScales );
		final RandomAccessibleIntervalMipmapSource<?> mipmapSource1 = VNCMovie.createMipmapSource( n5Path, n5GroupCh1, Normalization.NONE, false, false, 10, 450, vx, computeScales );
		final RandomAccessibleIntervalMipmapSource<?> mipmapSource2 = VNCMovie.createMipmapSource( n5Path, n5GroupCh2, Normalization.NONE, false, false, 10, 125, vx, computeScales );

		// for recording
		
		BdvStackSource<?> bdv = BdvFunctions.show(mipmapSource0, BdvOptions.options().numRenderingThreads(Runtime.getRuntime().availableProcessors()/3));
		bdv.setColor( color0 );
		bdv = BdvFunctions.show(mipmapSource1, BdvOptions.options().numRenderingThreads(Runtime.getRuntime().availableProcessors()/3).addTo(bdv));
		bdv.setColor( color1 );
		bdv = BdvFunctions.show(mipmapSource2, BdvOptions.options().numRenderingThreads(Runtime.getRuntime().availableProcessors()/3).addTo(bdv));
		bdv.setColor( color2 );
		

		// for viewing
		final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
		/*
		BdvStackSource<?> bdv = BdvFunctions.show((Source)mipmapSource0.asVolatile(queue));
		bdv.setColor( color0 );
		bdv = BdvFunctions.show((Source)mipmapSource1.asVolatile(queue), BdvOptions.options().addTo(bdv));
		bdv.setColor( color1 );
		bdv = BdvFunctions.show((Source)mipmapSource2.asVolatile(queue), BdvOptions.options().addTo(bdv));
		bdv.setColor( color2 );
		*/
		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);

		bdv.setDisplayRange( 0 , 255 );

		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);

		//SimpleMultiThreading.threadHaltUnClean();

		Thread.sleep(3000);

		/* animate */
		final AffineTransform3D[] transforms = new AffineTransform3D[15];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(3.532102186204135E-18,0.012129996424740786,4.040100395810416E-18,-469.1933403624089,-0.012129996424740786,2.1192613117224814E-18,6.274273875924292E-34,332.98377239214165,0.0,-4.0401003958104174E-18,0.012129996424740792,-173.13878614299034);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(3.532102186204135E-18,0.012129996424740786,4.040100395810416E-18,-469.1933403624089,-0.012129996424740786,2.1192613117224814E-18,6.274273875924292E-34,332.98377239214165,0.0,-4.0401003958104174E-18,0.012129996424740792,-173.13878614299034);
		frames[1] = 0;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(3.532102186204135E-18,0.012129996424740786,4.040100395810416E-18,-469.1933403624089,-0.012129996424740786,2.1192613117224814E-18,6.274273875924292E-34,332.98377239214165,0.0,-4.0401003958104174E-18,0.012129996424740792,-21.13878614299034);
		frames[2] = 240;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(3.532102186204135E-18,0.012129996424740786,4.040100395810416E-18,-469.1933403624089,-0.012129996424740786,2.1192613117224814E-18,6.274273875924292E-34,332.98377239214165,0.0,-4.0401003958104174E-18,0.012129996424740792,-130);
		frames[3] = 120;
		accel[3] = 0;

		transforms[4] = new AffineTransform3D();
		transforms[4].set(4.0504027853337493E-17,0.13909951840226567,4.632944641333981E-17,-609.6327077471249,-0.13909951840226567,2.4302416712002495E-17,7.194960690053429E-33,2885.9608086231437,0.0,-4.6329446413339814E-17,0.13909951840226567,-1389.1468902625913);
		frames[4] = 240;
		accel[4] = 3;

		transforms[5] = new AffineTransform3D();
		transforms[5].set(4.0504027853337493E-17,0.13909951840226567,4.632944641333981E-17,-2647.632707747125,-0.13909951840226567,2.4302416712002495E-17,7.194960690053429E-33,2616.9608086231437,0.0,-4.6329446413339814E-17,0.13909951840226567,-1389.1468902625913);
		frames[5] = 240;
		accel[5] = 0;

		transforms[6] = new AffineTransform3D();
		transforms[6].set(4.0504027853337493E-17,0.13909951840226567,4.632944641333981E-17,-2653.632707747125,-0.13909951840226567,2.4302416712002495E-17,7.194960690053429E-33,2456.9608086231437,0.0,-4.6329446413339814E-17,0.13909951840226567,-1759.1468902625913);
		frames[6] = 240;
		accel[6] = 3;

		transforms[7] = new AffineTransform3D();
		transforms[7].set(6.17725952177864E-17,0.13909951840226567,4.6329446413339764E-17,-6060.632707747125,-0.13909951840226567,6.17725952177864E-17,1.0287203625245446E-32,2311.960808623143,-1.0287203625245446E-32,-4.6329446413339764E-17,0.13909951840226567,-1759.1468902625913);
		frames[7] = 240;
		accel[7] = 3;

		transforms[8] = new AffineTransform3D();
		transforms[8].set(1.284207889136281E-16,0.2891779085490203,9.631559168522099E-17,-12173.226080709092,-0.2891779085490203,1.284207889136281E-16,2.1386357503865548E-32,4612.420252874623,-2.1386357503865548E-32,-9.631559168522099E-17,0.2891779085490203,-3282.9329695967335);
		frames[8] = 240;
		accel[8] = 3;

		transforms[9] = new AffineTransform3D();
		transforms[9].set(1.9263118337044216E-16,0.2891779085490202,9.631559168522094E-17,-12173.226080709082,-0.2891779085490202,1.9263118337044216E-16,2.851514333848739E-32,4612.420252874619,-2.851514333848739E-32,-9.631559168522094E-17,0.2891779085490202,-3772.9329695967317);
		frames[9] = 240;
		accel[9] = 3;

		transforms[10] = new AffineTransform3D();
		transforms[10].set(2.458516276982519E-16,0.3690724329634256,1.2292581384912572E-16,-10260.463062823419,-0.3690724329634256,2.458516276982519E-16,3.6393351694956164E-32,15866.938830236748,-3.6393351694956164E-32,-1.2292581384912572E-16,0.3690724329634256,-4815.3247856446815);
		frames[10] = 240;
		accel[10] = 0;

		transforms[11] = new AffineTransform3D();
		transforms[11].set(2.458516276982519E-16,0.3690724329634256,1.2292581384912572E-16,-10260.463062823419,-0.3690724329634256,2.458516276982519E-16,3.6393351694956164E-32,15866.938830236748,-3.6393351694956164E-32,-1.2292581384912572E-16,0.3690724329634256,-4935.3247856446815);
		frames[11] = 240;
		accel[11] = 0;

		transforms[12] = new AffineTransform3D();
		transforms[12].set(3.532102186204135E-18,0.012129996424740786,4.040100395810416E-18,-469.1933403624089,-0.012129996424740786,2.1192613117224814E-18,6.274273875924292E-34,332.98377239214165,0.0,-4.0401003958104174E-18,0.012129996424740792,-100);
		frames[12] = 240;
		accel[12] = 0;

		transforms[13] = new AffineTransform3D();
		transforms[13].set(3.532102186204135E-18,0.012129996424740786,4.040100395810416E-18,-469.1933403624089,-0.012129996424740786,2.1192613117224814E-18,6.274273875924292E-34,332.98377239214165,0.0,-4.0401003958104174E-18,0.012129996424740792,-173.13878614299034);
		frames[13] = 120;
		accel[13] = 0;

		transforms[14] = new AffineTransform3D();
		transforms[14].set(3.532102186204135E-18,0.012129996424740786,4.040100395810416E-18,-469.1933403624089,-0.012129996424740786,2.1192613117224814E-18,6.274273875924292E-34,332.98377239214165,0.0,-4.0401003958104174E-18,0.012129996424740792,-173.13878614299034);
		frames[14] = 0;
		accel[14] = 0;

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
