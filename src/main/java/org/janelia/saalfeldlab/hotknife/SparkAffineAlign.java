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

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Align;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import bdv.util.Bdv;
import mpicbg.imagefeatures.Feature;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import net.imagej.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import scala.Tuple2;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkAffineAlign {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "-d", aliases = {"--n5Dataset"}, required = true, usage = "List of N5 datasets, alternating top and bottom block faces e.g. -d /slab-24/top -d slab-24/bot -d slab-25/top ...")
		private final List<String> datasetNames = null;

		@Option(name = "-o", aliases = {"--n5OutputGroup"}, required = true, usage = "N5 output group, e.g. /align")
		private final String outGroup = null;

		@Option(name = "--scaleIndex", required = true, usage = "scale index, e.g. 4 (means scale = 1.0 / 2^4)")
		private int scaleIndex = 0;

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

		/**
		 * @return the n5Path
		 */
		public String getN5Path() {

			return n5Path;
		}

		/**
		 * @return the inDatasetNames
		 */
		public List<String> getDatasetNames() {
			return datasetNames;
		}

		/**
		 * @return the scaleIndex
		 */
		public int getScaleIndex() {
			return scaleIndex;
		}

		/**
		 * @return the outGroup
		 */
		public String getOutGroup() {
			return outGroup;
		}
	}


	static public JavaPairRDD<String, ArrayList<Feature>> extractFeatures(
			final JavaSparkContext sc,
			final String n5Path,
			final List<String> datasetNames,
			final int scaleIndex) throws IOException {

		final JavaRDD<String> rdd = sc.parallelize(datasetNames);

		final JavaPairRDD<String, ArrayList<Feature>> features =
				rdd.mapToPair(inDatasetName -> {

					final N5Reader n5Reader = N5.openFSReader(n5Path);
					final RandomAccessibleInterval<FloatType> source = N5Utils.open(n5Reader, inDatasetName + "/s" + scaleIndex);

					System.out.println(inDatasetName + " : " + Arrays.toString(Intervals.dimensionsAsLongArray(source)) + " extracting features...");

					final ArrayList<Feature> fs = Align.extractFeatures(source, 1.0, 0.5, 4);

					System.out.println(inDatasetName + " : " + fs.size() + " features extracted.");

					return new Tuple2<String, ArrayList<Feature>>(inDatasetName, fs);
				});

		return features;
	}


	static public <SA extends Supplier<? extends Model<?>> & Serializable> JavaPairRDD<String[], ArrayList<PointMatch>> matchBlockFaces(
			final JavaSparkContext sc,
			final List<String> datasetNames,
			final JavaPairRDD<String, ArrayList<Feature>> features) {

		final ArrayList<String[]> pairs = new ArrayList<>();
		for (int i = 2; i < datasetNames.size(); i += 2)
			pairs.add(new String[]{datasetNames.get(i - 1), datasetNames.get(i)});

		final Map<String, ArrayList<Feature>> featuresMap = features.collectAsMap();

		final ArrayList<Tuple2<Tuple2<String, ArrayList<Feature>>, Tuple2<String, ArrayList<Feature>>>> tupleList = new ArrayList<>();

		for (final String[] pair : pairs)
			tupleList.add(
					new Tuple2<Tuple2<String, ArrayList<Feature>>, Tuple2<String, ArrayList<Feature>>>(
							new Tuple2<String, ArrayList<Feature>>(pair[0], featuresMap.get(pair[0])),
							new Tuple2<String, ArrayList<Feature>>(pair[1], featuresMap.get(pair[1]))));

		final JavaRDD<Tuple2<Tuple2<String, ArrayList<Feature>>, Tuple2<String, ArrayList<Feature>>>> tuples = sc.parallelize(tupleList);

		final JavaPairRDD<String[], ArrayList<PointMatch>> candidateMatches = tuples.mapToPair(
				tuple -> {
					final ArrayList<PointMatch> candidates = Align.matchFeatures(
							tuple._1()._2(),
							tuple._2()._2(),
							0.92f);

					final String[] key = new String[]{tuple._1()._1(), tuple._2()._1()};

					System.out.println(Arrays.toString(key) + " : " + candidates.size() + " matches found.");

					return new Tuple2<String[], ArrayList<PointMatch>>(
							key,
							candidates);
				});

		return candidateMatches;
	}


	static public <SA extends Supplier<? extends Model<?>> & Serializable> JavaPairRDD<String[], ArrayList<PointMatch>> filterBlockFaceMatches(
			final JavaPairRDD<String[], ArrayList<PointMatch>> candidateMatches,
			final SA modelSupplier,
			final int numIterations,
			final double maxEpsilon,
			final double minInlierRatio,
			final int minNumInliers)
	{
		final JavaPairRDD<String[], ArrayList<PointMatch>> inlierMatches = candidateMatches.mapToPair(
				tuple -> {
					@SuppressWarnings("unchecked")
					final ArrayList<PointMatch> inliers =
							new MultiConsensusFilter(
									(Supplier<Model<?>>)modelSupplier,
									numIterations,
									maxEpsilon,
									minInlierRatio,
									minNumInliers).filter(tuple._2());

					System.out.printf("%s : %d inliers found.", Arrays.toString(tuple._1()), inliers.size());
					System.out.println();

					return new Tuple2<>(
							tuple._1(),
							inliers);

				});

		return inlierMatches;
	}


	/**
	 * Creates a {@link List} of connected tiles with each tile representing
	 * one block with a top and bottom face.  The names of topa and bottom face
	 * are passed as a sorted list of datasetNames starting with the top face
	 * of the first stack and ending with the bottom face of the last stack.
	 *
	 * @param datasetNames names of
	 * @param filteredMatches
	 * @return
	 */
	public static ArrayList<Tile<?>> createConnectedTiles(
			final List<String> datasetNames,
			final JavaPairRDD<String[], ArrayList<PointMatch>> filteredMatches) {

		/* map matches to first slab-face */
		final HashMap<String, ArrayList<PointMatch>> matchMap = new HashMap<>();
		for (final Tuple2<String[], ArrayList<PointMatch>> entry : filteredMatches.collect())
			matchMap.put(entry._1()[0], entry._2());

		final ArrayList<Tile<?>> tiles = Align.connectStackTiles(
				datasetNames,
				matchMap,
				new Transform.InterpolatedAffineModel2DSupplier<AffineModel2D, RigidModel2D>(
						(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
						(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
						1.0));

		return tiles;
	}

	public static void saveAffines(
			final String n5Path,
			final String outGroup,
			final double[] min,
			final double[] max,
			final int scaleIndex,
			final JavaPairRDD<Integer, double[]> transforms) {

		final double scale = 1.0 / (1 << scaleIndex);

		transforms.foreach(
				tuple -> {
					final N5Writer n5Writer = N5.openFSWriter(n5Path);
					final AffineTransform2D affine = new AffineTransform2D();
					affine.set(tuple._2());
					Transform.saveScaledTransform(
							n5Writer,
							outGroup + "/" + tuple._1(),
							affine,
							scale,
							min,
							max);
				});
	}


	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final SparkConf conf = new SparkConf().setAppName("SparkGenerateFaceScaleSpace");
		final JavaSparkContext sc = new JavaSparkContext(conf);

		final JavaPairRDD<String, ArrayList<Feature>> features = extractFeatures(
				sc,
				options.getN5Path(),
				options.getDatasetNames(),
				options.getScaleIndex());

		final JavaPairRDD<String[], ArrayList<PointMatch>> matches = matchBlockFaces(
				sc,
				options.getDatasetNames(),
				features);

		final JavaPairRDD<String[], ArrayList<PointMatch>> scaledMatches = matches.mapToPair(
				entry -> {
					Align.unScalePointMatches(entry._2(), options.getScaleIndex());
					return entry;
				});

		final JavaPairRDD<String[], ArrayList<PointMatch>> filteredMatches = filterBlockFaceMatches(
				scaledMatches,
				new Transform.InterpolatedAffineModel2DSupplier<>(
						(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
						(Supplier<RigidModel2D> & Serializable)RigidModel2D::new, 0.25),
				10000,
				200,
				0,
				7);


		final ArrayList<Tile<?>> tiles = createConnectedTiles(
				options.getDatasetNames(),
				filteredMatches);


		/* optimize */
		/* feed all tiles that have connections into tile configuration, report those that are disconnected */
		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles(tiles);

		/* three pass optimization, first using the regularizer exclusively ... */
		try {
			tc.preAlign();
			tc.optimize(0.01, 5000, 200, 0.5);
		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			e.printStackTrace();
		}

		/* ... then using the desired model with low regularization ... */
		tiles.forEach(
				t -> ((InterpolatedAffineModel2D<?, ?>)t.getModel()).setLambda(0.1));

		try {
			tc.optimize(0.01, 5000, 200, 0.5);
		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			e.printStackTrace();
		}

//		/* ... then using the desired model with very low regularization.*/
//		tiles.forEach(
//			t -> ((InterpolatedAffineModel2D<?, ?>)t.getModel()).setLambda(0.01));
//
//		try {
//			tc.optimize(0.01, 5000, 200, 0.9);
//		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
//			e.printStackTrace();
//		}


		/* convert and invert transforms */
		final ArrayList<AffineTransform2D> transforms = new ArrayList<>();
		tiles.forEach(
				t -> {
					final Affine2D<?> tileTransform = (Affine2D<?>)t.getModel();
					System.out.println(tileTransform.createAffine());
					final double[] a = new double[6];
					tileTransform.createInverse().toArray(a);
					final AffineTransform2D transform = new AffineTransform2D();
					transform.set(
							a[0], a[2], a[4],
							a[1], a[3], a[5]);
					transforms.add(transform);
				});

		/* joint bounding box */
		final ArrayList<AffineTransform2D> topBotTransforms = new ArrayList<>();
		transforms.forEach(t -> {
			topBotTransforms.add(t);
			topBotTransforms.add(t);
		});

		final double[][] bounds = Transform.bounds(
				options.getN5Path(),
				options.getDatasetNames(),
				0,
				topBotTransforms);

		System.out.println("Bounds : " + Arrays.deepToString(bounds));

		/* save transforms */
		final N5Writer n5 = N5.openFSWriter(options.getN5Path());
		n5.createGroup(options.getOutGroup());
		n5.setAttribute(options.getOutGroup(), "datasets", options.getDatasetNames());
		n5.setAttribute(options.getOutGroup(), "scaleIndex", options.getScaleIndex());
		n5.setAttribute(options.getOutGroup(), "boundsMin", bounds[0]);
		n5.setAttribute(options.getOutGroup(), "boundsMax", bounds[1]);

		final ArrayList<Tuple2<Integer, double[]>> transformTuples = new ArrayList<>();
		for (int i = 0; i < transforms.size(); ++i) {
			transformTuples.add(new Tuple2<>(2 * i, transforms.get(i).getRowPackedCopy()));
			transformTuples.add(new Tuple2<>(2 * i + 1, transforms.get(i).getRowPackedCopy()));
		}

		saveAffines(
				options.getN5Path(),
				options.getOutGroup(),
				bounds[0],
				bounds[1],
				options.getScaleIndex(),
				sc.parallelizePairs(transformTuples));




		/* pairwise transforms second to first */
		final ArrayList<RealTransform> pairTransforms = new ArrayList<>();
		AffineTransform2D t1 = ((AffineTransform2D)transforms.get(0)).inverse().copy();
		pairTransforms.add(new AffineTransform2D());
		for (int i = 1; i < transforms.size(); ++i) {
			final AffineTransform2D t2 = ((AffineTransform2D)transforms.get(i)).inverse().copy();
			final AffineTransform2D t2Copy = t2.copy();
			t2.preConcatenate(t1.inverse());
			t1 = t2Copy;
			pairTransforms.add(t2.inverse());
		}

		/* interleaved pairwise transforms */
		final ArrayList<RealTransform> interleavedPairTransforms = new ArrayList<>();
		pairTransforms.forEach(
				t -> {
					interleavedPairTransforms.add(t);
					interleavedPairTransforms.add(new AffineTransform2D());
				});


		sc.close();




		/* test and inspect */

		new ImageJ();

		final N5Reader n5Reader = N5.openFSReader(options.getN5Path());
		final String[] datasetNames = n5Reader.getAttribute(options.getOutGroup(), "datasets", String[].class);
		final int transformScaleIndex = n5Reader.getAttribute(options.getOutGroup(), "scaleIndex", int.class);
		final double[] boundsMin = n5Reader.getAttribute(options.getOutGroup(), "boundsMin", double[].class);
		final double[] boundsMax = n5Reader.getAttribute(options.getOutGroup(), "boundsMax", double[].class);

		final int showScaleIndex = options.getScaleIndex();
		final double showScale = 1.0 / (1 << showScaleIndex);

		final ArrayList<RealTransform> realTransforms = new ArrayList<>();
		for (int i = 0; i < datasetNames.length; ++i) {
			final RealTransform transform = Transform.loadScaledTransform(
					n5Reader,
					options.getOutGroup() + "/" + i);
			realTransforms.add(transform);
		}

		final Bdv bdv = Show.transformedStack(
				options.getN5Path(),
				options.getDatasetNames(),
				showScaleIndex,
				realTransforms,
//				new FinalInterval(new long[]{-512, -512}, new long[]{1535, 1535}));
				new FinalInterval(
						Grid.floorScaled(boundsMin, showScale),
						Grid.ceilScaled(boundsMax, showScale)),
				null);

		Show.transformedStack(
				options.getN5Path(),
				options.getDatasetNames(),
				showScaleIndex,
				topBotTransforms,
//				new FinalInterval(new long[]{-512, -512}, new long[]{1535, 1535}));
				new FinalInterval(
						Grid.floorScaled(boundsMin, showScale),
						Grid.ceilScaled(boundsMax, showScale)),
				bdv);
	}
}
