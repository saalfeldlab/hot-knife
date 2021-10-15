package org.janelia.saalfeldlab.hotknife.tobi;

import java.util.ArrayList;
import java.util.List;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RealPoint;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.img.Img;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Lazy;

public class Bake {

	/**
	 * TODO javadoc
	 *
	 * @param positionField
	 * @param concatenatedTransform
	 * @param level desired resolution level of output position field
	 * @param blockWidth
	 * @return
	 */
	public static PositionField bakePositionField(
			final PositionField positionField,
			final RealTransform concatenatedTransform,
			final int level,
			final int blockWidth ) {
		final double[] boundsMin = positionField.getBoundsMin();
		final double[] boundsMax = positionField.getBoundsMax();
		final double scale = 1.0 / (1 << level);
		final long[] min = Grid.floorScaled(boundsMin, scale);
		final long[] max = Grid.ceilScaled(boundsMax, scale);
		final long[] dimensions = {
				max[0] - min[0] + 1,
				max[1] - min[1] + 1,
				2};
		final int[] blockSize = {
				blockWidth,
				blockWidth,
				2};
		final BakedPositionFieldLoader loader = new BakedPositionFieldLoader(positionField, concatenatedTransform, level);
		final Img<DoubleType> baked = Lazy.createImg(
				new FinalDimensions(dimensions), blockSize, new DoubleType(), AccessFlags.setOf(),
				loader);

		return new PositionField(baked, scale, boundsMin, boundsMax);
	}

	/**
	 * TODO javadoc
	 *
	 * @param positionFieldPyramid
	 * @param concatenatedTransform
	 * @param blockWidth
	 * @return
	 */
	public static PositionFieldPyramid bakePositionFieldPyramid(
			final PositionFieldPyramid positionFieldPyramid,
			final RealTransform concatenatedTransform,
			final int blockWidth) {
		final int minLevel = positionFieldPyramid.getMinLevel();
		final int maxLevel = positionFieldPyramid.getMaxLevel();
		return bakePositionFieldPyramid(positionFieldPyramid, concatenatedTransform, blockWidth, minLevel, maxLevel);
	}

	/**
	 * TODO javadoc
	 *
	 * @param positionFieldPyramid
	 * @param concatenatedTransform
	 * @param blockWidth
	 * @param minLevel
	 * @param maxLevel
	 * @return
	 */
	public static PositionFieldPyramid bakePositionFieldPyramid(
			final PositionFieldPyramid positionFieldPyramid,
			final RealTransform concatenatedTransform,
			final int blockWidth,
			final int minLevel,
			final int maxLevel) {

		final List<PositionField> positionFields = new ArrayList<>();
		for (int level = minLevel; level <= maxLevel; ++level) {
			positionFields.add(
					bakePositionField(
							positionFieldPyramid.getPositionField(level),
							concatenatedTransform,
							level,
							blockWidth
					)
			);
		}

		return new PositionFieldPyramid(positionFields, minLevel, maxLevel);
	}

	/**
	 * A {@code CellLoader<DoubleType>} that produces position field vectors,
	 * from a source {@code PositionField} and a concatenated {@code RealTransform}.
	 * <p>
	 * The scale level of the produced position field must not match the scale
	 * level of the source position field.
	 */
	static class BakedPositionFieldLoader implements CellLoader<DoubleType> {
		private final PositionField positionField;
		private final RealTransform incrementalTransform;
		private final double scale;
		private final double invscale;
		private final long offsetX;
		private final long offsetY;

		public BakedPositionFieldLoader(
				final PositionField positionField,
				final RealTransform incremental,
				final int level) {
			this.positionField = positionField;
			this.incrementalTransform = incremental;
			invscale = 1 << level;
			scale = 1.0 / invscale;
			final long[] offset = Grid.floorScaled(positionField.getBoundsMin(), scale);
			offsetX = offset[ 0 ];
			offsetY = offset[ 1 ];
		}

		@Override
		public void load(final SingleCellArrayImg<DoubleType, ?> cell) throws Exception {
			final int coX = (int) cell.min(0);
			final int coY = (int) cell.min(1);
			final int csX = (int) cell.dimension(0);
			final int csY = (int) cell.dimension(1);

			final double[] tmp1 = new double[2];
			final double[] tmp2 = new double[2];
			final RealPoint tmp1p = RealPoint.wrap(tmp1);
			final RealPoint tmp2p = RealPoint.wrap(tmp2);

			final RealTransform positionFieldTransform = positionField.getTransform(0);
			final RandomAccess<DoubleType> a = cell.randomAccess();
			cell.min(a);
			for (int y = 0; y < csY; ++y) {
				for (int x = 0; x < csX; ++x) {
					tmp1[0] = (x + coX + offsetX) * invscale;
					tmp1[1] = (y + coY + offsetY) * invscale;
					incrementalTransform.apply(tmp1p, tmp2p);
					positionFieldTransform.apply(tmp2p, tmp1p);
					a.get().set(tmp1[0] * scale);
					a.fwd(2);
					a.get().set(tmp1[1] * scale);
					a.bck(2);
					a.fwd(0);
				}
				a.setPosition(coX, 0);
				a.fwd(1);
			}
		}
	}
}

