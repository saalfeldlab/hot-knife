package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import java.io.IOException;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.img.Img;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Lazy;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import static net.imglib2.img.basictypeaccess.AccessFlags.VOLATILE;

// rasterize transformed image in a CellLoader
public class ViewAlignmentPlayground9 {

	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader(n5Path);

		final String transformGroup = passGroup + "/" + "flat.Sec33.bot.face";
		final String faceGroup = "/flat/Sec33/bot/face";

		final SurfacePyramid<?, ?> pyramid = new SurfacePyramid<>(n5, faceGroup);
//		BdvFunctions.show(pyramid.getSourceAndConverter(), Bdv.options().is2D());

		final PositionField positionField = new PositionField(n5, transformGroup);

		final int level = 0; // resolution we want to rasterize

		final double scale = 1.0 / (1 << level);
		final long[] min = Grid.floorScaled(positionField.getBoundsMin(), scale);
		final long[] max = Grid.ceilScaled(positionField.getBoundsMax(), scale);
		final Dimensions dims = new FinalInterval(min, max);
		System.out.println("dims = " + Intervals.toString(dims));

		final int[] blockSize = {256, 256};
		final Img<FloatType> baked = Lazy.createImg(
				dims, blockSize, new FloatType(), AccessFlags.setOf(VOLATILE),
				new Bakery(positionField, (SurfacePyramid<FloatType, VolatileFloatType>) pyramid, level));

		final AffineTransform3D st = new AffineTransform3D();
		st.set(1 << level, 0, 0, min[0] << level,
				0, 1 << level, 0, min[1] << level,
				0, 0, 1, 0);
		final SharedQueue queue = new SharedQueue(8, 1);
		final BdvStackSource<?> source = BdvFunctions.show(
				VolatileViews.wrapAsVolatile(baked, queue), "baked", Bdv.options().is2D().sourceTransform(st));
		source.setDisplayRange(0, 255);
		source.setDisplayRangeBounds(0, 255);

		final TransformedSurfacePyramid<?, ?> tpyramid = new TransformedSurfacePyramid<>(
				pyramid,
				PositionFieldPyramid.createSingleLevelPyramid(positionField),
				IdentityTransform.get());
		final BdvStackSource<?> s2 = BdvFunctions.show(tpyramid.getSourceAndConverter(), Bdv.options().addTo(source));
		s2.setDisplayRange(0, 255);
		s2.setDisplayRangeBounds(0, 255);
	}









	static class Bakery implements CellLoader<FloatType> {
		private final PositionField positionField;
		private final SurfacePyramid<FloatType, ?> pyramid;
		private final int level;

		public Bakery(
				final PositionField positionField,
				final SurfacePyramid<FloatType, ?> pyramid,
				final int level) {
			this.positionField = positionField;
			this.pyramid = pyramid;
			this.level = level;
		}

		@Override
		public void load(final SingleCellArrayImg<FloatType, ?> cell) throws Exception {
			final int coX = (int) cell.min(0);
			final int coY = (int) cell.min(1);
			final int csX = (int) cell.dimension(0);
			final int csY = (int) cell.dimension(1);

			final double scale = 1.0 / (1 << level);
			final long[] min = Grid.floorScaled(positionField.getBoundsMin(), scale);

			final RandomAccessibleInterval<FloatType> img = pyramid.getImg(level);
			final RealTransformRandomAccessible<FloatType, RealTransform> timg = new RealTransformRandomAccessible<>(
					Views.interpolate(Views.extendZero(img), new ClampingNLinearInterpolatorFactory<>()),
					positionField.getTransform(level));

			final RandomAccess<FloatType> in = Views.translateInverse(timg, min).randomAccess();
			final RandomAccess<FloatType> out = cell.randomAccess();
			cell.min(out);
			cell.min(in);
			for (int y = 0; y < csY; ++y) {
				for (int x = 0; x < csX; ++x) {
					out.get().set(in.get());
					out.fwd(0);
					in.fwd(0);
				}
				out.setPosition(coX, 0);
				out.fwd(1);
				in.setPosition(coX, 0);
				in.fwd(1);
			}
		}
	}
}
