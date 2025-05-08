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
public class MultiSemMovieWafer53 implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/groups/scicompsoft/home/preibischs/recordMultiSemWafer53/";
	private final String n5Path = "/nrs/hess/data/hess_wafer_53/export/hess_wafer_53d.n5/";
	private final String n5Group = "/wafer-53-align/run_20240409_135204/pass12";//12";//"/wafer-52-align/run_20230329_104500/pass12";

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

		new CommandLine(new MultiSemMovieWafer53()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, Normalization.CLLCN, true, true );

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
		final AffineTransform3D[] transforms = new AffineTransform3D[13];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(0.006964369794729823, 0.0, 0.0, -417.0347836967968, 0.0, 0.006964369794729823, 0.0, -424.95116634117977, 0.0, 0.0, 0.006964369794729823, 0.005);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(0.006964369794729823, 0.0, 0.0, -417.0347836967968, 0.0, 0.006964369794729823, 0.0, -424.95116634117977, 0.0, 0.0, 0.006964369794729823, 0.005); // z=0
		frames[1] = 0;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(0.006964369794729823, 0.0, 0.0, -417.0347836967968, 0.0, 0.006964369794729823, 0.0, -424.95116634117977, 0.0, 0.0, 0.006964369794729823, -2.302); // z=end
		frames[2] = 90;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(0.006964369794729823, 0.0, 0.0, -417.0347836967968, 0.0, 0.006964369794729823, 0.0, -424.95116634117977, 0.0, 0.0, 0.006964369794729823, -1.0585); // z=middle
		frames[3] = 30;
		accel[3] = 0;

		transforms[4] = new AffineTransform3D(); // zoom in
		transforms[4].set(-0.06293399161635259, 0.35691640248979406, 0.0, -17306.343869127937, -0.35691640248979406, -0.06293399161635259, 0.0, 26460.355035563094, 0.0, 0.0, 0.36242241330666064, -55.07956095113786);
		frames[4] = 120;
		accel[4] = 0;

		transforms[5] = new AffineTransform3D(); // turn 90 degrees
		transforms[5].set(0.0, 0.0, 1.0601800496652112, -181.8468432715947, -5.886441395273198E-16, 1.0601800496652107, 0.0, -62643.34918197945, -1.0601800496652107, -5.886441395273198E-16, 0.0, 67291.0695783979);
		frames[5] = 60;
		accel[5] = 0;

		transforms[6] = new AffineTransform3D(); // move over
		transforms[6].set(5.296663356239514E-16, 1.568127363142791E-31, 1.06018004966521, -181.8468432716055, -5.886441395273193E-16, 1.0601800496652103, 1.568127363142791E-31, -62643.349181979385, -1.06018004966521, -5.886441395273193E-16, 5.296663356239514E-16, 65711.06957839783 );
		frames[6] = 150;
		accel[6] = 0;

		transforms[7] = new AffineTransform3D(); // turn another 90 degrees
		transforms[7].set(1.0601800496652098, 2.0003495013007632E-16, 1.7655544520798356E-16, -65774.0695783978,-1.7655544520798356E-16, -5.3834624715163426E-17, 1.0601800496652098, -163.84684327156216, 1.411831350607485E-16, -1.0601800496652098, -5.3834624715163026E-17, 62488.3491819794 );
		frames[7] = 60;
		accel[7] = 0;

		transforms[8] = new AffineTransform3D(); // zoom out
		transforms[8].set(1.0601800496652105, 9.860761315262648E-32, -4.085413641641328E-32, -67320.06957839786, -9.860761315262648E-32, 1.0601800496652105, 0.0, -62643.34918197947, -4.085413641641326E-32, -2.2683456441729402E-47, 1.060180049665211, -152.84684327159468);
		frames[8] = 120;
		accel[8] = 0;

		transforms[9] = new AffineTransform3D(); // at full res to end
		transforms[9].set( 1.0601800496652105, 1.0127489220297191E-31, -6.236558389981315E-79, -67320.06957839786, -1.0127489220297191E-31, 1.0601800496652105, -1.3057283280793954E-47, -62643.34918197947, -6.236558389981315E-79, 1.3057283280793954E-47, 1.0601800496652105, -350.24684327159457 );
		frames[9] = 120;
		accel[9] = 0;

		transforms[10] = new AffineTransform3D(); // at full res to beginning
		transforms[10].set( 1.0601800496652105, 1.0127489220297191E-31, -6.236558389981315E-79, -67320.06957839786, -1.0127489220297191E-31, 1.0601800496652105, -1.3057283280793954E-47, -62643.34918197947, -6.236558389981315E-79, 1.3057283280793954E-47, 1.0601800496652105, 0.453156728405429 );
		frames[10] = 240;
		accel[10] = 0;

		transforms[11] = new AffineTransform3D(); // back to start
		transforms[11].set(0.006964369794729823, 0.0, 0.0, -417.0347836967968, 0.0, 0.006964369794729823, 0.0, -424.95116634117977, 0.0, 0.0, 0.006964369794729823, 0.005);
		frames[11] = 30;
		accel[11] = 0;

		transforms[12] = new AffineTransform3D();
		transforms[12].set(0.006964369794729823, 0.0, 0.0, -417.0347836967968, 0.0, 0.006964369794729823, 0.0, -424.95116634117977, 0.0, 0.0, 0.006964369794729823, 0.005);
		frames[12] = 3;
		accel[12] = 0;

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
