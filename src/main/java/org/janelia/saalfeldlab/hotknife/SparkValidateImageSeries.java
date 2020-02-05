package org.janelia.saalfeldlab.hotknife;

import ij.IJ;
import ij.ImagePlus;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;

import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 * Validates images in a directory can be opened by ImageJ.
 *
 * @author Eric Trautman
 */
public class SparkValidateImageSeries
        implements Callable<Void>, Serializable {

	@Option(names = "--imageDirectory",
			required = true,
			description = "full path for directory containing images to be validated")
	private String imageDirectory = null;

	@SuppressWarnings("FieldCanBeLocal")
	@Option(names = "--imageExtension",
			description = "extension for image files to be validated (e.g. png)")
	private String imageExtension = null;

	@Override
	public Void call() throws IOException {

		final SparkConf conf = new SparkConf().setAppName(this.getClass().getCanonicalName());
		final JavaSparkContext sc = new JavaSparkContext(conf);

		final List<String> imagePaths =
				Files.list(Paths.get(imageDirectory))
						.filter(imagePath -> imagePath.toString().endsWith(imageExtension))
						.map(Path::toString)
						.collect(Collectors.toList());

		System.out.println("found " + imagePaths.size() + " images to validate");

		if (imagePaths.size() > 0) {

			System.out.println("first image is " + imagePaths.get(0));

			final JavaRDD<String> imagePathsRDD = sc.parallelize(imagePaths);

			imagePathsRDD.foreach(imagePath -> {
				final ImagePlus imp = IJ.openImage(imagePath);
				if (imp == null) {
					throw new IllegalArgumentException("failed to load " + imagePath);
				}
			});

		}

		sc.close();

		return null;
	}

	public static void main(final String... args) {
		 CommandLine.call(new SparkValidateImageSeries(), args);
	}
}
