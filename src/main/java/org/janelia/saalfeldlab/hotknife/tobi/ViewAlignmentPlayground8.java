package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import java.io.IOException;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

// rasterize transformed image at particular resolution level
public class ViewAlignmentPlayground8 {

	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader(n5Path);

		final String transformGroup = passGroup + "/" + "flat.Sec33.bot.face";
		final String faceGroup = "/flat/Sec33/bot/face";

		final SurfacePyramid<?, ?> pyramid = new N5SurfacePyramid<>(n5, faceGroup);
//		BdvFunctions.show(pyramid.getSourceAndConverter(), Bdv.options().is2D());

		final PositionField positionField = new PositionField(n5, transformGroup);

		final int level = 6; // resolution we want to rasterize

		final double scale = 1.0 / (1 << level);
		final long[] min = Grid.floorScaled(positionField.getBoundsMin(), scale);
		final long[] max = Grid.ceilScaled(positionField.getBoundsMax(), scale);
		final long[] dims = new FinalInterval(min, max).dimensionsAsLongArray();
		final Img<FloatType> render = ArrayImgs.floats(dims); // rasterized image

		final RandomAccessibleInterval<FloatType> img = (RandomAccessibleInterval<FloatType>) pyramid.getImg(level);
		final RealTransformRandomAccessible<FloatType, RealTransform> timg = new RealTransformRandomAccessible<>(
				Views.interpolate(Views.extendZero(img), new ClampingNLinearInterpolatorFactory<>()),
				positionField.getTransform(level));

		final Cursor<FloatType> cursor = render.localizingCursor();
		final RandomAccess<FloatType> ra = Views.translateInverse(timg, min).randomAccess();
		while (cursor.hasNext()) {
			cursor.fwd();
			ra.setPosition(cursor);
			cursor.get().set(ra.get());
		}

		final AffineTransform3D st = new AffineTransform3D();
		st.set(1 << level, 0, 0, min[0] << level,
				0, 1 << level, 0, min[1] << level,
				0, 0, 1, 0);
		final BdvStackSource<?> source = BdvFunctions.show(render, "render", Bdv.options().is2D().sourceTransform(st));
		source.setDisplayRange(0, 255);
		source.setDisplayRangeBounds(0, 255);

		final TwiceTransformedSurfacePyramid<?, ?> tpyramid = new TwiceTransformedSurfacePyramid<>(
				pyramid,
				PositionFieldPyramid.createSingleLevelPyramid(positionField),
				IdentityTransform.get());
		final BdvStackSource<?> s2 = BdvFunctions.show(tpyramid.getSourceAndConverter(), Bdv.options().addTo(source));
		s2.setDisplayRange(0, 255);
		s2.setDisplayRangeBounds(0, 255);
	}
}
