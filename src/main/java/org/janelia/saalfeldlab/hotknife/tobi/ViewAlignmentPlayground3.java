package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import java.io.IOException;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

public class ViewAlignmentPlayground3 {

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

		final TwiceTransformedSurfacePyramid<?, ?> tpyramid = new TwiceTransformedSurfacePyramid<>(
				pyramid,
				PositionFieldPyramid.createSingleLevelPyramid(positionField),
				IdentityTransform.get());
		final BdvStackSource<?> source = BdvFunctions.show(tpyramid.getSourceAndConverter(), Bdv.options().is2D());
		source.setDisplayRange(0, 255);
		source.setDisplayRangeBounds(0, 255);

	}
}
