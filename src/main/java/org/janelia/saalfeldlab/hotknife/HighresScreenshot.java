package org.janelia.saalfeldlab.hotknife;

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.janelia.saalfeldlab.hotknife.ops.CLLCN;
import org.janelia.saalfeldlab.hotknife.ops.ImageJStackOp;
import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.ispim.bdv.BDVFlyThrough;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.io.InputTriggerDescription;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

import bdv.export.ProgressWriterNull;
import bdv.tools.RecordMovieDialog;
import bdv.util.Bdv;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.DefaultVoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.view.Views;

public class HighresScreenshot
{
	public static boolean defaultScalebar = false;
	public static boolean defaultBoxes = false;
	public static int defaultWidth = 0;

	@SuppressWarnings("serial")
	public static class Options implements Serializable {

		@Option(name = "-i", aliases = {"--n5Path"}, required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "-d", aliases = {"--n5Dataset" }, required = true, usage = "N5 dataset, e.g. /Sec26")
		private String n5Dataset = null;

		private boolean parsedSuccessfully = false;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				parsedSuccessfully = true;
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		public boolean isParsedSuccessfully() {
			return parsedSuccessfully;
		}

		/**
		 * @return the n5Path
		 */
		public String getN5Path() {
			return n5Path;
		}

		/**
		 * @return the datasetName
		 */
		public String getDatasetName() {
			return n5Dataset;
		}

		/**
		 * @param parsedSuccessfully the parsedSuccessfully to set
		 */
		public void setParsedSuccessfully(final boolean parsedSuccessfully) {
			this.parsedSuccessfully = parsedSuccessfully;
		}
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final boolean useVolatile = false; // otherwise screenshots don't work
		final boolean normalizeContrast = true;

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final N5Reader n5 = new N5FSReader(options.getN5Path());
		final String group = options.getDatasetName();

		final SharedQueue queue = new SharedQueue(Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));

		final int numScales = n5.list(group).length;

		System.out.println(numScales);

		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = (RandomAccessibleInterval<UnsignedByteType>[])new RandomAccessibleInterval[numScales];
		final double[][] scales = new double[numScales][];

		for (int s = 0; s < numScales; ++s) {

			final int scale = 1 << s;
			final double inverseScale = 1.0 / scale;

			final RandomAccessibleInterval<UnsignedByteType> source;

			if ( normalizeContrast )
			{
				final RandomAccessibleInterval<UnsignedByteType> sourceRaw = N5Utils.open(n5, group + "/s" + s);

				final int blockRadius = (int)Math.round(511 * inverseScale); //1023

				final ImageJStackOp<UnsignedByteType> cllcn =
						new ImageJStackOp<>(
								Views.extendZero(sourceRaw),
								(fp) -> new CLLCN(fp).run(blockRadius, blockRadius, 2f, 3, 0.5f, true, true, true),
								blockRadius,
								0,
								255);

				source = Lazy.process(
						sourceRaw,
						new int[] {128, 128, 16},
						new UnsignedByteType(),
						AccessFlags.setOf(AccessFlags.VOLATILE),
						cllcn);
			}
			else
			{
				//source = N5Utils.open(n5, datasetName + "/s" + s);
				source = N5Utils.openVolatile(n5, group + "/s" + s);
			}

			//final RandomAccessibleInterval<UnsignedByteType> source = N5Utils.openVolatile(n5, group + "/s" + s);

			mipmaps[s] = source;
			scales[s] = new double[]{scale, scale, scale};
		}

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
				new RandomAccessibleIntervalMipmapSource<>(
						mipmaps,
						new UnsignedByteType(),
						scales,
						new DefaultVoxelDimensions( 3 ),
						new AffineTransform3D(),
						group);

		final BdvOptions bdvOptions = Bdv.options()./*screenScales(new double[] {1, 0.5}).*/numRenderingThreads(Math.max(3, Runtime.getRuntime().availableProcessors() / 5));//.addTo( bdv );
		//final BdvOptions bdvOptions = Bdv.options().numRenderingThreads(Math.max(3, Runtime.getRuntime().availableProcessors() / 5));

		final Source<?> volatileMipmapSource;
		if (useVolatile)
			volatileMipmapSource = mipmapSource.asVolatile(queue);
		else
			volatileMipmapSource = mipmapSource;

		final BdvStackSource<?> bdv = Show.mipmapSource(volatileMipmapSource, null, bdvOptions);

		final ActionMap ksActionMap = new ActionMap();
		final InputMap ksInputMap = new InputMap();

		// default input trigger config, disables "control button1" drag in bdv
		// (collides with default of "move annotation")
		final InputTriggerConfig config = new InputTriggerConfig(
				Arrays.asList(
						new InputTriggerDescription[]{
								new InputTriggerDescription(
										new String[]{"not mapped"}, "drag rotate slow", "bdv")}));

		final KeyStrokeAdder ksKeyStrokeAdder = config.keyStrokeAdder(ksInputMap, "persistence");

		new AbstractNamedAction( "Record movie" )
		{
			private static final long serialVersionUID = 3640052275162419689L;

			@Override
			public void actionPerformed(ActionEvent e)
			{
				/*
				new Thread( ()-> {
				RecordMovieDialog r = new RecordMovieDialog(null, bdv.getBdvHandle().getViewerPanel(), new ProgressWriterNull());
				try {
					r.recordMovie(2000, 2000, 0, 0, new File("/Users/preibischs/workspace/n5-utils/record/") );
					System.out.println( "Done.");
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} } ).start();*/
				new Thread( ()-> BDVFlyThrough.renderScreenshot( bdv.getBdvHandle().getViewerPanel() ) ).start();
			}

			public void register() {
				put(ksActionMap);
				ksKeyStrokeAdder.put(name(), "ctrl R" );
			}
		}.register();

		bdv.getBdvHandle().getKeybindings().addActionMap("persistence", ksActionMap);
		bdv.getBdvHandle().getKeybindings().addInputMap("persistence", ksInputMap);
	}
}