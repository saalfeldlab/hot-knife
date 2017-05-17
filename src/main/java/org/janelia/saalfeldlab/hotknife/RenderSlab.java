/**
 *
 */
package org.janelia.saalfeldlab.hotknife;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import ij.IJ;
import ij.ImagePlus;
import ij.io.Opener;
import ij.process.FloatProcessor;
import mpicbg.ij.plugin.ApplyFlow;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.type.numeric.real.DoubleType;
import scala.Tuple2;

/**
 * @author Stephan Saalfeld <saalfelds@janelia.hhmi.org>
 *
 */
public class RenderSlab {

	public static class Options implements Serializable {

		@Option(name = "--inputformat", required = true, usage = "Input image format, consumes one integer number e.g. /tmp/image-%05d.png")
		private final String inputFormat = null;

		@Option(name = "--xpath", required = true, usage = "x-field")
		private final String xPath = null;

		@Option(name = "--ypath", required = true, usage = "y-field")
		private final String yPath = null;

		@Option(name = "--width", required = true, usage = "width")
		private Integer width;

		@Option(name = "--height", required = true, usage = "height")
		private Integer height;

		@Option(name = "--first", required = true, usage = "first index")
		private Integer first;

		@Option(name = "--last", required = true, usage = "last index")
		private Integer last;

		@Option(name = "--affine", required = true, usage = "affine transform")
		private final String affineString = "1.0,0.0,0.0,0.0,1.0,0.0";

		private double[] affine;

		@Option(name = "--outputformat", required = true, usage = "Output image format, consumes one integer number e.g. /tmp/image-%05d.png")
		private String outputFormat;

		private boolean parsedSuccessfully = false;

		public Options(final String[] args) {
			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);
				affine = new double[6];
				final String[] splitCoefficients = affineString.split(",");
				for (int i = 0; i < 6; ++i)
					affine[i] = Double.parseDouble(splitCoefficients[i]);
				parsedSuccessfully = true;
			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		public AffineTransform2D getAffine() {
			final AffineTransform2D affineTransform = new AffineTransform2D();
			affineTransform.set(affine);
			return affineTransform;
		}

		public boolean isParsedSuccessfully() {
			return parsedSuccessfully;
		}

		public String getInputFormat() {
			return inputFormat;
		}

		public String getxPath() {
			return xPath;
		}

		public String getyPath() {
			return yPath;
		}

		public Integer getWidth() {
			return width;
		}

		public Integer getHeight() {
			return height;
		}

		public String getOutputFormat() {
			return outputFormat;
		}

		public int getFirst() {
			return first;
		}

		public int getLast() {
			return last;
		}
	}

	private static final void readDoubles(final String filePath, final Iterable<DoubleType> stream) throws IOException {
		final File file = new File(filePath);
		try (final DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
			for (final DoubleType t : stream)
				t.set(dis.readDouble());
		}
	}

	public static void main(final String... args) throws IllegalArgumentException, IOException, InterruptedException, ExecutionException {

		System.out.println("*************** Job started! ***************");

		final Options options = new Options(args);

		final ArrayList<Integer> indices = new ArrayList<>();
		for (int i = options.getFirst(); i <= options.getLast(); ++i)
			indices.add(i);

		final SparkConf conf      = new SparkConf().setAppName( "RenderMontages" );
        final JavaSparkContext sc = new JavaSparkContext(conf);

		final JavaRDD<Integer> rddIndices = sc.parallelize(indices);

		System.out.println("rddPaths count = " + rddIndices.count());

		final JavaPairRDD<Integer, String> paths = rddIndices.mapToPair(
				i -> new Tuple2<Integer, String>(i, String.format(options.getInputFormat(), i)));

		paths.collect().forEach(
				a -> System.out.println(a._1() + " : " + a._2()));

		final JavaRDD<String> outputs = paths.map(
				pair -> {
					final ImagePlus imp = new Opener().openImage(pair._2());
					final FloatProcessor ip = imp.getStack().getProcessor( 1 ).convertToFloatProcessor();

					final ArrayImg< DoubleType, ? > xPositions = ArrayImgs.doubles(options.getWidth(), options.getHeight());
					final ArrayImg< DoubleType, ? > yPositions = ArrayImgs.doubles(options.getWidth(), options.getHeight());
					readDoubles(options.getxPath(), xPositions );
					readDoubles(options.getyPath(), yPositions );

					final FloatProcessor ipTransformed = new ApplyFlow().run(
							ip,
							xPositions,
							yPositions,
							options.getWidth(),
							options.getHeight(),
							options.getAffine().inverse(),
							getLambda(pair._1(), options.getFirst(), options.getLast()));

					final String outputPath = String.format(options.getOutputFormat(), pair._1());
					IJ.saveAsTiff(new ImagePlus("", ipTransformed), outputPath);
					return outputPath;
				});

		outputs.collect().forEach(
				a -> System.out.println(a));


//		final JavaPairRDD<Double, ArrayList<String>> urls = rddZ.mapToPair(
//				new PairFunction<ArrayList<Double>, Double, ArrayList<String>>() {
//
//					@Override
//					public Tuple2<Double, ArrayList<String>> call(final ArrayList<Double> zs) throws Exception {
//						final ArrayList<String> urls = new ArrayList<>();
//						for (final Double z : zs) {
//							if (
//									options.getX() == null ||
//									options.getY() == null ||
//									options.getW() == null ||
//									options.getH() == null)
//								urls.add(String.format(String.format(urlString, "%f"), z));
//							else
//								urls.add(
//										String.format(
//											String.format(urlString, "%f/box/%f,%f,%d,%d,%f"),
//											z,
//											options.getX(),
//											options.getY(),
//											options.getW().intValue(),
//											options.getH().intValue(),
//											options.getScale()));
//						}
//						return new Tuple2<Double, ArrayList<String> >(zs.get(0), urls);
//					}
//				});
//
//		urls.cache();
//		System.out.println("urls count = " + urls.count());
//
//		final JavaPairRDD<Double, ArrayList<RenderParameters>> parameters = urls.mapToPair(
//				new PairFunction<Tuple2<Double, ArrayList<String>>, Double, ArrayList<RenderParameters>>() {
//
//					@Override
//					public Tuple2<Double, ArrayList<RenderParameters>> call(final Tuple2<Double, ArrayList<String>> pair) throws Exception {
//						final ArrayList<RenderParameters> parametersList = new ArrayList<>();
//						for (final String url : pair._2()) {
//							final RenderParameters parameters = RenderParameters.parseJson(new InputStreamReader(new URL(url).openStream()));
//
////							#### begin HACK to inject mask
//							for (final TileSpec spec : parameters.getTileSpecs()) {
//								final ImageAndMask mipmap = spec.getMipmap(0);
//								System.out.println(spec.getWidth());
//								final String maskUrl;
//								switch (spec.getWidth()) {
//								case 6500:
//									maskUrl = "file:///nrs/saalfeld/fly-em-slab-masks/mask6500.tif";
//									break;
//								case 6450:
//									maskUrl = "file:///nrs/saalfeld/fly-em-slab-masks/mask6450.tif";
//									break;
//								case 6375:
//									maskUrl = "file:///nrs/saalfeld/fly-em-slab-masks/mask6375.tif";
//									break;
//								default:
//									maskUrl = null;
//								}
//
//								spec.putMipmap(0, new ImageAndMask(mipmap.getImageUrl(), maskUrl));
//							}
////							#### end HACK
//
//							parameters.setScale(options.scale);
//							parameters.initializeDerivedValues();
//							parametersList.add(parameters);
//						}
//
//						return new Tuple2<Double, ArrayList<RenderParameters>>(
//								pair._1(),
//								parametersList);
//					}
//				});
//
//		parameters.cache();
////		System.out.println("parameters count = " + parameters.count());
//
//		final JavaRDD<Double> images = parameters.map(
//				new Function<Tuple2<Double, ArrayList<RenderParameters>>, Double>() {
//
//					@Override
//					public Double call(final Tuple2<Double, ArrayList<RenderParameters>> pair) throws Exception {
//
//						final ArrayList<RenderParameters> parametersList = pair._2();
//						final RenderParameters firstParameters = parametersList.get(0);
//						final BufferedImage box = firstParameters.openTargetImage();
//						final ImageStack stack = new ImageStack(box.getWidth(), box.getHeight());
//						for (final RenderParameters param : parametersList) {
//
//							final BufferedImage targetImage = param.openTargetImage();
//							final ByteProcessor ip = new ByteProcessor(targetImage.getWidth(), targetImage.getHeight());
//							//mpicbg.ij.util.Util.fillWithNoise(ip);
//							//ip.setValue(255);
//							//ip.fill();
//
//							targetImage.getGraphics().drawImage(ip.createImage(), 0, 0, null);
//
//							final Timer timer = new Timer();
//							timer.start();
//							try {
//	        					Render.render(
//	        							param.getTileSpecs(),
//	        							targetImage,
//	//        							param.getX(),
//	//        							param.getY(),
//	        							options.x,
//	        							options.y,
//	        							param.getRes(param.getScale()),
//	        							param.getScale(),
//	        							false,
//	        							1,
//	        							false,
//	        							false);
//							} catch (final IndexOutOfBoundsException e) {
//							    System.out.println("Failed rendering layer " + pair._1() + " because");
//							    e.printStackTrace(System.out);
//							}
//							timer.stop();
//
//							stack.addSlice(new ColorProcessor(targetImage).convertToByteProcessor());
//						}
//
//						final ZProjector projector = new ZProjector(new ImagePlus("", stack));
//						projector.setMethod( ZProjector.AVG_METHOD );
//						projector.doProjection();
//
//						ImageProcessor ip = projector.getProjection().getProcessor();
//						ip = new Invert().process( ip );
//						ip = new CLAHE( true, 255, 256, 5 ).process( ip, options.scale );
//
//						final BufferedImage targetImage = ip.getBufferedImage();
//						final String fileName = String.format("%s%08.1f%s", options.outputPath, pair._1(), ".png");
//						System.out.println(fileName);
//						Utils.saveImage(
//								targetImage,
//								fileName,
//								"png",
//								true,
//								9);
//
//						return pair._1();
//					}
//				});
//
////		images.persist(StorageLevel.DISK_ONLY());
//		System.out.println("images count = " + images.count());
//
////		final JavaPairRDD<Double, ArrayList<Feature>> features = images.mapToPair(
////				new PairFunction<Tuple2<Double, BufferedImage>, Double, ArrayList<Feature>>() {
////
////					@Override
////					public Tuple2<Double, ArrayList<Feature>> call(final Tuple2<Double, BufferedImage> pair) throws Exception {
////
////						final BufferedImage img = pair._2();
////						final ColorProcessor ip = new ColorProcessor(img);
////
////						final FloatArray2DSIFT.Param p = new FloatArray2DSIFT.Param();
////
////						p.fdSize = 4;
////						p.maxOctaveSize = 4000;
////						p.minOctaveSize = 1500;
////
////						final FloatArray2DSIFT sift = new FloatArray2DSIFT(p);
////						final SIFT ijSIFT = new SIFT(sift);
////
////						final ArrayList<Feature> fs = new ArrayList<Feature>();
////						ijSIFT.extractFeatures(ip, fs);
////						return new Tuple2<Double, ArrayList<Feature>>(pair._1(), fs);
////					}
////				});
////
////		features.cache();
////
////		final JavaPairRDD<Tuple2<Double, ArrayList<Feature>>, Tuple2<Double, ArrayList<Feature>>> cartesian = features.cartesian(features);
////
////		final JavaPairRDD<Tuple2<Double, Double>, Double> similarity = cartesian.mapToPair(
////				new PairFunction<Tuple2<Tuple2<Double, ArrayList<Feature>>, Tuple2<Double, ArrayList<Feature>>>, Tuple2<Double, Double>, Double>() {
////
////					@Override
////					public Tuple2<Tuple2<Double, Double>, Double> call(final Tuple2<Tuple2<Double, ArrayList<Feature>>, Tuple2<Double, ArrayList<Feature>>> pair) {
////
////						final float rod = 0.92f;
////						final float maxEpsilon = 50f;
////						final float minInlierRatio = 0.0f;
////						final int minNumInliers = 20;
////						final AffineModel2D model = new AffineModel2D();
////
////						final Double z1 = pair._1()._1();
////						final Double z2 = pair._2()._1();
////						final ArrayList<Feature> features1 = pair._1()._2();
////						final ArrayList<Feature> features2 = pair._2()._2();
////
////						double inlierRatio = 0;
////
////						if (features1.size() > 0 && features2.size() > 0) {
////							final ArrayList<PointMatch> candidates = new ArrayList<PointMatch>();
////							final ArrayList<PointMatch> inliers = new ArrayList<PointMatch>();
////
////							FeatureTransform.matchFeatures(features1, features2, candidates, rod);
////
////							boolean modelFound = false;
////							try {
////								modelFound = model.filterRansac(
////										candidates,
////										inliers,
////										1000,
////										maxEpsilon,
////										minInlierRatio,
////										minNumInliers,
////										3);
////							} catch (final NotEnoughDataPointsException e) {
////								modelFound = false;
////							}
////
////							if (modelFound)
////								inlierRatio = (double)inliers.size() / candidates.size();
////						}
////						return new Tuple2<Tuple2<Double, Double>, Double>(new Tuple2<Double, Double>(z1, z2), inlierRatio);
////					}
////				});
////
////		/* inverse z-position lookup */
////		final int n = zs.size();
////		final HashMap<Double, Integer> zLUT = new HashMap<Double, Integer>();
////		for (int i = 0; i < n; ++i)
////			zLUT.put(zs.get(i), i);
////
////		/* generate matrix */
////		final FloatProcessor matrix = new FloatProcessor(n, n);
////		matrix.add(Double.NaN);
////		for (int i = 0; i < n; ++i)
////			matrix.setf(i, i, 1.0f);
////
////		matrix.setMinAndMax(0, 1);
////
////		final float[] pixels = (float[])matrix.getPixels();
////
////		/* aggregate */
////		final float[] aggregate = similarity.aggregate(
////				pixels,
////				new Function2<float[], Tuple2<Tuple2<Double, Double>, Double>, float[]>() {
////
////					@Override
////					public float[] call(final float[] v1, final Tuple2<Tuple2<Double, Double>, Double> v2) throws Exception {
////						/* generate matrix */
////						final FloatProcessor matrix = new FloatProcessor(n, n, v1.clone());
////						final int x = zLUT.get(v2._1()._1());
////						final int y = zLUT.get(v2._1()._2());
////						final float v = (float)v2._2().doubleValue();
////						matrix.setf(x, y, v);
////						matrix.setf(y, x, v);
////
////						return (float[])matrix.getPixels();
////					}
////				},
////				new Function2<float[], float[], float[]>() {
////
////					@Override
////					public float[] call(final float[] v1, final float[] v2) throws Exception {
////						/* generate matrix */
////						final float[] v3 = v1.clone();
////						for (int i = 0; i < v3.length; ++i) {
////							final float v = v2[i];
////							if (!Float.isNaN(v))
////								v3[i] = v;
////						}
////
////						return v3;
////					}
////				});
////
////		IJ.saveAsTiff(new ImagePlus("matrix", new FloatProcessor(n, n, aggregate)), "/nobackup/saalfeld/tmp/matrix.tif");
//
//		System.out.println("Done.");
//
		sc.close();
	}
//
//
//
//
//	final static public void main2(final String... args) throws InterruptedException, ExecutionException, IOException {
//
//		final Options options = new Options(args);
//
//		final String baseUrlString = options.getServer() +
//                "/owner/" + options.getOwner() +
//                "/project/" + options.getProjectId() +
//                "/stack/" + options.getStackId();
//
//        final URL zValuesUrl = new URL(baseUrlString + "/zValues");
//		final JsonUtils.Helper<Double> jsonHelper = new JsonUtils.Helper<>(Double.class);
//		final List<Double> zs = jsonHelper.fromJsonArray(new InputStreamReader(zValuesUrl.openStream()));
//
//
//        final String urlString =
//                baseUrlString +
//                "/z/%f/box/" + (int)Math.round(options.getX()) + "," + (int)Math.round(options.getY()) + "," + (int)Math.round(options.getW()) + "," + (int)Math.round(options.getH()) + "," + options.getScale() +
//                "/render-parameters";
//
//		new ImageJ();
//
//		final ImageStack stack = new ImageStack(
//				(int)Math.ceil(options.getW() * options.getScale()),
//				(int)Math.ceil(options.getH() * options.getScale()));
//
//		ImagePlus imp = null;
//
//		final int numThreads = 24;
//		for (int i = 0; i < zs.size(); ++i) {
//		    final int fi = i;
//		    final ExecutorService exec = Executors.newFixedThreadPool(numThreads);
//			final ArrayList<Future<BufferedImage>> futures = new ArrayList<Future<BufferedImage>>();
//			for (int t = 0; t < numThreads && t + i <= zs.size(); ++t) {
//				final int ft = t;
//			    futures.add(exec.submit(new Callable<BufferedImage>() {
//
//					@Override
//					public BufferedImage call() throws IllegalArgumentException, IOException {
//						final URL url = new URL(String.format(urlString, zs.get(fi + ft)));
//						final RenderParameters param = RenderParameters.parseJson(new InputStreamReader(url.openStream()));
//
//						final BufferedImage targetImage = param.openTargetImage();
//						final ByteProcessor ip = new ByteProcessor(targetImage.getWidth(), targetImage.getHeight());
//						mpicbg.ij.util.Util.fillWithNoise(ip);
//						targetImage.getGraphics().drawImage(ip.createImage(), 0, 0, null);
//
//						Render.render(param.getTileSpecs(), targetImage, param.getX(), param.getY(), param.getRes(param.getScale()), param.getScale(),
//								param.isAreaOffset(), param.getNumberOfThreads(), param.skipInterpolation(), true);
//
//						return targetImage;
//					}
//				}));
//			}
//			for (int f = 0; f < futures.size(); ++f) {
//				stack.addSlice("" + zs.get(i + f), new ColorProcessor(futures.get(f).get()));
//			}
//			exec.shutdown();
//			if (i == 0) {
//				imp = new ImagePlus("export." + options.getProjectId() + "." + options.getStackId() + "." + options.getW() + "x" + options.getH() + "+" + options.getX() + "+" + options.getY(), stack);
//				imp.show();
//			} else {
//				imp.setStack(stack);
//				imp.updateAndDraw();
//			}
//		}
//		System.out.println("Done.");

	private static double getLambda(double current, double first, double last) {
		return Math.min(1.0, Math.max(0.0, 1.0 - (current - first) / (last - first)));
	}
}
