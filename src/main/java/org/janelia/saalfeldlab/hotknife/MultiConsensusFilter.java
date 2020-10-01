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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class MultiConsensusFilter<M extends Model<?>> implements ConsensusFilter {

	final private Supplier<M> modelSupplier;
	final private int numIterations;
	final private double maxEpsilon;
	final private double minInlierRatio;
	final private int minNumInliers;

	public MultiConsensusFilter(
			final Supplier<M> modelSupplier,
			final int numIterations,
			final double maxEpsilon,
			final double minInlierRatio,
			final int minNumInliers) {

		this.modelSupplier = modelSupplier;
		this.numIterations = numIterations;
		this.maxEpsilon = maxEpsilon;
		this.minInlierRatio = minInlierRatio;
		this.minNumInliers = minNumInliers;
	}

	public ArrayList<ArrayList<PointMatch>> filterMultiConsensusSets(final List<PointMatch> candidates) {

		final ArrayList<ArrayList<PointMatch>> inliers = new ArrayList<>();

		final Model<?> model = modelSupplier.get();

		boolean modelFound = true;
		do {
			final ArrayList<PointMatch> modelInliers = new ArrayList<>();
			try {
				modelFound = model.filterRansac(
						candidates,
						modelInliers,
						numIterations,
						maxEpsilon,
						minInlierRatio,
						minNumInliers );
			}
			catch (final NotEnoughDataPointsException e) {
				modelFound = false;
			}

			if (modelFound) {
				inliers.add(modelInliers);
				candidates.removeAll(modelInliers);
			}
		} while (modelFound);

		return inliers;
	}

	@Override
	public ArrayList<PointMatch> filter(final List<PointMatch> candidates) {

		final ArrayList<PointMatch> inliers = new ArrayList<>();
		final ArrayList<ArrayList<PointMatch>> multiConsensusSets = filterMultiConsensusSets(candidates);

		multiConsensusSets.stream().forEach(consensusSet -> inliers.addAll(consensusSet));

		System.out.printf("Found %d consensus sets with %d inliers", multiConsensusSets.size(), inliers.size());
		System.out.println();

		return inliers;
	}
}
