package org.janelia.saalfeldlab.hotknife.tobi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pyramid of {@code PositionField}s assumed to all represent the same position
 * field transform (in more or less detail).
 */
public class PositionFieldPyramid {

	private List<PositionField> positionFields;
	private final int minLevel;
	private final int maxLevel;

	/**
	 * @param positionFields
	 * 		a list of {@code PositionField}s ordered by increasing level.
	 * 		Assumed to all represent the same position field transform.
	 * @param minLevel
	 *      resolution level of the RAI underlying the first {@code
	 *      PositionField} in the list.
	 * @param maxLevel
	 *      resolution level of the RAI underlying the last {@code
	 *      PositionField} in the list.
	 */
	public PositionFieldPyramid(
			final List<PositionField> positionFields,
			final int minLevel,
			final int maxLevel) {
		this.positionFields = new ArrayList<>(positionFields);
		this.minLevel = minLevel;
		this.maxLevel = maxLevel;
	}

	public static PositionFieldPyramid createSingleLevelPyramid(final PositionField positionField) {
		return new PositionFieldPyramid(
				Collections.singletonList(positionField),
				positionField.getLevel(),
				positionField.getLevel());
	}

	public static PositionFieldPyramid createFullPyramid(
			final PositionField positionField,
			final int blockWidth,
			final int minLevel,
			final int maxLevel) {
		return Bake.bakePositionFieldPyramid(createSingleLevelPyramid(positionField), IdentityTransform.get(), blockWidth, minLevel, maxLevel);
	}

	/**
	 * Get the resolution level of the highest-resolution {@code PositionField}
	 * in the pyramid.
	 */
	public int getMinLevel() {
		return minLevel;
	}

	/**
	 * Get the resolution level of the lowest-resolution {@code PositionField}
	 * in the pyramid.
	 */
	public int getMaxLevel() {
		return maxLevel;
	}

	/**
	 * Returns the appropriate {@code PositionField} for the specified level.
	 * That is, clamped to {@link #getMinLevel() min}/{@link #getMaxLevel() max}
	 * available resolution level.
	 */
	public PositionField getPositionField(final int level) {
		return positionFields.get(Math.max(minLevel, Math.min(maxLevel, level)) - minLevel);
	}

	public double[] getBoundsMin() {
		return positionFields.get(0).getBoundsMin();
	}

	public double[] getBoundsMax() {
		return positionFields.get(0).getBoundsMax();
	}
}
