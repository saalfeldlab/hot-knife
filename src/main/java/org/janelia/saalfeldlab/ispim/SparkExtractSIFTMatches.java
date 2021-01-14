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
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.MultiConsensusFilter;
import org.janelia.saalfeldlab.hotknife.util.Align;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;

import com.google.common.reflect.TypeToken;
import com.google.gson.GsonBuilder;

import loci.formats.FormatException;
import loci.formats.in.TiffReader;
import mpicbg.imagefeatures.Feature;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel2D;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import scala.Tuple2;

/**
 * Align a 3D N5 dataset.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command(
		name = "SparkExtractSIFTMatches",
		mixinStandardHelpOptions = true,
		version = "0.0.4-SNAPSHOT",
		description = "Extract SIFT matches from an iSPIM camera series")
public class SparkExtractSIFTMatches implements Callable<Void>, Serializable {

	private static final long serialVersionUID = 1030006363999084424L;

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--id", required = true, description = "Stack key, e.g. Pos012")
	private String id = null;

	@Option(names = "--channel", required = true, description = "Channel key, e.g. Ch488+561+647nm")
	private String channel = null;

	@Option(names = "--cam", required = true, description = "Cam key, e.g. cam1")
	private String cam = null;

	@Option(names = {"-d", "--distance"}, required = false, description = "max distance for two slices to be compared, e.g. 10")
	private int distance = 10;

	@Option(names = "--minIntensity", required = false, description = "min intensity")
	private double minIntensity = 0;

	@Option(names = "--maxIntensity", required = false, description = "max intensity")
	private double maxIntensity = 4096;

	@Option(names = "--maxEpsilon", required = true, description = "residual threshold for filter in world pixels")
	private double maxEpsilon = 50.0;

	@Option(names = "--iterations", required = false, description = "number of iterations for RANSAC (default 1000)")
	private int numIterations = 1000;

	@Option(names = "--minNumInliers", required = false, description = "minimal number of inliers for RANSAC (default 10)")
	private int minNumInliers = 10;

	@SuppressWarnings("serial")
	public static void extractStackSIFTMatches(
			final JavaSparkContext sc,
			final String n5Path,
			final String id,
			final String channel,
			final String cam,
			final int distance,
			final double minIntensity,
			final double maxIntensity,
			final double maxEpsilon,
			final int numIterations,
			final int minNumInliers) throws IOException, FormatException {

		final ArrayList<Slice> stack;
		final String groupName;
		{
			final N5FSWriter n5 = new N5FSWriter(
					n5Path,
					new GsonBuilder().registerTypeAdapter(
							AffineTransform2D.class,
							new AffineTransform2DAdapter()));

			groupName = n5.groupPath(id, channel, cam);

			if (!n5.exists(groupName)) {
				System.err.println("Group '" + groupName + "' does not exist in '" + n5Path + "'.");
				return;
			}

			stack = n5.getAttribute(
					groupName,
					"slices",
					new TypeToken<ArrayList<Slice>>() {}.getType());

			final String featuresGroupName = n5.groupPath(groupName, "features");
			if (n5.exists(featuresGroupName))
				n5.remove(featuresGroupName);
			n5.createDataset(
					featuresGroupName,
					new long[] {stack.size()},
					new int[] {1},
					DataType.OBJECT,
					new GzipCompression());

			final String matchesGroupName = n5.groupPath(groupName, "matches");
			if (n5.exists(matchesGroupName))
				n5.remove(matchesGroupName);
			n5.createDataset(
					matchesGroupName,
					new long[] {stack.size(), stack.size()},
					new int[] {1, 1},
					DataType.OBJECT,
					new GzipCompression());
			n5.setAttribute(
					matchesGroupName,
					"distance",
					distance);
		}

		/* get width and height from first slice */
		final int width, height;
		{
			try(final TiffReader firstSliceReader = new TiffReader()) {

				firstSliceReader.setId(stack.get(0).path);
				width = firstSliceReader.getSizeX();
				height = firstSliceReader.getSizeY();
				firstSliceReader.close();
			}
		}

		final double maxScale = Math.min(1.0, 1024.0 / Math.max(width, height));
		final double minScale = maxScale * 0.25;
		final int fdSize = 4;

		final float intensityScale = 255.0f / (float)(maxIntensity - minIntensity);

		final ArrayList<Integer> slices = new ArrayList<>();
		for (int i = 0; i < stack.size(); ++i)
			slices.add(new Integer(i));

		final JavaRDD<Integer> rddSlices = sc.parallelize(slices);

		/* save features */
		final JavaPairRDD<Integer, Integer> rddFeatures = rddSlices.mapToPair(
				i ->  {
					final Slice sliceInfo = stack.get(i);
					final N5FSWriter n5Writer = new N5FSWriter(n5Path);
					final String datasetName = groupName + "/features";
					final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);

					try (final TiffReader reader = new TiffReader()) {
						RandomAccessibleInterval slice =
							(RandomAccessibleInterval)Opener.openSlice(
									reader,
									sliceInfo.path,
									sliceInfo.index,
									width,
									height);

						if (sliceInfo.affine != null && !sliceInfo.affineTransform().isIdentity() )
						{
							slice = Views.interval( Views.raster( RealViews.affineReal( Views.interpolate(Views.extendZero( slice ), new NLinearInterpolatorFactory()), sliceInfo.affineTransform() ) ), slice );
							System.out.println( "non identify affine transform (index=" + i + "):" + sliceInfo.path + ", " + sliceInfo.index + ": " +  sliceInfo.affineTransform() );
						}

						final ArrayList<Feature> features = Align.extractFeatures(
								Converters.convert(
										(RandomAccessibleInterval<RealType<?>>)slice,
										(a, b) -> {
											b.setReal((a.getRealFloat() - minIntensity) * intensityScale);
										},
										new FloatType()),
								maxScale,
								minScale,
								fdSize);
						if (features.size() > 0) {
							n5Writer.writeSerializedBlock(
									features,
									datasetName,
									datasetAttributes,
									new long[] {i});
						}

						return new Tuple2<>(i, features.size());
					}
				});

		/* cache the booleans, so features aren't regenerated every time */
		rddFeatures.cache();

		/* run feature extraction */
		rddFeatures.count();

		/* match features */
		final JavaRDD<Integer> rddIndices = rddFeatures.filter(pair -> pair._2() > 0).map(pair -> pair._1());
		final JavaPairRDD<Integer, Integer> rddPairs = rddIndices.cartesian(rddIndices).filter(
				pair -> {
					final int diff = pair._2() - pair._1();
					return diff > 0 && diff < distance;
				});
		final JavaPairRDD<Tuple2<Integer, Integer>, Integer> rddMatches = rddPairs.mapToPair(
				pair -> {
					final N5FSWriter n5Writer = new N5FSWriter(n5Path);
					final String datasetName = groupName + "/features";
					final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(datasetName);

					final ArrayList<Feature> features1 = n5Writer.readSerializedBlock(datasetName, datasetAttributes, new long[] {pair._1()});
					final ArrayList<Feature> features2 = n5Writer.readSerializedBlock(datasetName, datasetAttributes, new long[] {pair._2()});

					final ArrayList<PointMatch> matches = new ArrayList<>(Align.sampleRandomly(
							Align.filterMatchFeatures(
									features1,
									features2,
									0.92,
									new MultiConsensusFilter<>(
//											new Transform.InterpolatedAffineModel2DSupplier(
//													(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
//													(Supplier<RigidModel2D> & Serializable)RigidModel2D::new, 0.25),
											(Supplier<TranslationModel2D> & Serializable)TranslationModel2D::new,
//											(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
											numIterations,
											maxEpsilon,
											0,
											minNumInliers)),
							64));

					if (matches.size() > 0) {

						final String matchesDatasetName = groupName + "/matches";
						final DatasetAttributes matchesAttributes = n5Writer.getDatasetAttributes(matchesDatasetName);
						n5Writer.writeSerializedBlock(
								matches,
								matchesDatasetName,
								matchesAttributes,
								new long[] {pair._1(), pair._2()});
					}

					return new Tuple2<>(
							new Tuple2<>(pair._1(), pair._2()),
							matches.size());

				});

		/* run matching */
		rddMatches.count();
	}

	@Override
	public Void call() throws IOException, FormatException {
		// System property for locally calling spark has to be set, e.g. -Dspark.master=local[4]
		final String sparkLocal = System.getProperty( "spark.master" );

		// only do that if the system property is not set
		if ( sparkLocal == null || sparkLocal.trim().length() == 0 )
		{
			System.out.println( "Spark System property not set: " + sparkLocal );
			System.setProperty( "spark.master", "local[" + Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 ) + "]" );
		}

		System.out.println( "Spark System property is: " + System.getProperty( "spark.master" ) );

		final SparkConf conf = new SparkConf().setAppName("SparkExtractSIFTMatches");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");

		extractStackSIFTMatches(
				sc,
				n5Path,
				id,
				channel,
				cam,
				distance,
				minIntensity,
				maxIntensity,
				maxEpsilon,
				numIterations,
				minNumInliers);

		sc.close();

		System.out.println("Done.");

		return null;
	}

	public static final void main(final String... args) {

		System.exit(new CommandLine(new SparkExtractSIFTMatches()).execute(args));
	}
}
