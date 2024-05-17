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
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Align;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import mpicbg.imagefeatures.Feature;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import scala.Tuple2;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkAlignAffineGlobal {

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private final String n5Path = null;

		@Option(name = "-d", aliases = {"--n5Dataset"}, required = true, usage = "List of N5 datasets, alternating top and bottom block faces e.g. -d /slab-24/top -d slab-24/bot -d slab-25/top ...")
		private final List<String> datasetNames = null;

		@Option(name = "-o", aliases = {"--n5GroupOutput"}, required = true, usage = "N5 output group, e.g. /align-0")
		private final String outGroup = null;

		@Option(name = "-f", aliases = {"--fixDatasets"}, required = false, usage = "List of fixed N5 datasets, e.g. -f /slab-24/top -f slab-25/top")
		private final List<String> fixedDatasetNames = null;

		@Option(name = "-fm", aliases = {"--fixedModels"}, required = false, usage = "Affine transform for each fixed model, e.g. -fm '[[0.85, -0.575, 22704.7], [0.51, 0.80, 19373.73]]' -fm '[[0.95, -0.47, 12704.7], [0.41, 0.60, 7373.73]]'")
		private final List<String> fixedModels = null;

		@Option(name = "-bmin", aliases = {"--boundsMin"}, required = false, usage = "min bounds, e.g. '-34.4,213.12,9.12'")
		private final String boundsMinString = null;
		private double[] boundsMin;

		@Option(name = "-bmax", aliases = {"--boundsMax"}, required = false, usage = "max bounds, e.g. '-324234.1,21213.12,9456.54'")
		private final String boundsMaxString = null;
		private double[] boundsMax;

		@Option(name = "--scaleIndex", required = true, usage = "scale index, e.g. 4 (means scale = 1.0 / 2^4)")
		private int scaleIndex = 0;

		@Option(name = "--iterations", required = false, usage = "RANSAC iterations (default: 10000)")
		private int iterations = 0;

		@Option(name = "--maxError", required = false, usage = "RANSAC maxError (default: 200.0)")
		private double maxError = 0;

		@Option(name = "--filter", required = false, usage = "Outlier filter (RANSAC or MULTI_CONSENSUS_RANSAC (default))")
		private OutlierFilter filter = OutlierFilter.MULTI_CONSENSUS_RANSAC;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);

				if ( boundsMinString != null )
					boundsMin = parseCSDoubleArray( boundsMinString );

				if ( boundsMaxString != null )
					boundsMax = parseCSDoubleArray( boundsMaxString );

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
		 * @return the fixedDatasetNames
		 */
		public List<String> getFixedDatasetNames() {
			return fixedDatasetNames;
		}

		/**
		 * @return the fixedModels
		 */
		public List<String> getFixedModels() {
			return fixedModels;
		}

		public double[] getBoundsMin() {
			return boundsMin;
		}

		public double[] getBoundsMax() {
			return boundsMax;
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

		/**
		 * @return the outGroup
		 */
		public int getNumIterations() {
			return iterations;
		}

		/**
		 * @return the outGroup
		 */
		public double getMaxError() {
			return maxError;
		}

		public enum OutlierFilter {
			RANSAC,
			MULTI_CONSENSUS_RANSAC;
		}
	}


	// size of the feature descriptor for feature extraction
	private static final int FD_SIZE = 8;
	// minimal percentage of inliers for RANSAC
	public static final double MIN_INLIER_RATIO = 0.05;
	// minimal absolute number of inliers for RANSAC
	public static final int MIN_NUM_INLIERS = 7;


	static public JavaPairRDD<String, ArrayList<Feature>> extractFeatures(
			final JavaSparkContext sc,
			final String n5Path,
			final List<String> datasetNames,
			final int scaleIndex) throws IOException {

		final JavaRDD<String> rdd = sc.parallelize(datasetNames);

		final JavaPairRDD<String, ArrayList<Feature>> features =
				rdd.mapToPair(inDatasetName -> {

					final N5Reader n5Reader = new N5FSReader(n5Path);
					final RandomAccessibleInterval<FloatType> source;
					try {
						source = N5Utils.open(n5Reader, inDatasetName + "/s" + scaleIndex);
					} catch (Exception e) {
						throw new RuntimeException("failed to open " + inDatasetName + "/s" + scaleIndex, e);
					}

					System.out.println(inDatasetName + " : " + Arrays.toString(Intervals.dimensionsAsLongArray(source)) + " extracting features...");

					final ArrayList<Feature> fs = Align.extractFeatures(source, 2.0, 0.05, FD_SIZE);

					System.out.println(inDatasetName + " : " + fs.size() + " features extracted.");

					return new Tuple2<>(inDatasetName, fs);
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
					new Tuple2<>(
							new Tuple2<>(pair[0], featuresMap.get(pair[0])),
							new Tuple2<>(pair[1], featuresMap.get(pair[1]))));

		final JavaRDD<Tuple2<Tuple2<String, ArrayList<Feature>>, Tuple2<String, ArrayList<Feature>>>> tuples = sc.parallelize(tupleList);

		final JavaPairRDD<String[], ArrayList<PointMatch>> candidateMatches = tuples.mapToPair(
				tuple -> {
					final ArrayList<PointMatch> candidates = Align.matchFeatures(
							tuple._1()._2(),
							tuple._2()._2(),
							0.92f);

					final String[] key = new String[]{tuple._1()._1(), tuple._2()._1()};

					System.out.println(Arrays.toString(key) + " : " + candidates.size() + " matches found.");

					return new Tuple2<>(key, candidates);
				});

		return candidateMatches;
	}


	static public <SA extends Supplier<? extends Model<?>> & Serializable> JavaPairRDD<String[], ArrayList<PointMatch>> filterBlockFaceMatches(
			final JavaPairRDD<String[], ArrayList<PointMatch>> candidateMatches,
			final SA modelSupplier,
			final int numIterations,
			final double maxEpsilon,
			final double minInlierRatio,
			final int minNumInliers,
			final Options.OutlierFilter filterType)
	{
		final JavaPairRDD<String[], ArrayList<PointMatch>> inlierMatches = candidateMatches.mapToPair(
				tuple -> {
					final String[] pairIds = tuple._1();
					final ArrayList<PointMatch> candidates = tuple._2();

					final ArrayList<PointMatch> inliers;
					if (filterType == Options.OutlierFilter.RANSAC) {
						inliers = new ArrayList<>();
						final Model<?> model = modelSupplier.get();
						model.filterRansac(tuple._2(), inliers, numIterations, maxEpsilon, minInlierRatio, minNumInliers);
					} else if (filterType == Options.OutlierFilter.MULTI_CONSENSUS_RANSAC) {
						@SuppressWarnings("unchecked")
						final MultiConsensusFilter<Model<?>> multiConsensusFilter = new MultiConsensusFilter<>(
								(Supplier<Model<?>>) modelSupplier,
								numIterations,
								maxEpsilon,
								minInlierRatio,
								minNumInliers);
						inliers = multiConsensusFilter.filter(candidates);
					} else {
						throw new IllegalArgumentException("Unsupported outlier filter type: " + filterType);
					}

					System.out.printf("%s : %d inliers found.", Arrays.toString(pairIds), inliers.size());
					System.out.println();

					return new Tuple2<>(pairIds, inliers);
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
	 * @param fixedModels - map from datasetname to {@link AffineModel2D} for fixed tiles
	 * @param fixedTiles - empty list that will be populated
	 * @return
	 */
	public static ArrayList<Tile<?>> createConnectedTiles(
			final List<String> datasetNames,
			final JavaPairRDD<String[], ArrayList<PointMatch>> filteredMatches,
			final HashMap<String, AffineModel2D > fixedModels,
			final List< Tile<?> > fixedTiles ) {

		/* map matches to first slab-face */
		final HashMap<String, ArrayList<PointMatch>> matchMap = new HashMap<>();
		for (final Tuple2<String[], ArrayList<PointMatch>> entry : filteredMatches.collect())
			matchMap.put(entry._1()[0], entry._2());

		final ArrayList<Tile<?>> tiles = Align.connectStackTiles(
				datasetNames,
				matchMap,
				fixedModels,
				fixedTiles,
				new Transform.InterpolatedAffineModel2DSupplier<>(
						(Supplier<AffineModel2D> & Serializable) AffineModel2D::new,
						(Supplier<RigidModel2D> & Serializable) RigidModel2D::new,
						1.0));

		return tiles;
	}

	public static void saveAffines(
			final String n5Path,
			final String outGroup,
			final double[] min,
			final double[] max,
			final int scaleIndex,
			final JavaPairRDD<String, double[]> transforms) {

		final double scale = 1.0 / (1 << scaleIndex);

		transforms.foreach(
				tuple -> {
					final N5Writer n5Writer = new N5FSWriter(n5Path);
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


	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

		final List<String> datasetNames = options.getDatasetNames();
		final List<String> transformDatasetNames = datasetNames.stream()
				.map(Util::flattenGroupName)
				.collect(Collectors.toList());

		/* collect fix tile info if requested */
		final HashMap<String, AffineModel2D > fixedModels = new HashMap<>();

		if ( options.getFixedDatasetNames() != null && !options.getFixedDatasetNames().isEmpty())
		{
			for ( int i = 0; i < options.getFixedDatasetNames().size(); ++i )
			{
				String dataset = options.getFixedDatasetNames().get( i );
				String modelString = options.getFixedModels().get( i );

				String[] ms = modelString.replaceAll("[\\[\\]]", "").split(",");
				if ( ms.length != 6 )
					throw new RuntimeException( "number of models does not match number of fixed datasets." );

				final double[] m = new double[ ms.length ];
				for ( int j = 0; j < ms.length; ++j )
					m[ j ] = Double.parseDouble( ms[ j ] );

				final AffineModel2D model = new AffineModel2D();
				model.set(m[ 0 ], m[ 3 ], m[ 1 ], m[ 4 ], m[ 2 ], m[ 5 ]);

				fixedModels.put( dataset, model );

				System.out.println( "fixing " + dataset + ": model=" + net.imglib2.util.Util.printCoordinates( m ) + "; " + model );
			}
		}

		final SparkConf conf = new SparkConf().setAppName("SparkAlignAffineGlobal");
		final JavaSparkContext sc = new JavaSparkContext(conf);

		final JavaPairRDD<String, ArrayList<Feature>> features = extractFeatures(
				sc,
				options.getN5Path(),
				datasetNames,
				options.getScaleIndex());

		final JavaPairRDD<String[], ArrayList<PointMatch>> matches = matchBlockFaces(
				sc,
				datasetNames,
				features);

		final JavaPairRDD<String[], ArrayList<PointMatch>> scaledMatches = matches.mapToPair(
				entry -> {
					Align.unScalePointMatches(entry._2(), options.getScaleIndex());
					return entry;
				});

		final JavaPairRDD<String[], ArrayList<PointMatch>> filteredMatches = filterBlockFaceMatches(
				scaledMatches,
				new Transform.InterpolatedAffineModel2DSupplier<>( // rigid model for outlier detection
						(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
						(Supplier<RigidModel2D> & Serializable)RigidModel2D::new, 1.0),
				options.getNumIterations(),// 10000, 100000 for MultiSem
				options.getMaxError(),// 200, 400 for MultiSem
				MIN_INLIER_RATIO,
				MIN_NUM_INLIERS,
				options.filter);


		/* remember fixed tiles if requested */
		final List< Tile<?> > fixedTiles = new ArrayList<>();

		final ArrayList<Tile<?>> tiles = createConnectedTiles(
				datasetNames,
				filteredMatches,
				fixedModels,
				fixedTiles );

		System.out.println( "fixedTiles: " + fixedTiles.size() );

		/* optimize */
		/* feed all tiles that have connections into tile configuration, report those that are disconnected */
		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles(tiles);

		/* fix tiles if requested */
		if (!fixedTiles.isEmpty())
			fixedTiles.forEach(tc::fixTile);

		/* three pass optimization, first using the regularizer exclusively ... */
		try {
			tc.preAlign();
			tc.optimize(0.01, 5000, 200, 0.5);
		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			e.printStackTrace();
		}

		/* ... then using the desired model with low regularization ... */
		tiles.forEach(
				t -> {
					if (!fixedTiles.contains( t ))
						((InterpolatedAffineModel2D<?, ?>)t.getModel()).setLambda(0.1); } );

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
					AffineModel2D tileTransform = ((InterpolatedAffineModel2D<?, ?>)t.getModel()).createAffineModel2D();
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
				datasetNames,
				0,
				topBotTransforms);

		System.out.println("Bounds from transform: " + Arrays.deepToString(bounds));

		if ( options.getBoundsMin() != null )
		{
			System.out.println("Overriding bounds min: " + net.imglib2.util.Util.printCoordinates( options.getBoundsMin() ));
			for ( int d = 0; d < bounds[0].length; ++d )
				bounds[0][d] = options.getBoundsMin()[d];
		}

		if ( options.getBoundsMax() != null )
		{
			System.out.println("Overriding bounds max: " + net.imglib2.util.Util.printCoordinates( options.getBoundsMax() ));
			for ( int d = 0; d < bounds[1].length; ++d )
				bounds[1][d] = options.getBoundsMax()[d];
		}

		System.out.println("Bounds : " + Arrays.deepToString(bounds));

		/* save transforms */
		final N5Writer n5 = new N5FSWriter(options.getN5Path());
		n5.createGroup(options.getOutGroup());
		n5.setAttribute(options.getOutGroup(), "datasets", datasetNames);
		n5.setAttribute(options.getOutGroup(), "transforms", transformDatasetNames);
		n5.setAttribute(options.getOutGroup(), "scaleIndex", options.getScaleIndex());
		n5.setAttribute(options.getOutGroup(), "boundsMin", bounds[0]);
		n5.setAttribute(options.getOutGroup(), "boundsMax", bounds[1]);

		final ArrayList<Tuple2<String, double[]>> transformTuples = new ArrayList<>();
		for (int i = 0; i < transforms.size(); ++i) {
			transformTuples.add(
					new Tuple2<>(
							transformDatasetNames.get(2 * i),
							transforms.get(i).getRowPackedCopy()));
			transformTuples.add(
					new Tuple2<>(
							transformDatasetNames.get(2 * i + 1),
							transforms.get(i).getRowPackedCopy()));
		}

		System.out.println("saving affines to " + options.getN5Path() + "/" + options.getOutGroup() );

		saveAffines(
				options.getN5Path(),
				options.getOutGroup(),
				bounds[0],
				bounds[1],
				options.getScaleIndex(),
				sc.parallelizePairs(transformTuples));

		n5.close();

		sc.close();

		System.out.println("done.");
	}
}
