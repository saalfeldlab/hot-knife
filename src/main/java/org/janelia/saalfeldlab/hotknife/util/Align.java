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
package org.janelia.saalfeldlab.hotknife.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.janelia.saalfeldlab.hotknife.ConsensusFilter;
import org.janelia.saalfeldlab.hotknife.NormalizeLocalContrast;
import org.janelia.saalfeldlab.hotknife.PMCCScaleSpaceBlockFlow;
import org.janelia.saalfeldlab.hotknife.ValueToNoise;

import ij.process.FloatProcessor;
import mpicbg.ij.FeatureTransform;
import mpicbg.ij.SIFT;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.PositionFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.Translation2D;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Align {

	private Align() {}

	static public void unScalePointMatches(
			final Collection<PointMatch> matches,
			final int scaleIndex) {

		final int scale = 1 << scaleIndex;
		matches.forEach(
				match -> {
					Util.scaleArray(match.getP1().getL(), scale);
					Util.scaleArray(match.getP1().getW(), scale);
					Util.scaleArray(match.getP2().getL(), scale);
					Util.scaleArray(match.getP2().getW(), scale);
				});
	}

	public static FloatProcessor addNoise(FloatProcessor ip) {

		final ValueToNoise filter1 = new ValueToNoise(0, 0, 255);
		final ValueToNoise filter2 = new ValueToNoise(255, 0, 255);
		final NormalizeLocalContrast filter3 = new NormalizeLocalContrast(256, 256, 3, true, true);

		ip = filter1.process(ip).convertToFloatProcessor();
		ip = filter2.process(ip).convertToFloatProcessor();
		ip.setMinAndMax(0, 255);
		ip = filter3.process(ip).convertToFloatProcessor();

		return ip;
	}

	/**
	 * Extract features from a {@link FloatProcessor}.  Also adds noise to
	 * 0 and 255 pixels because I believe that this does better on saturation
	 * and background.
	 *
	 * @param ip
	 * @param maxScale
	 * @param minScale
	 * @param fdSize
	 * @return
	 */
	public static ArrayList<Feature> extractFeatures(
			FloatProcessor ip,
			final double maxScale,
			final double minScale,
			final int fdSize) {

		final FloatArray2DSIFT.Param p = new FloatArray2DSIFT.Param();

		p.maxOctaveSize = (int)Math.round(Math.max(ip.getWidth(), ip.getHeight()) * maxScale);
		p.minOctaveSize = (int)(Math.min(ip.getWidth(), ip.getHeight()) * minScale);

		ip = addNoise(ip);

		final FloatArray2DSIFT sift = new FloatArray2DSIFT(p);
		final SIFT ijSIFT = new SIFT(sift);

		final ArrayList<Feature> fs = new ArrayList<Feature>();
		ijSIFT.extractFeatures(ip, fs);

		return fs;
	}

	/**
	 * Extract SIFT features from a 2D source.
	 *
	 * @param source
	 * @param maxScale
	 * @param minScale
	 * @param fdSize
	 * @return
	 */
	public static ArrayList<Feature> extractFeatures(
			final RandomAccessibleInterval<FloatType> source,
			final double maxScale,
			final double minScale,
			final int fdSize) {

		final ArrayList<Feature> fs = extractFeatures(Util.materialize(source), maxScale, minScale, fdSize);
		fs.forEach(
				feature -> {
					feature.location[0] += source.realMin(0);
					feature.location[1] += source.realMin(1);
				});
		return fs;
	}

	public static ArrayList<PointMatch> matchFeatures(
			final List<Feature> fs1,
			final List<Feature> fs2,
			final double rod) {

		final ArrayList<PointMatch> candidates = new ArrayList<>();
		FeatureTransform.matchFeatures(
				fs1,
				fs2,
				candidates,
				(float)rod);
		return candidates;
	}

	/**
	 * Align two images with SIFT features.  Returns the inverse transform
	 * of mapping a into b which is, well, the forward transform for mapping
	 * b into a.
	 *
	 * @param a
	 * @param b
	 * @param maxScale
	 * @param minScale
	 * @param fdSize
	 * @param rod
	 * @param scale
	 * @param filter
	 * @param modelSupplier
	 * @param modelTransformConverter
	 * @return
	 */
	static public <M extends Model<M>, R extends RealTransform> R alignSIFT(
			final RandomAccessibleInterval<FloatType> a,
			final RandomAccessibleInterval<FloatType> b,
			final double maxScale,
			final double minScale,
			final int fdSize,
			final double rod,
			final double scale,
			final ConsensusFilter filter,
			final Supplier<M> modelSupplier,
			final Function<M, R> modelTransformConverter) {

		final ArrayList<Feature> fs1 = extractFeatures(a, maxScale, minScale, fdSize);
		final ArrayList<Feature> fs2 = extractFeatures(b, maxScale, minScale, fdSize);
		fs1.forEach(
				feature -> {
					feature.location[0] *= scale;
					feature.location[1] *= scale;
				});
		fs2.forEach(
				feature -> {
					feature.location[0] *= scale;
					feature.location[1] *= scale;
				});

		final ArrayList<PointMatch> candidates = matchFeatures(fs1, fs2, rod);
		final ArrayList<PointMatch> matches = filter.filter(candidates);

		System.out.printf("%d and %d features extracted.  %d of %d matches found.", fs1.size(), fs2.size(), matches.size(), candidates.size());
		System.out.println();

		final M model = modelSupplier.get();
		try {
			model.fit(matches);
		} catch (final NotEnoughDataPointsException | IllDefinedDataPointsException e) {
//			e.printStackTrace();
			return null;
		}

		return modelTransformConverter.apply(model);
	}

	@SuppressWarnings("unchecked")
	public static <S extends Supplier<? extends Model<?>>> ArrayList<Tile<?>> connectStackTiles(
			final List<String> datasetNames,
			final Map<String, ArrayList<PointMatch>> matches,
			final S modelSupplier) {

		final ArrayList<Tile<?>> tiles = new ArrayList<>();
		@SuppressWarnings("rawtypes")
		Tile a = new Tile(modelSupplier.get());
		tiles.add(a);
		for (int i = 1; i < datasetNames.size() - 1; i += 2) {
			@SuppressWarnings("rawtypes")
			final Tile b = new Tile(modelSupplier.get());
			tiles.add(b);
			final ArrayList<PointMatch> match = matches.get(datasetNames.get(i));
			if (match != null) {
				a.connect(b, match);
				System.out.printf("[%s, %s] : connected by %d matches.", datasetNames.get(i), datasetNames.get(i + 1), match.size());
				System.out.println();
			}
			a = b;
		}

		return tiles;
	}

	/**
	 * Align two images with block matching.  Returns the inverse transform
	 * of mapping a into b which is, well, the forward transform for mapping
	 * b into a.
	 *
	 * @param a
	 * @param b
	 * @param radius
	 * @param scale
	 * @return
	 */
	static public RealTransform alignFlow(
			final RandomAccessibleInterval<FloatType> a,
			final RandomAccessibleInterval<FloatType> b,
			final short radius,
			final double sigma,
			final int numIterations) {

		final Pair<PositionFieldTransform<DoubleType>, FloatProcessor> transformAndWeights = PMCCScaleSpaceBlockFlow.scaleSpaceOpticFlow(
				Util.materialize(b),
				Util.materialize(a),
				radius,
				sigma,
				numIterations);

		final double[] offset = Intervals.minAsDoubleArray(a);
		final double[] inverseOffset = new double[offset.length];
		Arrays.setAll(inverseOffset, i -> -offset[i]);

		final RealTransformSequence transform = new RealTransformSequence();
		transform.add(new Translation2D(inverseOffset));
		transform.add(transformAndWeights.getA());
		transform.add(new Translation2D(offset));

		return transform;
	}
}
