package org.janelia.saalfeldlab.hotknife.tobi;

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
import org.janelia.saalfeldlab.hotknife.util.Lazy;

public class Bake {

	/**
	 *
	 * @param level desired resolution level of output position field
	 */
	static PositionField bakePositionField(
			final PositionField positionField,
			final RealTransform concatenatedTransform,
			final int level,
			final int blockSize ) {
		final Dimensions pfDims = positionField.getPositionFieldRAI();
		final int levelDist = level - positionField.getLevel();
		final long sizeX = pfDims.dimension(0) >> levelDist;
		final long sizeY = pfDims.dimension(1) >> levelDist;
		final Dimensions dims = new FinalDimensions(sizeX, sizeY, 2);

		final int[] blocks = {blockSize, blockSize, 2};

		final BakedPositionFieldLoader loader = new BakedPositionFieldLoader(positionField, concatenatedTransform, level);
		final Img<DoubleType> baked = Lazy.createImg(
				dims, blocks, new DoubleType(), AccessFlags.setOf(),
				loader);

		final double scale = 1.0 / (1 << level);
		final double[] boundsMin = positionField.getBoundsMin();
		final double[] boundsMax = positionField.getBoundsMax();
		return new PositionField(baked, scale, boundsMin, boundsMax);
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
			final int pfLevel = positionField.getLevel();
			invscale = 1 << level;
			scale = 1.0 / invscale;
			offsetX = positionField.getOffset(0) >> (level - pfLevel);
			offsetY = positionField.getOffset(1) >> (level - pfLevel);
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
