/*
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
package org.janelia.saalfeldlab.hotknife.tools;

import ij.ImageJ;
import ij.ImagePlus;
import ij.plugin.filter.RankFilters;
import ij.process.FloatProcessor;

import java.awt.BorderLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import mpicbg.spim.data.sequence.FinalVoxelDimensions;

import org.janelia.saalfeldlab.hotknife.HeightFieldTransform;
import org.janelia.saalfeldlab.hotknife.ops.AbsoluteGradientCenter;
import org.janelia.saalfeldlab.hotknife.tools.actions.HeightFieldKeyActions;
import org.janelia.saalfeldlab.hotknife.tools.proofread.LocationsPanel;
import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Transform.TransformedSource;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.io.InputTriggerDescription;
import org.scijava.ui.behaviour.io.yaml.YamlConfigIO;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Interpolation;
import bdv.viewer.SynchronizedViewerState;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.cache.Cache;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.basictypeaccess.FloatAccess;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command(name = "heightfield")
public class PaintHeightField implements Callable<Void>{

	private static int[] blockSize = new int[] {32, 32};

	@Option(names = {"-i", "--n5Path"}, required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"-j", "--n5FieldPath"}, required = false, description = "N5 output path for height field, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5FieldPath = null;

	@Option(names = {"-d", "--n5Raw"}, required = true, description = "N5 input group for raw, e.g. /raw")
	private String rawGroup = null;

	@Option(names = {"-f", "--n5Field"}, required = true, description = "N5 field dataset, e.g. /surface/s1/min")
	private String fieldGroup = null;

	@Option(names = {"-g", "--n5FieldOutput"}, required = true, description = "N5 field dataset to save, overrides if the same as input, e.g. /surface/s1/min")
	private String fieldGroupOut = null;

	@Option(names = {"-s", "--scale"}, required = true,  split=",", description = "downsampling factors, e.g. 6,6,1")
	private int[] downsamplingFactors = null;

	@Option(names = {"-hfo", "--heightfieldOffset"}, required = false,  split=",", description = "offset of the heightfield (at the respective input resolution), e.g. 46,46,46")
	private int[] heightfieldOffset = null;

	@Option(names = {"-o", "--offset"}, required = true, description = "offset from the target surface, this will be at z=0, e.g. 3")
	private int offset = 0;

	@Option(names = {"--heightFieldMagnitude"}, required = false, description = "allows to adjust the magnitude (multiplicative factor) of heightfield change when moving it (default: 1.0)")
	private double heightFieldMagnitude = 1.0;

	@Option(names = {"--locationsFile"}, description = "full path for review locations JSON file, e.g. /nrs/flyem/render/n5/Z0720_07m_BR/review/Sec38/v3_acquire_trimmed_sp1_adaptive_ic___20210424_155438_gauss/min/locations.trautmane.json")
	private String locationsFilePath = null;

	FinalVoxelDimensions voxelDimensions = new FinalVoxelDimensions("px", new double[]{1, 1, 1});

	/*
	--n5Path=/nrs/flyem/render/n5/Z0720_07m_BR
	--n5FieldPath=/nrs/flyem/render/n5/Z0720_07m_BR
	--n5Raw=/z_corr/Sec25/v5_acquire_trimmed_align_ic___20210709_144057
	--n5FieldOutput=/heightfields_fix/Sec25/pass1/min
	--n5Field=/heightfields/Sec25/trn_vld_single_head_jitter_flips_12-5-3-2-2-3_s3_s2_ft_20_cosine/s1/min
	--scale=4,4,4
	--heightfieldOffset=62,62,62
	--offset=3

	--n5Path=/nrs/flyem/render/n5/Z0720_07m_BR
	--n5FieldPath=/nrs/flyem/render/n5/Z0720_07m_BR
	--n5Raw=/z_corr/Sec22/v4_acquire_trimmed_align_ic___20210825_210921
	--n5FieldOutput=/heightfields_fix/Sec22/pass1/min
	--n5Field=/heightfields/Sec22/v4_acquire_trimmed_align_ic___20210825_210921_gauss/s1/min
	--scale=6,6,1
	--offset=3
	*/

	static protected InputTriggerConfig getInputTriggerConfig() throws IllegalArgumentException {

		final String[] filenames = {"paintheightfieldkeyconfig.yaml",
				System.getProperty("user.home") + "/.bdv/paintheightfieldkeyconfig.yaml"};

		for (final String filename : filenames) {
			try {
				if (new File(filename).isFile()) {
					System.out.println("reading key config from file " + filename);
					return new InputTriggerConfig(YamlConfigIO.read(filename));
				}
			} catch (final IOException e) {
				System.err.println("Error reading " + filename);
			}
		}

		System.out.println("creating default input trigger config");

		// default input trigger config, disables "control button1" drag in bdv
		// (collides with default of "move annotation")
		final InputTriggerConfig config = new InputTriggerConfig(
				Arrays
						.asList(
								new InputTriggerDescription[]{new InputTriggerDescription(
										new String[]{"not mapped"},
										"drag rotate slow",
										"bdv")}));

		return config;
	}


	public static class FullBrain
	{
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps;
		final double[][] scales;
		final Translation3D[] offsetsAtOwnScale;

		public FullBrain( final RandomAccessibleInterval<UnsignedByteType>[] mipmaps, final double[][] scales, final Translation3D[] offsetsAtOwnScale )
		{
			this.mipmaps = mipmaps;
			this.scales = scales;
			this.offsetsAtOwnScale = offsetsAtOwnScale;
		}
	}

	public static FullBrain loadCroppedFullBrain() throws IOException
	{
		/*
		new ImageJ();
		ImagePlus imp = new ImagePlus( "/Users/preibischs/Desktop/test.tif" );
		imp.show();

		Img<FloatType> img = ImageJFunctions.wrapFloat(imp);
		
		ImageJFunctions.show(Views.rotate( img, 1, 0 )); // 90 degree counter-clockwise
		ImageJFunctions.show(Views.permute(Views.rotate( img, 1, 0 ),1,2)); // 90 degree counter-clockwise + make XZY
		
		SimpleMultiThreading.threadHaltUnClean();
		*/
		final String n5Path = "/nrs/flyem/render/n5/Z0720_07m_BR";
		final String n5Dataset = "/40-06-final";
		final Interval crop = new FinalInterval( new long[] {47204, 46557, 42756}, new long[] {55779, 59038, 53664} );

		final N5Reader n5 = new N5FSReader(n5Path);
		final int numScales = n5.list(n5Dataset).length;
		final double[][] scales = new double[numScales][];
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = new RandomAccessibleInterval[numScales];

		final Translation3D[] offsetsAtOwnScale = new Translation3D[numScales];

		for (int s = 0; s < numScales; ++s) {

			final String mipmapName = n5Dataset + "/s" + s;
			mipmaps[s] = (RandomAccessibleInterval<UnsignedByteType>)N5Utils.openVolatile(n5, mipmapName);
			double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
			scales[s] = (scale == null) ? new double[] {1, 1, 1} : scale;

			System.out.println( "scale: "+ net.imglib2.util.Util.printCoordinates( scales[s] ) );
			System.out.println( "dimensions: "+ net.imglib2.util.Util.printInterval( mipmaps[ s ]));

			final AffineTransform3D mipmapTransform = new AffineTransform3D();
			mipmapTransform.set(
					scales[s][ 0 ], 0, 0, 0.5 * ( scales[s][ 0 ] - 1 ),
					0, scales[s][ 1 ], 0, 0.5 * ( scales[s][ 1 ] - 1 ),
					0, 0, scales[s][ 2 ], 0.5 * ( scales[s][ 2 ] - 1 ) );

			double[] min = new double[] {crop.min(0), crop.min(1), crop.min(2)};
			double[] max = new double[] {crop.max(0), crop.max(1), crop.max(2)};

			mipmapTransform.applyInverse(min, min);
			mipmapTransform.applyInverse(max, max);

			System.out.println( "mipmap transform: "+ mipmapTransform );
			System.out.println( "updated interval: "+ net.imglib2.util.Util.printCoordinates( min ) + " >>> " + net.imglib2.util.Util.printCoordinates( max ) );

			final RealInterval realBB = new FinalRealInterval(min, max);
			Interval intBB = Intervals.smallestContainingInterval( realBB );

			// now let's compute how much each transform needs to be moved in local (downsampled) coordinates
			// due to the fact that the cropping was done on subpixel locations

			final double[] offset = new double[3];
			for ( int d = 0; d < offset.length; ++d )
				offset[ d ] = (intBB.realMin( d ) - realBB.realMin( d ));

			System.out.println( "offset for s" + s +": " + net.imglib2.util.Util.printCoordinates( offset ));

			mipmaps[ s ] =
					Views.permute(
							Views.zeroMin(
									Views.rotate(
											Views.interval( mipmaps[ s ], intBB ),
									1,
									0 )),
							1, 2);

			// 90 degree counter-clockwise
			double tmp = offset[ 1 ];
			offset[ 1 ] = offset[ 0 ];
			offset[ 0 ] = tmp;

			// permute YZ
			tmp = offset[ 1 ];
			offset[ 1 ] = offset[ 2 ];
			offset[ 2 ] = tmp;

			System.out.println( "transformed offset for s" + s +": " + net.imglib2.util.Util.printCoordinates( offset ));

			offsetsAtOwnScale[ s ] = new Translation3D( offset );

			System.out.println();
		}

		return new FullBrain(mipmaps, scales,offsetsAtOwnScale);
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new PaintHeightField(), args);
	}

	@SuppressWarnings("unchecked")
	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		//new ImageJ();

		final N5Reader n5 = new N5FSReader(n5Path);
		final N5FSReader n5Field = new N5FSReader(n5FieldPath);

		/*
		 * raw data
		 */
		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.min(12, Math.max(1, numProc - 2)));

		final FullBrain fb = loadCroppedFullBrain();
		final RandomAccessibleInterval<UnsignedByteType>[] rawMipmaps = fb.mipmaps;
		final double[][] scales = fb.scales;

		/*
		final int numScales = n5.list(rawGroup).length;
		final double[][] scales = new double[numScales][];
		final RandomAccessibleInterval<UnsignedByteType>[] rawMipmaps = new RandomAccessibleInterval[numScales];
		for (int s = 0; s < numScales; ++s) {

			final String mipmapName = rawGroup + "/s" + s;
			rawMipmaps[s] =Views.permute((RandomAccessibleInterval<UnsignedByteType>)N5Utils.openVolatile(n5, mipmapName), 1, 2);
			double[] scale = n5.getAttribute(mipmapName, "downsamplingFactors", double[].class);
			if (scale == null)
				scale = new double[] {1, 1, 1};

			scales[s] = scale;
		}
		*/

		final BdvOptions options =
				BdvOptions.options()
				.screenScales(new double[] {0.5})
				.numRenderingThreads(Runtime.getRuntime().availableProcessors());

		BdvStackSource<?> bdv = null;

		/*
		RandomAccessibleIntervalMipmapSource<UnsignedByteType> test = new RandomAccessibleIntervalMipmapSource<>(
				rawMipmaps,
				new UnsignedByteType(),
				scales,
				new FinalVoxelDimensions("px", 1.0, 1.0, 1.0),
				new AffineTransform3D(),
				rawGroup);

		RandomAccessibleIntervalMipmapSource<UnsignedByteType> test2 = new RandomAccessibleIntervalMipmapSource2<>(
				rawMipmaps,
				new UnsignedByteType(),
				scales,
				new FinalVoxelDimensions("px", 1.0, 1.0, 1.0),
				new AffineTransform3D(),
				fb.offsetsAtOwnScale,
				rawGroup);

		bdv = Show.mipmapSource(test2.asVolatile(queue), null, options);

		SimpleMultiThreading.threadHaltUnClean();
		*/
		/* raw */
		if ( !new File( n5FieldPath, fieldGroup ).exists() )
		{
			System.out.println( "heightfield dataset does not exist: " + n5FieldPath + "/" + fieldGroup );
			System.exit( 0 );
		}

		RandomAccessibleInterval<FloatType> heightFieldSource = N5Utils.open(n5Field, fieldGroup);

		// add offsets from DL prediction
		if ( heightfieldOffset != null )
		{
			System.out.println( "correcting heighfield offset (from DL predictions): " + net.imglib2.util.Util.printCoordinates( heightfieldOffset ) );

			final long[] min = new long[] { 0, 0 };
			final long[] max = new long[] { heightFieldSource.dimension( 0 ) + heightfieldOffset[ 0 ] * 2, heightFieldSource.dimension( 1 ) + heightfieldOffset[ 1 ] * 2  };
			heightFieldSource = Views.interval( Views.translate( Views.extendBorder( heightFieldSource ), new long[] { heightfieldOffset[ 0 ], heightfieldOffset[ 1 ] } ), min, max);

			// correct the location of the surface
			heightFieldSource = Converters.convert( heightFieldSource, (i,o) -> o.set( (i.get() + heightfieldOffset[ 2 ]) /* downsamplingFactors[ 2 ]*/), new FloatType() );
			//downsamplingFactors[ 2 ] = 1;
		}

		ArrayImg<FloatType, ?> heightField = new ArrayImgFactory<>(new FloatType()).create(heightFieldSource);

		// multi-threaded copy
		System.out.print("Loading height field " + n5FieldPath + ":/" + fieldGroup + "... " );
		Util.copy(heightFieldSource, heightField, Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() ));
		System.out.println("done.");

		//System.out.print("Loading MANUAL heightfield.");
		//float[] hf = (float[])new ImagePlus( "/Users/preibischs/Documents/Janelia/Projects/Male CNS+VNC Alignment/07m/VNC-Sec 29/heighfield_max_hybrid.tif" ).getProcessor().getPixels();
		//heightField = ArrayImgs.floats( hf, heightFieldSource.dimensionsAsLongArray() );
		//for ( final FloatType t : heightField )
		//	if ( t.get() <= 0 )
		//		t.set( 0 );
		//heightField = fix07mBRSec28HeightField( heightField );

		//System.out.print("SMOOTHING heightfield.");
		//Gauss3.gauss( 2.0, Views.extendBorder( heightField ), heightField );
		//System.out.println("done.");

		//System.out.print("MEDIAN-FILTERING heightfield.");
		//final FloatProcessor fp = new FloatProcessor( (int)heightField.dimension( 0 ), (int)heightField.dimension( 1 ), ((FloatArray)heightField.update( null )).getCurrentStorageArray() );
		//new RankFilters().rank( fp, 30, RankFilters.MEDIAN );
		//System.out.println("done.");

		System.out.print("adding offset to heightfield.");
		for ( final FloatType t : heightField )
			t.set( t.get() + 1000f );

		final double avg = n5Field.getAttribute(fieldGroup, "avg", double.class);
		//final double min = (avg + 0.5) * downsamplingFactors[2] - 0.5;

		final HeightFieldTransform<DoubleType> heightFieldTransform = new HeightFieldTransform<>(
					Transform.scaleAndShiftHeightFieldAndValues(
							heightField,
							new double[]{
									downsamplingFactors[0],
									downsamplingFactors[1],
									downsamplingFactors[2]}),
					0);

		final TransformedSource<?> mipmapSource =
				Show.createTransformedMipmapSliceSource(
						heightFieldTransform.inverse(),
						rawMipmaps,
						scales,
						fb.offsetsAtOwnScale,
						voxelDimensions,
						fieldGroup,
						offset,
						queue);

		bdv = Show.mipmapSource( mipmapSource, bdv, options.addTo(bdv) );

		System.out.println("Setting up gradients ... ");

		/* gradients */
		final AbsoluteGradientCenter<FloatType> gradientOp =
				new AbsoluteGradientCenter<>(
						Views.extendBorder( heightField ) /*,
						new double[] { 0.5, 0.5 }*/);
		final RandomAccessibleInterval<FloatType> gradient = Lazy.process(heightField, blockSize, new FloatType(), AccessFlags.setOf(), gradientOp);
		final Cache< ?, ? > gradientCache = ((CachedCellImg< ?, ? >)gradient).getCache();

		System.out.println("Copying gradients ... ");
		final ArrayImg<FloatType, ?> gradientCopy = new ArrayImgFactory<>(new FloatType()).create(gradient);
		Util.copy(gradient, gradientCopy);

		new ImageJ();
		ImageJFunctions.show( heightField ).setTitle( "heighfield");
		ImagePlus grad = ImageJFunctions.show( gradientCopy );
		grad.setTitle( "gradients");
		grad.setDisplayRange(0, 10);
		grad.updateAndDraw();
		//SimpleMultiThreading.threadHaltUnClean(); 

		final RealRandomAccessible< FloatType > gradientFull =
				RealViews.affineReal(
						Views.interpolate(
								Views.extendZero(
										gradient ),
								new NLinearInterpolatorFactory<>()),
						Transform.createTopLeftScaleShift(new double[] {downsamplingFactors[0], downsamplingFactors[1]}) );

		final RealRandomAccessible< FloatType > gradientCopyFull =
				RealViews.affineReal(
						Views.interpolate(
								Views.extendZero(
										gradientCopy ),
								new NLinearInterpolatorFactory<>()),
						Transform.createTopLeftScaleShift(new double[] {downsamplingFactors[0], downsamplingFactors[1]}) );

		final Interval gradientFullInterval = new FinalInterval(
				new long[] { rawMipmaps[ 0 ].min( 0 ), rawMipmaps[ 0 ].min( 1 ) },
				new long[] { rawMipmaps[ 0 ].max( 0 ), rawMipmaps[ 0 ].max( 1 ) } );

		bdv = BdvFunctions.show( gradientFull, gradientFullInterval, "current gradient", options.addTo( bdv ) );
		bdv.setDisplayRangeBounds( 0, 500 );
		bdv.setDisplayRange(0, 25);
		bdv.setColor( new ARGBType( ARGBType.rgba( 0, 255, 0, 0 ) ) );

		bdv = BdvFunctions.show( gradientCopyFull, gradientFullInterval, "input gradient", options.addTo( bdv ) );
		bdv.setDisplayRangeBounds( 0, 500 );
		bdv.setDisplayRange(0, 25);
		bdv.setColor( new ARGBType( ARGBType.rgba( 255, 0, 0, 0 ) ) );
		bdv.setActive( false );

		final BdvStackSource< ? > bdvGradient = bdv;

		/* Controls */
		final InputTriggerConfig config = getInputTriggerConfig();
		final TriggerBehaviourBindings bindings = bdv.getBdvHandle().getTriggerbindings();

		final HeightFieldBrushController brushController = new HeightFieldBrushController(
				bdv.getBdvHandle().getViewerPanel(),
				heightField,
				Transform.createTopLeftScaleShift(
						new double[] {
								downsamplingFactors[0],
								downsamplingFactors[1]}),
				gradientCache,
				heightFieldMagnitude,
				config);

		final HeightFieldSmoothController smoothController = new HeightFieldSmoothController(
				bdv.getBdvHandle().getViewerPanel(),
				heightField,
				Transform.createTopLeftScaleShift(
						new double[] {
								downsamplingFactors[0],
								downsamplingFactors[1]}),
				gradientCache, // just for invalidation
				config);

		final HeightFieldWeightedSmoothController weightedSmoothController = new HeightFieldWeightedSmoothController(
				bdv.getBdvHandle().getViewerPanel(),
				heightField,
				Transform.createTopLeftScaleShift(
						new double[] {
								downsamplingFactors[0],
								downsamplingFactors[1]}),
				gradientCopy,
				bdvGradient,
				gradientCache, // just for invalidation
				config);


		new HeightFieldKeyActions(
				bdv.getBdvHandle().getViewerPanel(),
				heightField,
				avg,
				downsamplingFactors,
				n5Path,
				fieldGroupOut,
				config,
				bdv.getBdvHandle().getKeybindings());

		bindings.addBehaviourMap("brush", brushController.getBehaviourMap());
		bindings.addInputTriggerMap("brush", brushController.getInputTriggerMap());
		bdv.getBdvHandle().getViewerPanel().getDisplay().overlays().add(brushController.getBrushOverlay());

		bindings.addBehaviourMap("smooth", smoothController.getBehaviourMap());
		bindings.addInputTriggerMap("smooth", smoothController.getInputTriggerMap());
		bdv.getBdvHandle().getViewerPanel().getDisplay().overlays().add(smoothController.getBrushOverlay());

		bindings.addBehaviourMap("weightedsmooth", weightedSmoothController.getBehaviourMap());
		bindings.addInputTriggerMap("weightedsmooth", weightedSmoothController.getInputTriggerMap());
		bdv.getBdvHandle().getViewerPanel().getDisplay().overlays().add(weightedSmoothController.getBrushOverlay());

		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		final SynchronizedViewerState viewerState = bdv.getBdvHandle().getViewerPanel().state();
		final AffineTransform3D transform = new AffineTransform3D();
		viewerState.getViewerTransform(transform);
		transform.set(0, 3, 4);
		viewerState.setViewerTransform(transform);

		final CardPanel cardPanel = bdv.getBdvHandle().getCardPanel();

		final JPanel heightFieldMagnitudePanel = new JPanel(new BorderLayout());
		heightFieldMagnitudePanel.add(brushController.getMagnitudePanel(), BorderLayout.CENTER);
		cardPanel.addCard("HeightFieldMagnitude",
						  "Height Field Magnitude",
						  heightFieldMagnitudePanel,
						  false,
						  new Insets(0, 4, 0, 0));

		final LocationsPanel locationsPanel = new LocationsPanel(bdv.getBdvHandle().getViewerPanel(),
																 locationsFilePath);
		cardPanel.addCard(LocationsPanel.KEY,
						  "Locations",
						  locationsPanel,
						  true,
						  new Insets(0, 4, 0, 0));
		cardPanel.setCardExpanded(BdvDefaultCards.DEFAULT_VIEWERMODES_CARD, false);
		cardPanel.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCES_CARD, false);
		cardPanel.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false);

//		bdv.getBdvHandle().getViewerPanel().transformChanged(transform);
		bdv.getBdvHandle().getViewerPanel().setCurrentViewerTransform( transform );
		bdv.getBdvHandle().getViewerPanel().requestRepaint();

		((JFrame)SwingUtilities.getWindowAncestor(bdv.getBdvHandle().getViewerPanel())).setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

		return null;
	}

	private ArrayImg<FloatType, ?> fix07mBRSec28HeightField( ArrayImg<FloatType, ?> hf )
	{
		final ArrayImg<FloatType, ?> hfNew = ArrayImgs.floats( hf.dimensionsAsLongArray() );

		final RealRandomAccess< FloatType > rra = 
				Views.interpolate( Views.extendBorder( hf ), new NLinearInterpolatorFactory<>() ).realRandomAccess();

		final Cursor< FloatType > c = hfNew.cursor();
		final double[] l = new double[ hf.numDimensions() ];

		while ( c.hasNext() )
		{
			final FloatType out = c.next();
			c.localize( l );

			// within fixed zcorr range
			if ( l[ 1 ] > 909.9370789 && l[ 1 ] < 940.6729431 )
			{
				if ( l[ 0 ] == 0 )
					System.out.print( l[ 1 ] + " >>> " + ( ( l[ 1 ] - 909.9370789 ) / ( 940.6729431 - 909.9370789 ) ) + " >>> ");

				l[ 1 ] += ( ( l[ 1 ] - 909.9370789 ) / ( 940.6729431 - 909.9370789 ) ) * 11.06491106;

				if ( l[ 0 ] == 0 )
					System.out.println( l[ 1 ] );
			}
			else if ( l[ 1 ] > 940.6729431 ) // 5644.037658 / 6.0
			{
				// fixed zcorr ends

				if ( l[ 0 ] == 0 && l[ 1 ] == 941 )
					System.out.print( l[ 1 ] + " >>> " + ( ( l[ 1 ] - 909.9370789 ) / ( 940.6729431 - 909.9370789 ) ) + " >>> ");

				// move heightfield 11.06 pixels up, = (5710.427125-5644.037658)/6.0
				l[ 1 ] += 11.06491106; 

				if ( l[ 0 ] == 0 && c.getIntPosition( 1 ) == 941 )
					System.out.println( l[ 1 ] );
			}

			rra.setPosition( l );
			out.set( rra.get() );
		}

		return hfNew;
	}
}
