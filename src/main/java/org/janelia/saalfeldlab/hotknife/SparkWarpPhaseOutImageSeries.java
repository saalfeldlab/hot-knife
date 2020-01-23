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
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.LongStream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;

import ij.IJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineRealRandomAccessible;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.real.FloatType;
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
public class SparkWarpPhaseOutImageSeries implements Callable<Void> {

	@Option(names = "--formatInput", required = true, description = "format string for input images, e.g. ")
	private String formatInput = null;

	@Option(names = "--formatOutput", required = true, description = "format string for output images, e.g. ")
	private String formatOutput = null;

	@Option(names = "--range", required = true, description = "index range (inclusive) [min,max], e.g. 20,30")
	private long[] range;

	@Option(names = "--xField", required = true, description = "path to the x-coordinates of the deformation field, e.g. ")
	private String xFieldPath;

	@Option(names = "--yField", required = true, description = "path to the y-coordinates of the deformation field, e.g. ")
	private String yFieldPath;

	@Option(names = "--scale", required = true, description = "inverse scale factor of the deformation field, e.g. 2")
	private double scale;

	@Override
	public Void call() {

		final SparkConf conf = new SparkConf().setAppName(this.getClass().getCanonicalName());
		final JavaSparkContext sc = new JavaSparkContext(conf);
//		sc.setLogLevel("ERROR");

		final int nExecutors = Math.max(1, Integer.parseInt(sc.getConf().get("spark.executor.instances", "1")) - 1);
		final int nCores = nExecutors * Integer.parseInt(sc.getConf().get("spark.executor.cores", "1"));

		final long[] indices = LongStream.rangeClosed(range[0], range[1]).toArray();
		final ArrayList<Tuple2<Double, Long>> lambdaIndex = new ArrayList<>();
		for (int i = 0; i < indices.length; ++i) {
			 lambdaIndex.add(new Tuple2<Double, Long>((indices.length - 1.0 - i) / indices.length, indices[i]));
		}

		final JavaRDD<Tuple2<Double, Long>> lambdaIndexRDD = sc.parallelize(lambdaIndex);

		lambdaIndexRDD.foreach(tuple -> {

			/* load warp field */
			final ImagePlus impXField = IJ.openImage(xFieldPath);
			final ImagePlus impYField = IJ.openImage(yFieldPath);
			final ImagePlusImg<FloatType, ?> xImg = ImagePlusImgs.from(impXField);
			final ImagePlusImg<FloatType, ?> yImg = ImagePlusImgs.from(impXField);

			/* scale warp field grid */
			final double offset = scale * 0.5 - 0.5;
			final ScaleAndTranslation transform =
					new ScaleAndTranslation(
							new double[] {scale, scale},
							new double[] {offset, offset});
//			final Scale2D transform = new Scale2D(scale, scale);

			final AffineRealRandomAccessible<FloatType, AffineGet> xReal = RealViews.affineReal(
					Views.interpolate(
							Views.extendBorder(xImg),
							new NLinearInterpolatorFactory<>()),
					transform);

			final AffineRealRandomAccessible<FloatType, AffineGet> yReal = RealViews.affineReal(
					Views.interpolate(
							Views.extendBorder(yImg),
							new NLinearInterpolatorFactory<>()),
					transform);

			/* scale warp field values */
			final RealRandomAccessible<FloatType> xRealScaled = Converters.convert(
					xReal,
					(a, b) -> b.setReal(a.get() * tuple._1()),
					new FloatType());

			final RealRandomAccessible<FloatType> yRealScaled = Converters.convert(
					yReal,
					(a, b) -> b.setReal(a.get() * tuple._1()),
					new FloatType());

			/* warp field */
			final DeformationFieldTransform<FloatType> warp =
					new DeformationFieldTransform<>(
							(RealRandomAccessible<FloatType>[])new RealRandomAccessible[] {
								xRealScaled,
								yRealScaled
							});

			final String inputPath = String.format(formatInput, tuple._2());
			final ImagePlus imp = IJ.openImage(inputPath);
			final ImagePlusImg img = ImagePlusImgs.from(imp);
			final RealRandomAccessible<?> imgReal = Views.interpolate(
					Views.extendValue(img, ((Type)Util.getTypeFromInterval(img)).createVariable()),
					new NLinearInterpolatorFactory());

			final RandomAccessibleInterval warped = Views.interval(
					new RealTransformRandomAccessible<>(
							imgReal,
							warp),
					img);

			/* materialize */
			final ImagePlusImg target = img.factory().create(img);
			final Cursor<Type> src = Views.flatIterable(warped).cursor();
			final Cursor<Type> tgt = target.cursor();
			while (tgt.hasNext()) {
				tgt.next().set(src.next());
			}

			final String outPath = String.format(formatOutput, tuple._2());

			IJ.save(target.getImagePlus(), outPath);
		});

		sc.close();

		return null;
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new SparkWarpPhaseOutImageSeries(), args);
	}
}
