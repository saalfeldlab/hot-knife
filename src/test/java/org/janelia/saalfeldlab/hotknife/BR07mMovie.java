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

	private final String outDir = "/groups/scicompsoft/home/preibischs/recording";
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
		final AffineTransform3D[] transforms = new AffineTransform3D[8];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(0.0, 0.0, 0.012848596214828574, -257.6377002671651, 0.0, 0.012848596214828574, 0.0, -307.21828157098076, -0.012848596214828574, 0.0, 0.0, 386.4310868671157);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(0.0, 0.0, 0.4829216128262429, -16692.270306266644, 0.0, 0.4829216128262429, 0.0, -10325.44446965581, -0.4829216128262429, 0.0, 0.0, 11764.251911827614);
		frames[1] = 120;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(0.2006639048112215, 2.4733735874150492E-33, 0.0, -4151.009430502303, -7.420120762245143E-33, 0.2006639048112215, 4.455633746652177E-17, -5127.133702433492, 0.0, -4.455633746652177E-17, 0.2006639048112215, -7034.045963433674);
		frames[2] = 200;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(0.1572254200853281, 1.937952925191653E-33, 0.0, -3539.7791608501766, -5.8138587755749556E-33, 0.1572254200853281, 3.491105628701876E-17, -4149.398621333599, 0.0, -3.491105628701876E-17, 0.1572254200853281, -5911.359068492123);
		frames[3] = 60;
		accel[3] = 0;

		transforms[4] = new AffineTransform3D();
		transforms[4].set(0.09652276918389616, 0.0, 0.0, -2076.0588471641345, 0.0, 0.09652276918389616, 0.0, -1526.1914218051177, 0.0, 0.0, 0.09652276918389616, -1972.0281265146218);
		frames[4] = 120;
		accel[4] = 0;
		
		transforms[5] = new AffineTransform3D();
		transforms[5].set(0.09652276918389616, 1.189734985513236E-33, 0.0, -2076.0588471641345, -3.569204956539712E-33, 0.09652276918389616, 2.143236014970821E-17, -1526.1914218051181, 0.0, -2.143236014970821E-17, 0.09652276918389616, -2340.8529250986057);
		frames[5] = 120;
		accel[5] = 0;

		transforms[6] = new AffineTransform3D();
		transforms[6].set(0.015115983473378208, 1.8631888134554667E-34, 0.0, -244.69776277305635, -5.589566440366406E-34, 0.015115983473378208, 3.3564225783995666E-18, -349.5248293613666, 0.0, -3.3564225783995666E-18, 0.015115983473378208, -366.5901261285309);
		frames[6] = 30;
		accel[6] = 0;

		transforms[7] = new AffineTransform3D();
		transforms[7].set(0.0, 0.0, 0.012848596214828574, -257.6377002671651, 0.0, 0.012848596214828574, 0.0, -307.21828157098076, -0.012848596214828574, 0.0, 0.0, 386.4310868671157);
		frames[7] = 60;
		accel[7] = 0;

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
