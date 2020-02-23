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
package org.janelia.saalfeldlab.hotknife.tools;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.hotknife.HeightFieldTransform;
import org.janelia.saalfeldlab.hotknife.util.Grid;
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

import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Interpolation;
import bdv.viewer.state.ViewerState;
import ij.ImageJ;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellCursor;
import net.imglib2.img.cell.CellImg;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.cell.CellLocalizingCursor;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class PaintDeformationField implements Callable<Void>{

	@Option(names = {"-i", "--n5Path"}, required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"-j", "--n5FieldPath"}, required = false, description = "N5 input and output path for position field, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5FieldPath = null;

	@Option(names = {"-d1", "--n5Raw1"}, required = true, description = "N5 input group for input raw 1 (moving), e.g. /align/slab-17/bot/face")
	private String rawGroup1 = null;

	@Option(names = {"-d2", "--n5Raw2"}, required = true, description = "N5 input group for input raw 2, e.g. /align/slab-18/top/face")
	private String rawGroup2 = null;

	@Option(names = {"-f1", "--n5Field1"}, required = true, description = "N5 field dataset (moving), e.g. /align/align-9/align.slab-17.bot.face")
	private String fieldGroup1 = null;

	@Option(names = {"-f2", "--n5Field2"}, required = true, description = "N5 field dataset, e.g. /align/align-9/align.slab-18.top.face")
	private String fieldGroup2 = null;

	@Option(names = {"-g", "--n5FieldOutput"}, required = true, description = "N5 field dataset to save (moving), overrides if the same as input, e.g. /align/align-9/align.slab-17.bot.face")
	private String fieldGroupOut = null;

	FinalVoxelDimensions voxelDimensions = new FinalVoxelDimensions("px", new double[]{1, 1, 1});

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


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new PaintDeformationField(), args);
	}

	@SuppressWarnings("unchecked")
	private static Pair<double[][], RandomAccessibleInterval<UnsignedByteType>[]> openMipmaps(
			final N5Reader n5,
			final String rawGroup) throws IOException {

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

		return new ValuePair<>(scales, rawMipmaps);
	}

	private static RandomAccessibleInterval<FloatType> loadScaledPositionFieldToDeformationField(
			final N5Reader n5,
			final String datasetName) {

		final double[] boundsMin = n5.getAttribute(datasetName, "boundsMin", double[].class);
		final double transformScale = n5.getAttribute(datasetName, "scale", double.class);
		final int[] blockSize = n5.getAttribute(datasetName, "blockSize", int[].class);

		final CachedCellImg<DoubleType, ?> positionField = N5Utils.open(n5, datasetName);
		final long[] translation = Grid.floorScaled(boundsMin, transformScale);

		final CellImg<FloatType, ?> deformationField = new CellImgFactory<>(new FloatType(), blockSize).create(positionField);

		final CellCursor<DoubleType, ?> positionFieldCursor = positionField.cursor();
		final CellLocalizingCursor<FloatType, ?> deformationFieldCursor = deformationField.localizingCursor();

		while (positionFieldCursor.hasNext()) {

			final DoubleType position = positionFieldCursor.next();
			final FloatType offset = deformationFieldCursor.next();

			final int d = deformationFieldCursor.getIntPosition(deformationFieldCursor.numDimensions() - 1);
			offset.setReal(position.getRealDouble() - deformationFieldCursor.getDoublePosition(d) + translation[d]);
		}

		return deformationField;
	}


	@SuppressWarnings("unchecked")
	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException {

		new ImageJ();

		final N5Reader n5 = new N5FSReader(n5Path);
		final N5FSReader n5Field = new N5FSReader(n5FieldPath);

		/*
		 * raw data
		 */
		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.min(12, Math.max(1, numProc - 2)));

		final Pair<double[][], RandomAccessibleInterval<UnsignedByteType>[]> mipmaps1 = openMipmaps(n5, rawGroup1);
		final Pair<double[][], RandomAccessibleInterval<UnsignedByteType>[]> mipmaps2 = openMipmaps(n5, rawGroup2);

		final BdvOptions options =
				BdvOptions.options()
				.screenScales(new double[] {0.5})
				.numRenderingThreads(8);

		BdvStackSource<?> bdv = null;

		/* warp fields */
		final RealTransform transform1 = Transform.loadScaledTransform(n5Field, fieldGroup1);

		final RandomAccessibleInterval<DoubleType> field2 = N5Utils.open(n5Field, fieldGroup2);
		final ArrayImg<FloatType, ?> deformationField2 = new ArrayImgFactory<>(new FloatType()).create(heightFieldSource);
		System.out.print("Loading height field ... ");
		Util.copy(heightFieldSource, heightField);
		System.out.println("done.");

		final double avg = n5Field.getAttribute(fieldGroup, "avg", double.class);
		final double min = (avg + 0.5) * downsamplingFactors[2] - 0.5;

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
						voxelDimensions,
						"surface",
						offset,
						queue);


		bdv = Show.mipmapSource(mipmapSource, bdv, options.addTo(bdv));

		final InputTriggerConfig config = getInputTriggerConfig();
		final TriggerBehaviourBindings bindings = bdv.getBdvHandle().getTriggerbindings();

		final HeightFieldBrushController brushController = new HeightFieldBrushController(
				bdv.getBdvHandle().getViewerPanel(),
				heightField,
				Transform.createTopLeftScaleShift(
						new double[] {
								downsamplingFactors[0],
								downsamplingFactors[1]}),
				config);

		final HeightFieldSmoothController smoothController = new HeightFieldSmoothController(
				bdv.getBdvHandle().getViewerPanel(),
				heightField,
				Transform.createTopLeftScaleShift(
						new double[] {
								downsamplingFactors[0],
								downsamplingFactors[1]}),
				config);

		new HeightFieldKeyActions(
				bdv.getBdvHandle().getViewerPanel(),
				heightField,
				avg,
				n5Path,
				fieldGroupOut,
				config,
				bdv.getBdvHandle().getKeybindings());

		bindings.addBehaviourMap("brush", brushController.getBehaviourMap());
		bindings.addInputTriggerMap("brush", brushController.getInputTriggerMap());
		bdv.getBdvHandle().getViewerPanel().getDisplay().addOverlayRenderer(brushController.getBrushOverlay());

		bindings.addBehaviourMap("smooth", smoothController.getBehaviourMap());
		bindings.addInputTriggerMap("smooth", smoothController.getInputTriggerMap());
		bdv.getBdvHandle().getViewerPanel().getDisplay().addOverlayRenderer(smoothController.getBrushOverlay());

		bdv.getBdvHandle().getViewerPanel().setInterpolation(Interpolation.NLINEAR);
		final ViewerState viewerState = bdv.getBdvHandle().getViewerPanel().getState();
		final AffineTransform3D transform = new AffineTransform3D();
		viewerState.getViewerTransform(transform);
		transform.set(0, 3, 4);
		viewerState.setViewerTransform(transform);
		bdv.getBdvHandle().getViewerPanel().transformChanged(transform);
		bdv.getBdvHandle().getViewerPanel().requestRepaint();

		return null;
	}
}
