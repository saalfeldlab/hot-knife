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

import org.janelia.saalfeldlab.hotknife.ops.CLLCN;
import org.janelia.saalfeldlab.hotknife.ops.ImageJStackOp;
import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.cache.CacheControl;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.viewer.Interpolation;
import bdv.viewer.ViewerPanel;
import bdv.viewer.ViewerState;
import bdv.viewer.animate.SimilarityTransformAnimator;
import bdv.viewer.overlay.MultiBoxOverlayRenderer;
import bdv.viewer.overlay.ScaleBarOverlayRenderer;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.render.PainterThread;
import bdv.viewer.render.RenderTarget;
import bdv.viewer.render.awt.BufferedImageRenderResult;
import ij.process.ColorProcessor;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command
public class VNCMovie implements Callable<Void> {

	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/home/saalfelds/record";
	private final String n5Path = "/nrs/flyem/tmp/VNC-export-v3.n5";
	private final String n5Group = "/2-26";
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

	public static class Target implements RenderTarget<BufferedImageRenderResult> {

		public BufferedImageRenderResult renderResult = new BufferedImageRenderResult();

		private final int width;
		private final int height;

		Target(final int width, final int height) {
			this.width = width;
			this.height = height;
		}

		@Override
		public BufferedImageRenderResult getReusableRenderResult() {
			return renderResult;
		}

		@Override
		public BufferedImageRenderResult createRenderResult() {
			return new BufferedImageRenderResult();
		}

		@Override
		public void setRenderResult(final BufferedImageRenderResult renderResult) {}

		@Override
		public int getWidth() {
			return width;
		}

		@Override
		public int getHeight() {
			return height;
		}
	}

	/**
	 * Cosine shape of linear [0,1]
	 */
	protected static double cos(final double x) {

		return 0.5 - 0.5 * Math.cos(Math.PI * x);
	}

	/**
	 * Acceleration function for a t in [0,1]:
	 *
	 * types
	 *   0  symmetric
	 *   1  slow start
	 *   2  slow end
	 *   3  soft symmetric
	 *   4  soft slow start
	 *   5  soft slow end
	 */
	protected static double accel(final double t, final int type) {

		switch (type) {
		case 1:		// slow start
			return cos(t * t);
		case 2:		// slow end
			return 1.0 - cos(Math.pow(1.0 - t, 2));
		case 3:		// soft symmetric
			return cos(cos(t));
		case 4:		// soft slow start
			return cos(cos(t * t));
		case 5:		// soft slow end
			return 1.0 - cos(cos(Math.pow(1.0 - t, 2)));
		default:	// symmetric
			return cos(t);
		}
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

		final Target target = new Target(width, height);

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
				final AffineTransform3D tkd = animator.get(accel((double)d / (double)frames[k], accel[k]));
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

		new CommandLine(new VNCMovie()).execute(args);
	}

	public static RandomAccessibleIntervalMipmapSource<UnsignedByteType> createMipmapSource(
			final String n5Path,
			final String n5Group ) throws IOException
	{
		return createMipmapSource(n5Path, n5Group, false, false, false );
	}

	public static RandomAccessibleIntervalMipmapSource<UnsignedByteType> createMipmapSource(
			final String n5Path,
			final String n5Group,
			final boolean normalizeContrast,
			final boolean invert,
			final boolean mSem ) throws IOException {

		final N5Reader n5 = new N5FSReader(n5Path);

		final int numScales = 7;
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = (RandomAccessibleInterval<UnsignedByteType>[])new RandomAccessibleInterval[numScales];
		final double[][] scales = new double[numScales][3];

		for (int scaleIndex = 0; scaleIndex < numScales; ++scaleIndex) {

			final int scale = 1 << scaleIndex;
			final double inverseScale = 1.0 / scale;
			RandomAccessibleInterval<UnsignedByteType> img = N5Utils.openVolatile(n5, n5Group + "/s" + scaleIndex);

			if ( invert )
			{
				img = Converters.convertRAI( img, (in,out) -> { if (in.get() == 0) { out.set( 0 ); } else { out.set( 255 - in.get() );} }, new UnsignedByteType() );
			}

			if ( normalizeContrast )
			{
				final int blockRadius = (int)Math.round(511 * inverseScale);
	
				final ImageJStackOp<UnsignedByteType> cllcn =
						new ImageJStackOp<>(
								Views.extendZero(img),
								(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 3f, 10, 0.5f, true, true, true),
								blockRadius,
								0,
								255);
				final RandomAccessibleInterval<UnsignedByteType> cllcned = Lazy.process(
						img,
						new int[] {128, 128, 16},
						new UnsignedByteType(),
						AccessFlags.setOf(AccessFlags.VOLATILE),
						cllcn);
				mipmaps[scaleIndex] = cllcned;
			}
			else
			{
				mipmaps[scaleIndex] = img;
			}

			// TODO: read the downsamplings rather than assuming stuff
			scales[scaleIndex] = new double[]{scale, scale, mSem ? 1 : scale};
		}

		final RandomAccessibleIntervalMipmapSource<UnsignedByteType> mipmapSource =
				new RandomAccessibleIntervalMipmapSource<>(
						mipmaps,
						new UnsignedByteType(),
						scales,
						new FinalVoxelDimensions("um", new double[]{0.008, 0.008, 0.008}),
						"VNC");

		return mipmapSource;
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource = createMipmapSource( n5Path, n5Group );

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
		final AffineTransform3D[] transforms = new AffineTransform3D[14];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(3.205689255981331E-4, 0.005954925593038874, 0.011687199528399559, -632.8995892966425, -0.0036154940367559957, 0.01127792679169835, -0.00564722110622439, -0.759428258980563, -0.012608726125403544, -0.00308249323337054, 0.0019164542864629447, 295.710839505524);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(3.205689255981331E-4, 0.005954925593038874, 0.011687199528399559, -632.8995892966425, -0.0036154940367559957, 0.01127792679169835, -0.00564722110622439, -0.759428258980563, -0.012608726125403544, -0.00308249323337054, 0.0019164542864629447, 555.7108395055241);
		frames[1] = 120;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(3.205689255981331E-4, 0.005954925593038874, 0.011687199528399559, -632.8995892966425, -0.0036154940367559957, 0.01127792679169835, -0.00564722110622439, -0.759428258980563, -0.012608726125403544, -0.00308249323337054, 0.0019164542864629447, 75.71083950552406);
		frames[2] = 240;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(3.205689255981456E-4, 0.0059549255930388565, 0.011687199528399543, -632.8995892966416, -0.012608726125403527, -0.003082493233370528, 0.0019164542864629568, 295.7108395055235, 0.0036154940367559988, -0.011277926791698303, 0.005647221106224375, 230.75942825898073);
		frames[3] = 180;
		accel[3] = 3;

		transforms[4] = new AffineTransform3D();
		transforms[4].set(3.205689255981456E-4, 0.0059549255930388565, 0.011687199528399543, -632.8995892966416, -0.012608726125403527, -0.003082493233370528, 0.0019164542864629568, 295.7108395055235, 0.0036154940367559988, -0.011277926791698303, 0.005647221106224375, -179.24057174101927);
		frames[4] = 240;
		accel[4] = 0;

		transforms[5] = new AffineTransform3D();
		transforms[5].set(0.019311094248977183, 0.3587251295747136, 0.7040377078920287, -38125.915031351644, -0.759550533829473, -0.18568960556730266, 0.11544733876088775, 17813.641423541798, 0.21779761082624102, -0.6793830899273354, 0.3401889900077849, 45.74801084399742);
		frames[5] = 480;
		accel[5] = 3;

		transforms[6] = new AffineTransform3D();
		transforms[6].set(0.002612435770058029, 0.04852891027494824, 0.09524334912633023, -5124.0108529147265, -0.10275321316128334, -0.025120387351311363, 0.015617900956230078, 2409.8579526280787, 0.02946400974588925, -0.09190803290666587, 0.04602131161590296, -1115.265726010176);
		frames[6] = 480;
		accel[6] = 3;

		transforms[7] = new AffineTransform3D();
		transforms[7].set(-2.211895033480208E-15, -2.284330309165575E-15, 2.097155715487181, -35014.06691782091, -2.0579552334826206E-17, 2.097155715487181, 2.284330309165575E-15, -25463.86077773481, -2.097155715487181, -2.0579552334823722E-17, -2.211895033480207E-15, 50824.96035364583);
		frames[7] = 480;
		accel[7] = 3;

		transforms[8] = new AffineTransform3D();
		transforms[8].set(0.6192958954651673, -4.2768680290629657E-32, -3.4377828110064176E-17, -15028.751663860457, 3.7308848764166316E-32, 0.6192958954651673, 1.2288609967644243E-31, -7499.817143398536, 3.4377828110063714E-17, -1.0972935981519313E-31, 0.6192958954651673, -10322.844953809492);
		frames[8] = 60;
		accel[8] = 3;

		transforms[9] = new AffineTransform3D();
		transforms[9].set(0.6192958954651673, -4.2768680290629657E-32, -3.4377828110064176E-17, -15028.751663860457, 3.7308848764166316E-32, 0.6192958954651673, 1.2288609967644243E-31, -7499.817143398536, 3.4377828110063714E-17, -1.0972935981519313E-31, 0.6192958954651673, -10357.844953809492);
		frames[9] = 60;
		accel[9] = 3;

		transforms[10] = new AffineTransform3D();
		transforms[10].set(0.6192958954651673, -4.2768680290629657E-32, -3.4377828110064176E-17, -15028.751663860457, 3.7308848764166316E-32, 0.6192958954651673, 1.2288609967644243E-31, -7499.817143398536, 3.4377828110063714E-17, -1.0972935981519313E-31, 0.6192958954651673, -10322.844953809492);
		frames[10] = 60;
		accel[10] = 3;

		transforms[11] = new AffineTransform3D();
		transforms[11].set(0.02264346057538643, 0.00553571497306888, -0.003441676553876729, -558.2168887335088, -0.006492907837603556, 0.02025353617328589, -0.010141597748058744, -84.30166893338213, 5.756957329615456E-4, 0.010694190797249918, 0.02098853120655918, -124.37851098566614);
		frames[11] = 480;
		accel[11] = 3;

		transforms[12] = new AffineTransform3D();
		transforms[12].set(0.12282535881319635, 0.012078550390662496, -0.009523313252987673, -2682.6420581847337, -0.015081057749620071, 0.10963820290448946, -0.05544979534201524, 197.056798178377, 0.0030243300095934987, 0.056180305506280205, 0.11026005778911437, -11362.499097287542);
		frames[12] = 960;
		accel[12] = 3;

		transforms[13] = new AffineTransform3D();
		transforms[13].set(3.205689255981331E-4, 0.005954925593038874, 0.011687199528399559, -632.8995892966425, -0.0036154940367559957, 0.01127792679169835, -0.00564722110622439, -0.759428258980563, -0.012608726125403544, -0.00308249323337054, 0.0019164542864629447, 295.710839505524);
		frames[13] = 480;
		accel[13] = 3;

		recordMovie(
				bdv.getBdvHandle().getViewerPanel(),
				screenWidth,
				screenHeight,
				transforms,
				frames,
				accel,
				5,
				outDir);

		return null;
	}
}
