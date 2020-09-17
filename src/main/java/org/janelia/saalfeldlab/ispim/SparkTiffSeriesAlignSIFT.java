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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.hotknife.util.Align;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Transform.InterpolatedAffineModel2DSupplier;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import loci.formats.FormatException;
import loci.formats.ImageReader;
import mpicbg.imagefeatures.Feature;
import mpicbg.models.Affine2D;
import mpicbg.models.AffineModel2D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.InterpolatedAffineModel2D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.transform.RigidModel2D;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;
import scala.Tuple2;

/**
 * Align a 3D N5 dataset.
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class SparkTiffSeriesAlignSIFT implements Callable<Void>, Serializable {

	@Option(names = "--tiffInput", required = true, description = "Input tiff file, e.g. '/nrs/svoboda/wangt/bis25/march18_bis25_sample4_spim_r4/affineC/pos012_exp488AND561AND647nm_Z0989_cam1affine.tif'")
	private String tiffInput = null;

	@Option(names = "--n5Output", required = true, description = "N5 output path, e.g. '/nrs/saalfeld/projects/wangt/bis25/march18_bis25_sample4_spim_r4/affineC/pos012_exp488AND561AND647nm_Z0989_cam1affine.n5")
	private String n5Output = null;

	@Option(names = {"-o", "--n5DatasetOutput"}, required = true, description = "N5 output dataset, e.g. /align")
	private String outDataset = null;

	@Option(names = {"-d", "--distance"}, required = false, description = "max distance for two slices to be compared, e.g. 10")
	private int distance = 10;

	@Option(names = "--minIntensity", required = false, description = "min intensity")
	private double minIntensity = 0;

	@Option(names = "--maxIntensity", required = false, description = "max intensity")
	private double maxIntensity = 65535;

	@Option(names = "--lambdaModel", required = false, description = "lambda for rigid regularizer in model")
	private double lambdaModel = 0.1;

	@Option(names = "--lambdaFilter", required = false, description = "lambda for rigid regularizer in filter")
	private double lambdaFilter = 0.1;

	@Option(names = "--maxEpsilon", required = true, description = "residual threshold for filter in world pixels")
	private double maxEpsilon = 50.0;

	@Option(names = "--iterations", required = false, description = "number of iterations")
	private int numIterations = 2000;

	@Option(names = "--tmpPath", required = true, description = "path for temporary files, e.g. /nrs/saalfeld/projects/wangt/bis25/march18_bis25_sample4_spim_r4/affineC/pos012_exp488AND561AND647nm_Z0989_cam1affine.tmp")
	private String tmpPath = null;


	public static boolean saveFeatures(
			final ArrayList<Feature> features,
			final String filePath) {

		System.out.println("Saving " + features.size() + " to " + filePath);
		try (
				final FileOutputStream fileStream = new FileOutputStream(new File(filePath));
				final ObjectOutputStream objectStream = new ObjectOutputStream(fileStream)) {

			objectStream.writeObject(features);
		} catch (final IOException e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	public static final ArrayList<Feature> readFeatures(final String filePath) {

		ArrayList<Feature> features;
		try (
				final FileInputStream fileStream = new FileInputStream(new File(filePath));
				final ObjectInputStream objectStream = new ObjectInputStream(fileStream)) {

			features = (ArrayList<Feature>)objectStream.readObject();
		} catch (final IOException | ClassNotFoundException e) {
			e.printStackTrace();
			features = null;
		}
		return features;
	}

	public static <T extends NativeType<T>> RandomAccessibleInterval<T> slice(
			final String tiffPath,
			final int slice) throws IOException, FormatException {

		final ImageReader reader = new ImageReader();
		reader.setId(tiffPath);

		final int width = reader.getSizeX();
		final int height = reader.getSizeY();

		final byte[] bytes = (byte[])reader.openPlane(slice, 0, 0, width, height);

		final ByteBuffer buffer = ByteBuffer.wrap(bytes);
		final short[] shorts = new short[width * height];
		buffer.order(reader.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

		reader.close();

		buffer.asShortBuffer().get(shorts);

		return (RandomAccessibleInterval<T>)ArrayImgs.unsignedShorts(shorts, width, height);
	}

	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException {

		final Path tmpBasePath = Paths.get(tmpPath);
		Files.createDirectories(tmpBasePath);
		final Path tmpDir = Files.createTempDirectory(tmpBasePath, "");
//		final Path tmpDir = Paths.get("/groups/cosem/cosem/saalfelds/Chlamydomonas_4x4x4nm/tmp/6562858001621012317");
//		final Path tmpDir = Paths.get("/groups/cosem/cosem/saalfelds/Macrophage_FS80_Cell2_4x4x4nm/tmp/4948929612810687672");
		final String tmpDirName = tmpDir.toAbsolutePath().toString();

		final SparkConf conf = new SparkConf().setAppName("SparkSeriesAlignSIFT");
		final JavaSparkContext sc = new JavaSparkContext(conf);
		sc.setLogLevel("ERROR");


		final ImageReader reader = new ImageReader();
		reader.setId(tiffInput);

		final int width = reader.getSizeX();
		final int height = reader.getSizeY();
		final int nSlices = reader.getImageCount();

		reader.close();

		final long[] dimensions = new long[]{
				width,
				height,
				nSlices
		};
//		final int nSlices = 2000;

		final double maxScale = Math.min(1.0, 1024.0 / Math.max(dimensions[0], dimensions[1]));
		final double minScale = maxScale * 0.25;
		final int fdSize = 4;

		final float intensityScale = 255.0f / (float)(maxIntensity - minIntensity);


//		final RandomAccessibleInterval stack = N5Utils.open(new N5FSReader(n5Input), inDataset);


//		/* show the original stack */
//		final RandomAccessibleInterval<UnsignedByteType> convertedStack = Converters.convert(
//				(RandomAccessibleInterval<RealType<?>>)stack,
//				(a, b) -> {
//					b.setReal(Math.min(255, Math.max(0, (a.getRealFloat() - minIntensity) * intensityScale)));
//				},
//				new UnsignedByteType());
//
//		final BdvStackSource bdv = BdvFunctions.show(convertedStack, "before");





		final ArrayList<Integer> slices = new ArrayList<>();
		for (int i = 0; i < nSlices; ++i)
			slices.add(new Integer(i));

		final JavaRDD<Integer> rddSlices = sc.parallelize(slices);

		/* save features */
		final JavaPairRDD<Integer, Boolean> rddFeatures = rddSlices.mapToPair(
				i ->  {
					final RandomAccessibleInterval slice = (RandomAccessibleInterval)slice(tiffInput, i);
					return new Tuple2<>(
							i,
							saveFeatures(
									Align.extractFeatures(
											Converters.convert(
													(RandomAccessibleInterval<RealType<?>>)slice,
													(a, b) -> {
														b.setReal((a.getRealFloat() - minIntensity) * intensityScale);
													},
													new FloatType()),
											maxScale,
											minScale,
											fdSize),
									tmpDirName + "/" + i)
//							true
							);
				});

		/* cache the booleans, so features aren't regenerated every time */
		rddFeatures.cache();

		/* run feature extraction */
		rddFeatures.count();


		/* match features */
		final JavaRDD<Integer> rddIndices = rddFeatures.filter(pair -> pair._2()).map(pair -> pair._1());
		final JavaPairRDD<Integer, Integer> rddPairs = rddIndices.cartesian(rddIndices).filter(
				pair -> {
					final int diff = pair._2() - pair._1();
					return diff > 0 && diff < distance;
				});
		final JavaPairRDD<Tuple2<Integer, Integer>, ArrayList<PointMatch>> matches = rddPairs.mapToPair(pair -> {
					final ArrayList<Feature> features1 = readFeatures(tmpDirName + "/" + pair._1());
					final ArrayList<Feature> features2 = readFeatures(tmpDirName + "/" + pair._2());
					return new Tuple2<>(
							new Tuple2<>(pair._1(), pair._2()),
							new ArrayList<>(Align.sampleRandomly(
								Align.filterMatchFeatures(
										features1,
										features2,
										0.92,
										new MultiConsensusFilter<>(
	//											new Transform.InterpolatedAffineModel2DSupplier(
	//													(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
	//													(Supplier<RigidModel2D> & Serializable)RigidModel2D::new, 0.25),
//												(Supplier<TranslationModel2D> & Serializable)TranslationModel2D::new,
												(Supplier<RigidModel2D> & Serializable)RigidModel2D::new,
												1000,
												maxEpsilon,
												0,
												10)),
								64)));
				});

		final InterpolatedAffineModel2DSupplier modelSupplier = new Transform.InterpolatedAffineModel2DSupplier(
				(Supplier<AffineModel2D> & Serializable)AffineModel2D::new,
				(Supplier<TranslationModel2D> & Serializable)TranslationModel2D::new, 1.0);

		final ArrayList<Tile<?>> tiles = new ArrayList<>();
		for (int i = 0; i < nSlices; ++i)
			tiles.add(new Tile<>(modelSupplier.get()));

		for (final Tuple2<Tuple2<Integer, Integer>, ArrayList<PointMatch>> entry : matches.collect()) {
			final ArrayList<PointMatch> pairMatches = entry._2();
			if (pairMatches.size() > 0)
				tiles.get(entry._1()._1()).connect(tiles.get(entry._1()._2()), entry._2());
		}

		final List<Tile<?>> nonEmptyTiles = tiles.stream().filter(tile -> tile.getConnectedTiles().size() > 0).collect(Collectors.toList());

		/* optimize */
		/* feed all tiles that have connections into tile configuration, report those that are disconnected */
		final TileConfiguration tc = new TileConfiguration();
		tc.addTiles(nonEmptyTiles);

		/* three pass optimization, first using the regularizer exclusively ... */
		try {
			tc.preAlign();
			tc.optimize(0.01, numIterations, numIterations, 0.75);
		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
			e.printStackTrace();
		}

		/* ... then using the desired model with low regularization ... */
		tiles.forEach(
				t -> ((InterpolatedAffineModel2D<?, ?>)t.getModel()).setLambda(lambdaModel));

		try {
			tc.optimize(0.01, numIterations, numIterations, 0.5);
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

		/* extract affines */
		final ArrayList<Tuple2<Integer, double[]>> transforms = new ArrayList<>();
		final ArrayList<double[]> affines = new ArrayList<>();
		for (int i = 0; i < nSlices; ++i) {
			final double[] affine = Transform.convertAffine2DtoAffineTransform2D((Affine2D)tiles.get(i).getModel()).getRowPackedCopy();
			transforms.add(new Tuple2<>(i, affine));
			affines.add(affine);
		}

		/* bounding box, too fast locally to spend time to parallelize */
		final double[] min = new double[]{Double.MAX_VALUE, Double.MAX_VALUE};
		final double[] max = new double[]{-Double.MAX_VALUE, -Double.MAX_VALUE};
		final double[] end = new double[]{dimensions[0] - 1, dimensions[1] - 1};
		for (final Tile<?> tile : tiles) {
			final double[][] bounds = Transform.bounds(end, Transform.convertAndInvertAffine2DtoAffineTransform2D((Affine2D)tile.getModel()));
			if (bounds[0][0] < min[0]) min[0] = bounds[0][0];
			if (bounds[0][1] < min[1]) min[1] = bounds[0][1];
			if (bounds[1][0] > max[0]) max[0] = bounds[1][0];
			if (bounds[1][1] > max[1]) max[1] = bounds[1][1];
		}

		final FinalInterval targetInterval = new FinalInterval(
				new long[] {(long)min[0], (long)min[1], 0},
				new long[] {(long)Math.ceil(max[0]), (long)Math.ceil(max[1]), nSlices - 1});
		System.out.println(Util.printInterval(targetInterval));



		final NativeType type = ((NativeType<? extends NativeType<?>>)Util.getTypeFromInterval((RandomAccessibleInterval)slice(tiffInput, 0)));

		/* create output dataset */
		final N5Writer n5Writer = new N5FSWriter(n5Output);

		final String outData = outDataset + "/data";
		final String outMask = outDataset + "/mask";

		n5Writer.createDataset(outData, Intervals.dimensionsAsLongArray(targetInterval), new int[] {1024, 1024, 1}, N5Utils.dataType(type), new GzipCompression());
		n5Writer.setAttribute(outData, "offset", Intervals.minAsLongArray(targetInterval));
		n5Writer.setAttribute(outData, "affines", affines);
		n5Writer.createDataset(outMask, Intervals.dimensionsAsLongArray(targetInterval), new int[] {1024, 1024, 1}, DataType.UINT8, new GzipCompression());
		n5Writer.setAttribute(outMask, "offset", Intervals.minAsLongArray(targetInterval));
		n5Writer.setAttribute(outMask, "affines", affines);

		/* export aligned series */
		final JavaPairRDD<Integer, double[]> rddTransforms = sc.parallelizePairs(transforms);

		rddTransforms.foreach(pair -> {

			final RandomAccessibleInterval slice = slice(tiffInput, pair._1());
			final RealType bg = ((RealType<? extends RealType<?>>)Util.getTypeFromInterval(slice)).createVariable();
			final AffineTransform2D transform = new AffineTransform2D();
			transform.set(pair._2());

			final N5Writer n5SliceWriter = new N5FSWriter(n5Output);

			final FinalInterval sliceTargetInterval = new FinalInterval(
					new long[] {(long)min[0],(long)min[1]},
					new long[] {(long)Math.ceil(max[0]), (long)Math.ceil(max[1])});

			final RandomAccessibleInterval transformedSlice = Transform.createAffineTransformedInterval(
					slice,
					sliceTargetInterval,
					transform,
					bg);

			final RandomAccessibleInterval<UnsignedByteType> transformedMaskSlice = Transform.createAffineTransformedMask(slice, sliceTargetInterval, transform);

			N5Utils.saveNonEmptyBlock(
					(RandomAccessibleInterval<NativeType>)Views.addDimension(transformedSlice, 0, 0),
					n5SliceWriter,
					outData,
					new long[] {0, 0, pair._1()},
					(NativeType)bg);

			N5Utils.saveNonEmptyBlock(
					Views.addDimension(transformedMaskSlice, 0, 0),
					n5SliceWriter,
					outMask,
					new long[] {0, 0, pair._1()},
					new UnsignedByteType(0));
		});

		sc.close();

		return null;
	}

	public static final void main(final String... args) {

		CommandLine.call(new SparkTiffSeriesAlignSIFT(), args);
	}
}
