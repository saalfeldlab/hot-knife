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
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.janelia.saalfeldlab.hotknife.VNCMovie.Normalization;
import org.janelia.saalfeldlab.hotknife.ViewAlignedSlabSeries.Options;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

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
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform3D;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command
public class VirtualFlyMultiSEMMovie
{

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		/*
		--n5Path '/nrs/hess/render/export/hess.n5/'
		-j '/surface-align/run_20230329_100000/pass00'
		-i '/flat/cut_030_slab_026/raw'
		--invert
		-t 20
		-b -21
		-i '/flat/cut_031_slab_006/raw'
		-t 20
		-b -21
		-i '/flat/cut_032_slab_013/raw'
		-t 20
		-b -21
		-i '/flat/cut_033_slab_033/raw'
		-t 20
		-b -21
		-i '/flat/cut_034_slab_020/raw'
		-t 20
		-b -21
		-i '/flat/cut_035_slab_001/raw'
		-t 20
		-b -21
		-i '/flat/cut_036_slab_045/raw'
		-t 20
		-b -21
		*/

		//@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		//private String n5Path = null;

		@Option(name = "--n5PathTransforms", required = true, usage = "N5 base path for the transforms, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5PathTransforms = null;

		@Option(name = "--n5PathFlatVolumes", required = true, usage = "N5 base path for the flattened volumes, e.g. gs://janelia-spark-test/hess_wafers_60_61_export/")
		private String n5PathFlatVolumes = null;

		@Option(name = "-i", aliases = {"--n5FlatVolumeDataset"}, required = true, usage = "N5 datasets of flattened volumes, e.g. /nrs/flyem/data/tmp/Z0115-22.n5/slab-22/raw")
		private List<String> datasets = new ArrayList<>();

		@Option(name = "-t", aliases = {"--top"}, required = true, usage = "top slab face offset")
		private List<Long> topOffsets = new ArrayList<>();

		@Option(name = "-b", aliases = {"--bot"}, required = true, usage = "bottom slab face offset")
		private List<Long> botOffsets = new ArrayList<>();

		@Option(name = "-j", aliases = {"--n5TransformGroup"}, required = true, usage = "N5 group containing alignments, e.g. /nrs/flyem/data/tmp/Z0115-22.n5/align-6")
		private String n5GroupAlign;

		@Option(name = "-n", aliases = {"--normalizeContrast"}, required = false, usage = "optionally normalize contrast")
		private boolean normalizeContrast;

		@Option(name = "--invert", required = false, usage = "invert intensities")
		private boolean invert;

		@Option(name = "--zoom", usage = "optionally zoom starting view in or out")
		private int zoom = 0;

		@Option(name = "--multiSem", usage = "MultiSem datasets have a different scaling in z than xy")
		private boolean multiSem = false;

		@Option(name = "--slabFrom", usage = "slab index to start with, inclusive (default: 0)")
		private Integer slabFrom = null;

		@Option(name = "--slabTo", usage = "slab index to end with, exclusive (default: datasetNames.size() as defined in the N5)")
		private Integer slabTo = null;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				parsedSuccessfully = datasets.size() == topOffsets.size() && datasets.size() == botOffsets.size();
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		public String getN5PathTransforms() { return n5PathTransforms; }
		public String getN5PathFlatVolumes() { return n5PathFlatVolumes; }

		public Integer slabFrom() { return slabFrom; }
		public Integer slabTo() { return slabTo; }

		/**
		 * @return the datasets
		 */
		public List<String> getDatasets() {

			return datasets;
		}

		/**
		 * @return the top offsets
		 */
		public List<Long> getTopOffsets() {

			return topOffsets;
		}

		/**
		 * @return the bottom offsets (max)
		 */
		public List<Long> getBotOffsets() {

			return botOffsets;
		}

		/**
		 * @return the group
		 */
		public String getGroupAlign() {

			return n5GroupAlign;
		}

		/**
		 * @return whether to normalize contrast
		 */
		public boolean normalizeContrast() {
			return normalizeContrast;
		}

		/**
		 * @return whether to invert intensities
		 */
		public boolean invert() {
			return invert;
		}

		public boolean multiSem() {
			return multiSem;
		}
	}
	/* some parameters */
	private final int screenWidth = 1280;
	private final int screenHeight = 720;
	private final String outDir = "/groups/scicompsoft/home/preibischs/recordHarald";
	//private final String n5Path = "/nrs/hess/render/export/hess.n5/";
	//private final String n5Group = "/wafer-52-align/run_20230404_105038/pass12";//"/wafer-52-align/run_20230329_104500/pass12";

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

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;
		
		new VirtualFlyMultiSEMMovie().call( options );
	}

	public final Void call( final Options options ) throws IOException, InterruptedException, ExecutionException {

		final boolean useVolatile = false;

		final BdvStackSource<?> bdv = ViewAlignedSlabSeries.run(
				options.getN5PathTransforms(),
				options.getGroupAlign(),
				options.getN5PathFlatVolumes(),
				options.getDatasets(),
				options.getTopOffsets(),
				options.getBotOffsets(),
				options.slabFrom(),
				options.slabTo(),
				new FinalVoxelDimensions("px", new double[]{1, 1, 1}),
				options.normalizeContrast(),
				options.invert(),
				options.multiSem(),
				options.zoom,
				useVolatile);
		//final RandomAccessibleIntervalMipmapSource<?> mipmapSource = VNCMovie.createMipmapSource( n5Path, n5Group, Normalization.CLLCN, true, true );
		//final RandomAccessibleIntervalMipmapSource<?> mipmapSource = null;
		//BdvFunctions.show((Source)mipmapSource.asVolatile(new SharedQueue(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))));
		
		//final BdvStackSource<?> bdv = BdvFunctions.show(mipmapSource, BdvOptions.options().numRenderingThreads((Runtime.getRuntime().availableProcessors() - 1)));

		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		bdv.getBdvHandle().getViewerPanel().setCanvasSize(screenWidth, screenHeight);
		final Window frame = SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel());
		frame.setSize(screenWidth, screenHeight);

		System.out.println( "done loading slabs ... ");
		//SimpleMultiThreading.threadHaltUnClean();

		Thread.sleep(10000);

		System.out.println( "starting ... ");

		/* animate */
		final AffineTransform3D[] transforms = new AffineTransform3D[12];
		final int[] frames = new int[transforms.length];
		final int[] accel  = new int[transforms.length];

		transforms[0] = new AffineTransform3D();
		transforms[0].set(-0.002860963851801902, 0.007860433580701645, -2.747290135722542E-34, -492.52804606247605, -0.007860433580701645, -0.002860963851801902, 1.3468180525288064E-33, 1050.0149387629901, -3.905440429682885E-34, 7.078610778800228E-34, 0.008364898698605936, -0.04838887981052245);
		frames[0] = 0;
		accel[0] = 0;

		transforms[1] = new AffineTransform3D();
		transforms[1].set(-0.002860963851801902, 0.007860433580701645, -2.747290135722542E-34, -492.52804606247605, -0.007860433580701645, -0.002860963851801902, 1.3468180525288064E-33, 1050.0149387629901, -3.905440429682885E-34, 7.078610778800228E-34, 0.008364898698605936, -0.04838887981052245);
		frames[1] = 0;
		accel[1] = 0;

		transforms[2] = new AffineTransform3D();
		transforms[2].set(-0.002860963851801899, 0.007860433580701643, 5.141700795673329E-34, -492.5280460624759, -0.007860433580701643, -0.002860963851801899, 2.3677798563302878E-34, 1050.0149387629897, 3.983550501712986E-34, -4.021789890157545E-34, 0.008364898698605931, -59.24838887981093);
		frames[2] = 180;
		accel[2] = 0;

		transforms[3] = new AffineTransform3D();
		transforms[3].set(-0.8098972307136137, -0.2947784847943822, 1.3876896227406526E-31, 118432.57863816769, 0.2947784847943822, -0.8098972307136137, 2.830661502377206E-32, 60235.08486367547, -4.0239579105187385E-32, 7.293423712815221E-32, 0.8618746309152232, -3853.1041044316057);
		frames[3] = 180;
		accel[3] = 0;

		transforms[4] = new AffineTransform3D();
		transforms[4].set(-0.8098972307136137, -0.2947784847943822, 1.3876896227406526E-31, 118432.57863816769, 0.2947784847943822, -0.8098972307136137, 2.830661502377206E-32, 60235.08486367547, -4.0239579105187385E-32, 7.293423712815221E-32, 0.8618746309152232, -4035.1041044316057);
		frames[4] = 120;
		accel[4] = 0;

		transforms[5] = new AffineTransform3D();
		transforms[5].set(0.8618746309152235, -4.048663467157277E-48, -4.78436529791196E-17, -90470.33147924114, 4.495832765592028E-17, 1.636349304914206E-17, 0.8618746309152234, -4021.104104431613, -5.551115123125783E-17, -0.8618746309152234, 7.406129762339623E-32, 97056.30426588998);
		frames[5] = 120;
		accel[5] = 0;

		transforms[6] = new AffineTransform3D();
		transforms[6].set(0.8618746309152235, -4.048663467157277E-48, -4.78436529791196E-17, -90470.33147924114, 4.495832765592028E-17, 1.636349304914206E-17, 0.8618746309152234, -4021.104104431613, -5.551115123125783E-17, -0.8618746309152234, 7.406129762339623E-32, 94508.30426588998);
		frames[6] = 240;
		accel[6] = 0;

		transforms[7] = new AffineTransform3D();
		transforms[7].set(0.24239425463357236, 7.400262977873119E-18, -8.211733992340265E-18, -24979.349313052386, 8.211733992340265E-18, 0.2423942546335723, 8.073350475931348E-17, -26401.584950640678, 7.400262977873116E-18, -7.61314239478195E-17, 0.2423942546335723, -1156.7724212572243);
		frames[7] = 60;
		accel[7] = 0;

		transforms[8] = new AffineTransform3D();
		transforms[8].set(0.2545139673652509, 7.770276126766774E-18, -8.622320691957272E-18, -26135.825991468806, 8.622320691957272E-18, 0.2545139673652507, 8.477017999727907E-17, -14558.211487379032, 7.770276126766766E-18, -7.993799514521039E-17, 0.2545139673652507, -1214.6110423200853);
		frames[8] = 240;
		accel[8] = 0;

		transforms[9] = new AffineTransform3D();
		transforms[9].set(0.2545139673652509, 7.770276126766774E-18, -8.622320691957272E-18, -26135.825991468806, 8.622320691957272E-18, 0.2545139673652507, 8.477017999727907E-17, -14558.211487379032, 7.770276126766766E-18, -7.993799514521039E-17, 0.2545139673652507, -1214.6110423200853);
		frames[9] = 120;
		accel[9] = 0;

		transforms[10] = new AffineTransform3D();
		transforms[10].set(-0.002860963851801902, 0.007860433580701645, -2.747290135722542E-34, -492.52804606247605, -0.007860433580701645, -0.002860963851801902, 1.3468180525288064E-33, 1050.0149387629901, -3.905440429682885E-34, 7.078610778800228E-34, 0.008364898698605936, -0.04838887981052245);
		frames[10] = 180;
		accel[10] = 0;

		transforms[11] = new AffineTransform3D();
		transforms[11].set(-0.002860963851801902, 0.007860433580701645, -2.747290135722542E-34, -492.52804606247605, -0.007860433580701645, -0.002860963851801902, 1.3468180525288064E-33, 1050.0149387629901, -3.905440429682885E-34, 7.078610778800228E-34, 0.008364898698605936, -0.04838887981052245);
		frames[11] = 3;
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
