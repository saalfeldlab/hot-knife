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
public class LICONNMouseMovie implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/home/preibischs@hhmi.org/Downloads/movie_liconn";
	private final String n5Path = "/groups/tavakoli/tavakolilab/data_internal/mouse-liconn/fused-fix-2.n5";
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

		new CommandLine(new LICONNMouseMovie()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		//final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, true, false, false );
		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, Normalization.NONE );

		final BdvStackSource<?> bdv = BdvFunctions.show(mipmapSource, BdvOptions.options().numRenderingThreads((Runtime.getRuntime().availableProcessors() - 1) / 2));

		
		//final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
		//final BdvStackSource<?> bdv = BdvFunctions.show((Source)mipmapSource.asVolatile(queue));
		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);

		bdv.setDisplayRange( 0 , 1750 );

		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);

		//SimpleMultiThreading.threadHaltUnClean();

		Thread.sleep(1000);

		/* animate */
		final AffineTransform3D[] transforms = new AffineTransform3D[11];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(0.010748083398409618,0.0,0.0,-640.7946259583007,0.0,0.010748083398409618,0.0,-192.41240312030294,0.0,0.0,0.010748083398409618,-38.07992207639535);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(0.010748083398409618,0.0,0.0,-640.7946259583007,0.0,0.010748083398409618,0.0,-192.41240312030294,0.0,0.0,0.010748083398409618,-38.07992207639535);
		frames[1] = 0;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(0.010748083398409618,0.0,0.0,-640.7946259583007,0.0,0.010748083398409618,0.0,-192.41240312030294,0.0,0.0,0.010748083398409618,-183.07992207639535);
		frames[2] = 240;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(0.010748083398409618,0.0,0.0,-640.7946259583007,0.0,0.010748083398409618,0.0,-192.41240312030294,0.0,0.0,0.010748083398409618,-102.07992207639535);
		frames[3] = 120;
		accel[3] = 0;

		transforms[4] = new AffineTransform3D();
		transforms[4].set(9.786248892107616E-17,0.378573194460564,-1.0873609880119573E-17,-2726.1120607863586,-0.378573194460564,4.3494439520478296E-17,-1.5090165062125422E-33,30181.137175188753,0.0,1.0873609880119574E-17,0.378573194460564,-3595.498914389211);
		frames[4] = 240;
		accel[4] = 3;

		transforms[5] = new AffineTransform3D();
		transforms[5].set(0.0,0.37857319446056387,4.457961882617683E-17,-2726.112060786352,-0.37857319446056387,0.0,1.1144904706544204E-17,30181.137175188745,1.1144904706544204E-17,-4.457961882617683E-17,0.37857319446056387,-3911.498914389211);
		frames[5] = 240;
		accel[5] = 0;

		transforms[6] = new AffineTransform3D();
		transforms[6].set(-1.1665687221662977E-33,-7.318652551908343E-17,0.3785731944605642,-3246.498914389212,2.4651903288156624E-32,0.3785731944605642,7.318652551908343E-17,-2646.6120607863536,-0.3785731944605642,2.4651903288156624E-32,-1.0852797565588081E-33,30087.637175188767);
		frames[6] = 240;
		accel[6] = 3;

		transforms[7] = new AffineTransform3D();
		transforms[7].set(-1.1665687221662977E-33,-7.318652551908343E-17,0.3785731944605642,-3246.498914389212,2.4651903288156624E-32,0.3785731944605642,7.318652551908343E-17,-2646.6120607863536,-0.3785731944605642,2.4651903288156624E-32,-1.0852797565588081E-33,29880.637175188767);
		frames[7] = 240;
		accel[7] = 3;


		transforms[8] = new AffineTransform3D();
		transforms[8].set(0.4831659881467453,-1.051483991830756E-32,1.0728440095125641E-16,-36809.717012445515,1.6853666174069494E-33,0.4831659881467453,-8.536476745846784E-65,-2768.4772251780023,-1.0728440095125641E-16,6.986138256161441E-48,0.4831659881467453,-4339.994067736209);
		frames[8] = 240;
		accel[8] = 3;

		transforms[9] = new AffineTransform3D();
		transforms[9].set(0.010748083398409618,0.0,0.0,-640.7946259583007,0.0,0.010748083398409618,0.0,-192.41240312030294,0.0,0.0,0.010748083398409618,-102.07992207639535);
		frames[9] = 240;
		accel[9] = 3;

		transforms[10] = new AffineTransform3D();
		transforms[10].set(0.010748083398409618,0.0,0.0,-640.7946259583007,0.0,0.010748083398409618,0.0,-192.41240312030294,0.0,0.0,0.010748083398409618,-102.07992207639535);
		frames[10] = 0;
		accel[10] = 0;

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
