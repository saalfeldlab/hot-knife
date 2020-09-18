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
package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;

import com.google.common.reflect.TypeToken;
import com.google.gson.GsonBuilder;

import loci.formats.FormatException;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel1D;
import mpicbg.models.ConstantModel;
import mpicbg.models.CoordinateTransform;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.InterpolatedModel;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TranslationModel2D;
import net.imglib2.realtransform.AffineTransform2D;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Align a 3D N5 dataset.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command(
		name = "AlignChannel",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Align a channel with matches from all camera images")
public class AlignChannel implements Callable<Void>, Serializable {

	private static final long serialVersionUID = 1030006363999084424L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--id", required = true, description = "Stack key, e.g. Pos012")
	private String id = null;

	@Option(names = "--channel", required = true, description = "Channel key, e.g. Ch488+561+647nm")
	private String channel = null;

	@Option(names = {"-d", "--distance"}, required = false, description = "max distance for two slices to be compared, e.g. 10 (default 0)")
	private int distance = 0;

	@Option(names = "--lambdaModel", required = false, description = "lambda for rigid regularizer in model")
	private double lambdaModel = 0.01;

	@Option(names = "--maxEpsilon", required = true, description = "residual threshold for filter in world pixels")
	private double maxEpsilon = 0.01;

	@Option(names = "--iterations", required = false, description = "number of iterations")
	private int numIterations = 2000;

	private static int channelLength(
			final N5Reader n5,
			final String id,
			final String channel,
			final Iterable<String> cams) throws IOException {

		int channelLength = 0;
		for (final String cam : cams) {
			final long[] dimensions = n5.getAttribute(n5.groupPath(id, channel, cam, "matches"), "dimensions", long[].class);
			if (dimensions[0] > channelLength)
				channelLength = (int)dimensions[0];
		}
		return channelLength;
	}

	private static double[] fit(
			final List<AffineTransform2D> affines,
			final Set<AffineTransform2D> consider,
			final int r,
			final int c) throws NotEnoughDataPointsException, IllDefinedDataPointsException {

		final double[][] data = new double[3][consider.size()];
		Arrays.fill(data[2], 1);

		int i = 0, z = 0;
		for (final AffineTransform2D affine : affines) {

			if (consider.contains(affine)) {
				data[0][i] = z;
				data[1][i] = affine.get(r, c);
				++i;
			}
			++z;
		}

		final AffineModel1D linear = new AffineModel1D();
		linear.fit(
				new double[][] {data[0]},
				new double[][] {data[1]},
				data[2]);

		final double[] array = new double[2];
		linear.toArray(array);
		return array;
	}

	private static double[][] fit(
			final List<AffineTransform2D> affines,
			final Set<AffineTransform2D> consider) throws NotEnoughDataPointsException, IllDefinedDataPointsException {

		final double[][] fit = new double[6][2];
		fit[0] = fit(affines, consider, 0, 0);
		fit[1] = fit(affines, consider, 0, 1);
		fit[2] = fit(affines, consider, 0, 2);
		fit[3] = fit(affines, consider, 1, 0);
		fit[4] = fit(affines, consider, 1, 1);
		fit[5] = fit(affines, consider, 1, 2);

		return fit;
	}

	private static Set<Tile<?>> whichGraph(final Tile<?> tile, final ArrayList<Set<Tile<?>>> graphs) {

		for (final Set<Tile<?>> graph : graphs)
			if (graph.contains(tile))
				return graph;
		return null;
	}

	private static void connectDefault(
			final Tile<?> tile,
			final Tile<?> otherTile,
			final ArrayList<Set<Tile<?>>> graphs,
			final CoordinateTransform transform) {

		final Set<Tile<?>> tileGraph = whichGraph(tile, graphs);
		final Set<Tile<?>> otherTileGraph = whichGraph(otherTile, graphs);
		if (tileGraph != otherTileGraph) {
			System.out.println("virtually connecting graphs with " + tileGraph.size() + " and " + otherTileGraph.size() + " tiles");
			final double[] coordinates = new double[] {0, 0};
			final double[] target = transform.apply(coordinates);
			final PointMatch match = new PointMatch(
					new Point(coordinates),
					new Point(target));
			final ArrayList<PointMatch> matches = new ArrayList<>();
			matches.add(match);
			tile.connect(otherTile, matches);
			otherTile.apply();
		}
	}

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException, ClassNotFoundException, NotEnoughDataPointsException, IllDefinedDataPointsException {

		final N5FSWriter n5 = new N5FSWriter(
				n5Path,
				new GsonBuilder().registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final HashMap<String, HashMap<String, AffineTransform2D>> camTransforms = n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, AffineTransform2D>>>() {}.getType());

		final HashMap<String, AffineTransform2D> channelCamTransforms = camTransforms.get(channel);

//		final InterpolatedModelSupplier modelSupplier =
//				new Transform.InterpolatedModelSupplier(
//						(Supplier<TranslationModel2D> & Serializable)TranslationModel2D::new,
//						(Supplier<ConstantModel<TranslationModel2D, ?>> & Serializable) () -> new ConstantModel<>(new TranslationModel2D()), 0.0);

		final int nSlices = channelLength(n5, id, channel, channelCamTransforms.keySet());

		final ArrayList<Tile<?>> tiles = new ArrayList<>();
		for (int i = 0; i < nSlices; ++i)
			tiles.add(new Tile<>(new TranslationModel2D()));

		for (final Entry<String, AffineTransform2D> channelCamTransformEntry : channelCamTransforms.entrySet()) {

			final AffineTransform2D channelCamTransform = channelCamTransformEntry.getValue();

			final String groupName = n5.groupPath(id, channel, channelCamTransformEntry.getKey(), "matches");
			if (!n5.exists(groupName))
				continue;

			System.out.println("loading matches " + groupName + "...");

			final DatasetAttributes matchesAttributes = n5.getDatasetAttributes(groupName);

			final Long mObject = n5.getAttribute(groupName, "distance", long.class);
			final long m = distance == 0 ? mObject == null ? 1 : mObject : distance;

			final int nCamSlices = (int)matchesAttributes.getDimensions()[0];
			for (int i = 0; i < nCamSlices; ++i) {
				for (int k = 0; k < m; ++k) {

					final int j = i + k + 1;

					ArrayList<PointMatch> matches = n5.readSerializedBlock(groupName, matchesAttributes, i, j);
					if (matches == null)
					{
						final ArrayList<PointMatch> matchesJI = n5.readSerializedBlock(groupName, matchesAttributes, j, i);
						if (matchesJI == null)
							continue;

						matches = new ArrayList<>();
						PointMatch.flip(matchesJI, matches);
					}

					for (final PointMatch match : matches) {
						final double[] p1l = match.getP1().getL();
						final double[] p2l = match.getP2().getL();
						final double[] p1w = match.getP1().getW();
						final double[] p2w = match.getP2().getW();
						channelCamTransform.inverse().apply(p1l, p1l);
						channelCamTransform.inverse().apply(p2l, p2l);
						System.arraycopy(p1l, 0, p1w, 0, p1l.length);
						System.arraycopy(p2l, 0, p2w, 0, p2l.length);
					}

					if (matches.size() > 0)
						tiles.get(i).connect(tiles.get(j), matches);
				}
			}
		}

		final Set<Tile<?>> nonEmptyTiles = tiles.stream().filter(tile -> tile.getConnectedTiles().size() > 0).collect(Collectors.toSet());

		/* optimize */
		/* feed all tiles that have connections into tile configuration, report those that are disconnected */
		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles(nonEmptyTiles);

		/* three pass optimization, first using the regularizer exclusively ... */
		try {
			tc.preAlign();
			tc.optimizeSilently(new ErrorStatistic(numIterations), maxEpsilon, numIterations, numIterations, 1);
		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			e.printStackTrace();
		}

		for (final Tile<?> tile : tiles) {

			System.out.println();

		}

		final ArrayList<Set<Tile<?>>> graphs = Tile.identifyConnectedGraphs(nonEmptyTiles);
		final Set<Tile<?>> largestGraph =
				graphs.stream().max((a, b) -> a.size() == b.size() ? 0 : a.size() < b.size() ? -1 : 1).get();

		/* extract affines */
		final ArrayList<AffineTransform2D> transforms = new ArrayList<>();
		final HashSet<AffineTransform2D> consider = new HashSet<>();
		for (final Tile<?> tile : tiles) {

			final AffineTransform2D transform =
					Transform.convertAffine2DtoAffineTransform2D((Affine2D)tile.getModel());
			transforms.add(transform);
			if (largestGraph.contains(tile))
				consider.add(transform);
		}

		final double[] shearX = fit(transforms, consider, 0, 2);
		final double[] shearY = fit(transforms, consider, 1, 2);

		System.out.println("average shear");
		System.out.println("x : a = " + shearX[0] + ", b = " + shearX[1]);
		System.out.println("y : a = " + shearY[0] + ", b = " + shearY[1]);

		/* align all tiles using the average shear model, and crank up the regularizer */
		final TranslationModel2D defaultTransform = new TranslationModel2D();
		defaultTransform.set(shearX[0], shearY[0]);
		for (int i = 0; i < nSlices; ++i) {

			final double x = shearX[0] * i;
			final double y = shearY[0] * i;

			final Tile tile = tiles.get(i);
//			final AffineModel2D affineModel = new AffineModel2D();
			final TranslationModel2D affineModel = new TranslationModel2D();
			affineModel.set(x, y);
			final TranslationModel2D regularizerAffineModel = new TranslationModel2D();
			regularizerAffineModel.set(x, y);
			final ConstantModel<TranslationModel2D, ?> regularizer = new ConstantModel<>(regularizerAffineModel);

			final InterpolatedModel model =
					new InterpolatedModel(affineModel, regularizer, lambdaModel);

			tile.setModel(model);

			if (i > 0)
				connectDefault(tile, tiles.get(i - 1), graphs, defaultTransform);

			tile.apply();
		}

		/* optimize */
		final TileConfiguration tc2 = new TileConfiguration();
		tc2.addTiles(tiles);

		try {
			tc2.optimizeSilently(new ErrorStatistic(numIterations), maxEpsilon, numIterations, numIterations, 1);
		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			e.printStackTrace();
		}

		/* extract affines */
		transforms.clear();
		for (final Tile<?> tile : tiles) {
			final InterpolatedAffineModel2D interpolatedAffineModel = new InterpolatedAffineModel2D(((InterpolatedModel)tile.getModel()).getA(), ((ConstantModel)((InterpolatedModel)tile.getModel()).getB()).getModel(), lambdaModel);
			transforms.add(
					Transform.convertAffine2DtoAffineTransform2D(interpolatedAffineModel));
		}

		n5.setAttribute(n5.groupPath(id, channel), "transforms", transforms);

//
//		/* bounding box, too fast locally to spend time to parallelize */
//		final double[] min = new double[]{Double.MAX_VALUE, Double.MAX_VALUE};
//		final double[] max = new double[]{-Double.MAX_VALUE, -Double.MAX_VALUE};
//		final double[] end = new double[]{dimensions[0] - 1, dimensions[1] - 1};
//		for (final Tile<?> tile : tiles) {
//			final double[][] bounds = Transform.bounds(end, Transform.convertAndInvertAffine2DtoAffineTransform2D((Affine2D)tile.getModel()));
//			if (bounds[0][0] < min[0]) min[0] = bounds[0][0];
//			if (bounds[0][1] < min[1]) min[1] = bounds[0][1];
//			if (bounds[1][0] > max[0]) max[0] = bounds[1][0];
//			if (bounds[1][1] > max[1]) max[1] = bounds[1][1];
//		}
//
//		final FinalInterval targetInterval = new FinalInterval(
//				new long[] {(long)min[0], (long)min[1], 0},
//				new long[] {(long)Math.ceil(max[0]), (long)Math.ceil(max[1]), nSlices - 1});
//		System.out.println(Util.printInterval(targetInterval));

		System.out.println("Done.");

		return null;
	}

	public static final void main(final String... args) {

		System.exit(new CommandLine(new AlignChannel()).execute(args));
	}
}
