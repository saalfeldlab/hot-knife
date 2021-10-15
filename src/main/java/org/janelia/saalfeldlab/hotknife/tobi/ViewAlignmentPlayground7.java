package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import java.io.IOException;
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
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

// transform baking in a CellLoader
public class ViewAlignmentPlayground7 {

	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader(n5Path);

		final String transformGroup = passGroup + "/" + "flat.Sec33.bot.face";
		final String faceGroup = "/flat/Sec33/bot/face";

		final SurfacePyramid<?, ?> pyramid = new N5SurfacePyramid<>(n5, faceGroup);
		final PositionField positionField = new PositionField(n5, transformGroup);

		// set up transform to bake into positionField
		final double maxSlope=0.8;
		final double minSigma=100.0;
		final boolean active=true;
		final double sx0=3634.3391666666666;
		final double sy0=14456.360833333334;
		final double sx1=11067.172499999999;
		final double sy1=14679.345833333335;
		final GaussTransform movingTransform = new GaussTransform(maxSlope, minSigma);
		movingTransform.setLine(sx0, sy0, sx1, sy1);
		movingTransform.setActive(active);

		final TransformedSurfacePyramid<?, ?> tpyramid = new TransformedSurfacePyramid<>(
				pyramid,
				PositionFieldPyramid.createSingleLevelPyramid(positionField),
				movingTransform);
		final BdvStackSource<?> source = BdvFunctions.show(tpyramid.getSourceAndConverter(), Bdv.options().is2D());
		source.setDisplayRange(0, 255);
		source.setDisplayRangeBounds(0, 255);


		final Dimensions dims = positionField.getPositionFieldRAI();
		final int level = positionField.getLevel();
		final int blevel = 5;
		final double bscale = 1.0 / (1 << blevel);
		final long boffsetX = positionField.getOffset(0) >> (blevel - level);
		final long boffsetY = positionField.getOffset(1) >> (blevel - level);
		final int bsizeX = (int) dims.dimension(0) >> (blevel - level);
		final int bsizeY = (int) dims.dimension(1) >> (blevel - level);
		final Dimensions bdims = new FinalDimensions(bsizeX, bsizeY, 2);

		final int[] blockSize = {256, 256, 2};
		final Img<DoubleType> baked = Lazy.createImg(
				bdims, blockSize, new DoubleType(), AccessFlags.setOf(),
				new Bakery(positionField, movingTransform, blevel));

//		System.out.println("level = " + level);
//		System.out.println("dims = " + Intervals.toString(dims));
//		System.out.println();
//		System.out.println("bscale = " + bscale);
//		System.out.println("blevel = " + blevel);
//		System.out.println("bdims = " + Intervals.toString(bdims));
//		System.out.println("boffset = " + boffsetX + ", " + boffsetY);

		final PositionField bakedPositionField = new PositionField(baked,
				new long[] {boffsetX, boffsetY}, bscale,
				positionField.getBoundsMin(), positionField.getBoundsMax());
		final TransformedSurfacePyramid<?, ?> bakedtpyramid = new TransformedSurfacePyramid<>(pyramid,
				PositionFieldPyramid.createSingleLevelPyramid(bakedPositionField),
				IdentityTransform.get());
		final BdvStackSource<?> bsource = BdvFunctions.show(bakedtpyramid.getSourceAndConverter(), Bdv.options().is2D());
		bsource.setDisplayRange(0, 255);
		bsource.setDisplayRangeBounds(0, 255);
	}


	static class Bakery implements CellLoader<DoubleType> {
		private final PositionField positionField;
		private final RealTransform incrementalTransform;
		private final double scale;
		private final double invscale;
		private final long offsetX;
		private final long offsetY;

		public Bakery(
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
