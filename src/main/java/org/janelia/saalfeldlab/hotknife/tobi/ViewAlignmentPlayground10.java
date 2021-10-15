package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import java.io.IOException;
import net.imglib2.type.numeric.ARGBType;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;


// show RenderedSurfacePyramid: baked image from SurfacePyramid and PositionFieldPyramid
public class ViewAlignmentPlayground10 {

	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader(n5Path);

		final String transformGroup = passGroup + "/" + "flat.Sec33.bot.face";
		final String faceGroup = "/flat/Sec33/bot/face";

		final SurfacePyramid<?, ?> pyramid = new SurfacePyramid<>(n5, faceGroup);
		final PositionField positionField = new PositionField(n5, transformGroup);

		final int minLevel = positionField.getLevel(); // can be 0
		final int maxLevel = pyramid.getNumMipmapLevels() - 1;
		final PositionFieldPyramid positionFieldPyramid = PositionFieldPyramid.createSingleLevelPyramid(positionField);
//		final PositionFieldPyramid positionFieldPyramid = PositionFieldPyramid.createFullPyramid(positionField, 256, minLevel, maxLevel);

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

		// show on-the-fly transformed SurfacePyramid
		final TransformedSurfacePyramid<?, ?> tpyramid = new TransformedSurfacePyramid<>(
				pyramid,
				positionFieldPyramid,
				movingTransform);
		final BdvStackSource<?> source = BdvFunctions.show(tpyramid.getSourceAndConverter(), Bdv.options().is2D());
		source.setColor(new ARGBType(0xff0000));
		source.setDisplayRange(0, 255);
		source.setDisplayRangeBounds(0, 255);

		// show baked transformed SurfacePyramid
		final PositionFieldPyramid bakedPositionFields = Bake.bakePositionFieldPyramid(
				positionFieldPyramid,
				movingTransform, 256, minLevel, maxLevel);
		final PositionField bakedPositionField = Bake.bakePositionField(positionField, movingTransform, 5, 256 );
		final RenderedSurfacePyramid<?, ?> renderedSurfacePyramid = new RenderedSurfacePyramid<>(
				pyramid, bakedPositionFields, 32);
		final BdvStackSource<?> bsource = BdvFunctions.show(renderedSurfacePyramid.getSourceAndConverter(), Bdv.options().addTo(source));
		bsource.setColor(new ARGBType(0x00ff00));
		bsource.setDisplayRange(0, 255);
		bsource.setDisplayRangeBounds(0, 255);
	}
}
