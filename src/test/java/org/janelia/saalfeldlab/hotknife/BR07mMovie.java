package org.janelia.saalfeldlab.hotknife;

import java.awt.Window;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.swing.SwingUtilities;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.viewer.Interpolation;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.realtransform.AffineTransform3D;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command
public class BR07mMovie implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;

	private final String outDir = "/Users/spreibi/Documents/Janelia/Projects/Male CNS+VNC Alignment/07m/recording";
	private final String n5Path = "/nrs/flyem/render/n5/Z0720_07m_BR";
	private final String n5Group = "/39-26";

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

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, new FinalVoxelDimensions("um", new double[]{0.008, 0.008, 0.008}), "BR07m");

		final BdvStackSource<?> bdv = BdvFunctions.show(mipmapSource, BdvOptions.options().numRenderingThreads((Runtime.getRuntime().availableProcessors() - 1) / 2));
//		final SharedQueue queue = new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
//		final BdvStackSource<?> bdv = BdvFunctions.show((Source)mipmapSource.asVolatile(queue));

//		Thread.sleep(10000);

		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);
		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);

		Thread.sleep(1000);

		/* animate */
		final AffineTransform3D[] transforms = new AffineTransform3D[5];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(-5.204170427930421E-18, -3.953584785207328E-19, -0.014244290372349894, 287.30628194269025, -3.469446951953614E-18, -0.01424429037234989, -3.953584785207325E-19, 346.50256915512205, -0.014244290372349878, 8.673617379884031E-19, 2.6020852139652106E-18, 411.17005134777844);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(0.02320244804977833, -4.5672789353082E-19, 3.489444992314946E-18, -605.9228804994912, 7.490776311300133E-19, 3.2199865065666217E-18, 0.02320244804977836, -447.5021271778794, -4.6952505169757754E-18, -0.02320244804977833, 1.9319919039399733E-18, 571.2793363556355);
		frames[1] = 120;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(0.17151222098230323, 3.1331108996394374E-17, -1.9973699540678216E-32, -4520.006457722943, 0.0, 0.17151222098230323, -5.2364624603266555E-17, -4060.0325421334887, 3.1331108996394374E-17, 6.188546544022405E-17, 0.17151222098230354, -4028.649264587012);
		frames[2] = 120;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(0.2534016645666567, 4.629031754667135E-17, -2.951025111898213E-32, -6655.667741066395, 0.0, 0.2534016645666567, -7.736639968206688E-17, -5980.612602193781, 4.629031754667135E-17, 9.143301780607894E-17, 0.2534016645666571, -8186.6850654012);
		frames[3] = 120;
		accel[3] = 0;

		transforms[4] = new AffineTransform3D();
		transforms[4].set(3.162850840607295E-18, 4.805607016324359E-19, 0.01731402397440611, -412.36673397811455, 1.5814254203036492E-18, 0.017314023974406092, 4.80560701632444E-19, -412.571500173239, -0.017314023974406092, -1.5814254203036474E-18, 1.9233260916607292E-33, 365.46085575186527);
		frames[4] = 120;
		accel[4] = 0;

		VNCMovie.recordMovie(
				bdv.getBdvHandle().getViewerPanel(),
				screenWidth,
				screenHeight,
				transforms,
				viewerScale,
				viewerTranslation,
				frames,
				accel,
				1,
				outDir);


		return null;
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		new CommandLine(new BR07mMovie()).execute(args);
	}

}
