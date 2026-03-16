/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope
 * that it will be useful,
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
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.janelia.saalfeldlab.hotknife.VNCMovie.Normalization;

import bdv.cache.CacheControl;
import bdv.cache.SharedQueue;
import bdv.tools.InitializeViewerState;
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
import org.scijava.ui.behaviour.ClickBehaviour;
import org.scijava.ui.behaviour.io.gui.VisualEditorPanel;
import org.scijava.ui.behaviour.util.Behaviours;
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
public class FlyID25Crop2Movie implements Callable<Void> {

	/* set to true to navigate interactively and capture keyframe transforms with P */
	private final boolean interactive = true;

	/* some parameters */
	private final int screenWidth = 1000; // 1280;
	private final int screenHeight = 718; // 720;
	private final String outDir = "/Volumes/tavakoli/FlyLICONN/FlyID25/crop3/crop3_movie/movie";

	private final String n5Path = "/Volumes/tavakoli/data_internal/s12b/samples_for_stitching/FlyID25/crop2";

	// scale levels 0–7 are inside fused.ome.zarr within the Zarr v3 store
	private final String n5GroupCh0 = "/fused.ome.zarr";

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
//        viewerTranslation.set(
//                1, 0, 0, 0,
//                0, 1, 0, 0,
//                0, 0, 1, 0);
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

		new File(dir).mkdirs();

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

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException, InvocationTargetException {

		new CommandLine(new FlyID25Crop2Movie()).execute(args);
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException, InvocationTargetException {

		final ARGBType color0 = new ARGBType( ARGBType.rgba( 255, 255, 255, 255) );

		final double expansionFactor = 18.0;
		final VoxelDimensions vx = new FinalVoxelDimensions("um", new double[]{0.157 / expansionFactor, 0.157 / expansionFactor, 1.0 / expansionFactor});

		final BiFunction<Integer, Integer, double[]> computeScales = (scaleIndex, scale) -> {
			if ( scaleIndex == 0 )
				return new double[]{scale, scale, scale};
			else if ( scaleIndex == 1 )
				return new double[]{scale, scale, scale / 2.0};
            else
                return new double[]{scale, scale, scale / 4.0};

		};

		// TODO: adjust min/max clipping range (100–3000) to match this dataset's intensity range
		// scalePrefix "" means scale levels are named 0, 1, 2, ... (OME-Zarr convention)
		final RandomAccessibleIntervalMipmapSource<?> mipmapSource0 = VNCMovie.createMipmapSource( n5Path, n5GroupCh0, Normalization.CLAHE, false, false, 100, 2000, vx, computeScales, "" );

		final BdvStackSource<?> bdv;
		if ( interactive ) {
			final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
			bdv = BdvFunctions.show((Source)mipmapSource0.asVolatile(queue), BdvOptions.options().numRenderingThreads(Runtime.getRuntime().availableProcessors() - 1));
		} else {
			bdv = BdvFunctions.show(mipmapSource0, BdvOptions.options().numRenderingThreads(Runtime.getRuntime().availableProcessors() - 1));
		}
		bdv.setColor( color0 );

		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.setDisplayRange( 0, 255 );

		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);

		final Behaviours behaviors = new Behaviours(new org.scijava.ui.behaviour.io.InputTriggerConfig());
		behaviors.install(bdv.getBdvHandle().getTriggerbindings(), "print-transform");
		behaviors.behaviour((ClickBehaviour) (x, y) -> {
			final AffineTransform3D transform = new AffineTransform3D();
			bdv.getBdvHandle().getViewerPanel().state().getViewerTransform(transform);
			System.out.println(String.format("Current transform: [%s]", transform.toString().replace("AffineTransform3D: (", "").replace(")", "")));
		}, "print-transform", "P");

		if ( interactive ) {
			// Navigate to each keyframe position and press P to print the transform.
			// Paste the printed values into the transforms[] array below, then set interactive = false.
			SimpleMultiThreading.threadHaltUnClean();
		}

		Thread.sleep(3000);

		/* animate */
		final AffineTransform3D[] transforms = new AffineTransform3D[9];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

//        how I transformed it from the original values:
//        - scale: ×1.25
//                - tx: 1.25 × tx_old − 467.5
//                - ty: 1.25 × ty_old − 353.5
//                - tz: /5

		transforms[0] = new AffineTransform3D();
//		transforms[0].set(0.05168960392840988,0.0,0.0,-26.474155198035874,0.0,0.05168960392840988,0.0,-4.363410221618949,0.0,0.0,0.05168960392840988,-7934.018448990874);
        transforms[0].set(0.06461200491051237, 0.0, 0.0, -0.5926939975447567 - 500, 0.0, 0.06461200491051237, 0.0, -28.954262777023985 - 340, 0.0, 0.0, 0.06461200491051237, -1557.9323706144676);
        frames[0] = 0;
		accel[0] = 0;

		// hold at first transform
		transforms[1] = transforms[0].copy();
		frames[1] = 0;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
//		transforms[2].set(0.05168960392840988,0.0,0.0,-26.474155198035874,0.0,0.05168960392840988,0.0,-4.363410221618949,0.0,0.0,0.05168960392840988,-3483.0184489908743);
		transforms[2].set(0.06461200491051235,0.0,0.0,-500.5926939975448,0.0,0.06461200491051235,0.0,-358.954262777024,0.0,0.0,0.06461200491051235,-683.9323706144589);
		frames[2] = 240;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
//		transforms[3].set(0.18379131293890835,0.0,0.0,-1205.8510498298524,0.0,0.18379131293890835,0.0,-884.4435724523892,0.0,0.0,0.18379131293890835,-12384.473570683173); -2476.8947141366346
        // transforms[0].set(0.22973914117363503, 0.0, 0.0, -1923.8137503213775, 0.0, 0.22973914117363503, 0.0, -1397.2587875389204, 0.0, 0.0, 0.2297391411736351, -2420.748996276919);
		transforms[3].set(0.22973914117363544,0.0,0.0,-1974.8138122873155,0.0,0.22973914117363544,0.0,-1459.054465565487,0.0,0.0,0.22973914117363544,-2420.748996276919);
		frames[3] = 240;
		accel[3] = 3;

		transforms[4] = new AffineTransform3D();
//		transforms[4].set(0.18379131293890835,0.0,0.0,-1205.8510498298524,0.0,0.18379131293890835,0.0,-884.4435724523892,0.0,0.0,0.18379131293890835,-10498.473570683173);
		transforms[4].set(0.22973914117363544,0.0,0.0,-1974.8138122873155,0.0,0.22973914117363544,0.0,-1459.054465565487,0.0,0.0,0.22973914117363544,-2058.6947141366346);
		frames[4] = 240;
		accel[4] = 0;

		transforms[5] = new AffineTransform3D();
//		transforms[5].set(0.18379131293890835,0.0,0.0,-1900.8510498298524,0.0,0.18379131293890835,0.0,-524.4435724523892,0.0,0.0,0.18379131293890835,-10498.473570683173);
		transforms[5].set(0.22973914117363544,0.0,0.0,-2843.5638122873155,0.0,0.22973914117363544,0.0,-1009.054465565487,0.0,0.0,0.22973914117363544,-2058.6947141366346);
		frames[5] = 240;
		accel[5] = 3;

		transforms[6] = new AffineTransform3D();
//		transforms[6].set(0.2715434758227295,0.0,0.0,-2983.6488792738564,0.0,0.2715434758227295,0.0,-921.8982877669971,0.0,0.0,0.2715434758227295,-12349.849017771183);
		transforms[6].set(0.33942934477841188,0.0,0.0,-4197.0610990923205,0.0,0.33942934477841188,0.0,-1505.8728597087464,0.0,0.0,0.33942934477841188,-2421.9698035542366);
		frames[6] = 240;
		accel[6] = 3;

		transforms[7] = new AffineTransform3D();
//		transforms[7].set(0.04465142332656071,0.0,0.0,-78.14666013441797,0.0,0.04465142332656071,0.0,42.263853808723866,0.0,0.0,0.04465142332656071,-1230.7552403564432); 4.22
		transforms[7].set(0.05581427915820089,0.0,0.0,-565.1833251680225,0.0,0.05581427915820089,0.0,-300.67018273909517,0.0,0.0,0.05581427915820089,-241.3323706144608);
		frames[7] = 240;
		accel[7] = 3;

//        transforms[8] = new AffineTransform3D();
////		transforms[7].set(0.04465142332656071,0.0,0.0,-78.14666013441797,0.0,0.04465142332656071,0.0,42.263853808723866,0.0,0.0,0.04465142332656071,-1230.7552403564432); 4.22
//        transforms[8].set(0.022087605549306763, 0.0, 0.0, 308.8504675596406 - 500, 0.0, 0.0, 0.022087605549306763, -4.169998622078651 - 340, 0.0, -0.022087605549306763, 0.0, 117.23092560508468);
//        frames[8] = 240;
//        accel[8] = 3;

		// go back to first transform + hold
		transforms[8] = transforms[0].copy();
		frames[8] = 60;
		accel[8] = 0;

		// play at 60 FPS
		for ( int i = 0; i < frames.length; ++i )
			frames[ i ] *= 2;

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
