package org.janelia.saalfeldlab.hotknife.tobi.old;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import java.io.IOException;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.RealPoint;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.BenchmarkHelper;
import net.imglib2.util.Intervals;
import org.janelia.saalfeldlab.hotknife.tobi.GaussTransform;
import org.janelia.saalfeldlab.hotknife.tobi.IdentityTransform;
import org.janelia.saalfeldlab.hotknife.tobi.N5SurfacePyramid;
import org.janelia.saalfeldlab.hotknife.tobi.PositionField;
import org.janelia.saalfeldlab.hotknife.tobi.PositionFieldPyramid;
import org.janelia.saalfeldlab.hotknife.tobi.SurfacePyramid;
import org.janelia.saalfeldlab.hotknife.tobi.TwiceTransformedSurfacePyramid;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

// bake transform into positionField at specified resolution level
public class ViewAlignmentPlayground6exp {

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
		final double sx0=3634.3391666666666;
		final double sy0=14456.360833333334;
		final double sx1=11067.172499999999;
		final double sy1=14679.345833333335;
		final GaussTransform incrementalTransform = new GaussTransform(maxSlope, minSigma);
		incrementalTransform.setLine(sx0, sy0, sx1, sy1);

		final TwiceTransformedSurfacePyramid<?, ?> tpyramid = new TwiceTransformedSurfacePyramid<>(
				pyramid,
				PositionFieldPyramid.createSingleLevelPyramid(positionField),
				incrementalTransform);
		final BdvStackSource<?> source = BdvFunctions.show(tpyramid.getSourceAndConverter(), Bdv.options().is2D());
		source.setDisplayRange(0, 255);
		source.setDisplayRangeBounds(0, 255);


		final Dimensions dims = positionField.getPositionFieldRAI();
		final RealTransform positionFieldTransform = positionField.getTransform(0);
		final double scale = positionField.getScale();
		final int level = positionField.getLevel();
		final long offsetX = positionField.getOffset(0);
		final long offsetY = positionField.getOffset(1);

		final int blevel = 6;
		final double binvscale = 1 << blevel;
		final double bscale = 1.0 / binvscale;
		final long boffsetX = positionField.getOffset(0) >> (blevel - level);
		final long boffsetY = positionField.getOffset(1) >> (blevel - level);
		final int bsizeX = (int) dims.dimension(0) >> (blevel - level);
		final int bsizeY = (int) dims.dimension(1) >> (blevel - level);
		final Dimensions bdims = new FinalDimensions(bsizeX, bsizeY, 2);

		System.out.println("scale = " + scale);
		System.out.println("level = " + level);
		System.out.println("dims = " + Intervals.toString(dims));
		System.out.println("offset = " + offsetX + ", " + offsetY);
		System.out.println();
		System.out.println("bscale = " + bscale);
		System.out.println("blevel = " + blevel);
		System.out.println("bdims = " + Intervals.toString(bdims));
		System.out.println("boffset = " + boffsetX + ", " + boffsetY);

		final ArrayImg<DoubleType, DoubleArray> baked = ArrayImgs.doubles(bsizeX, bsizeY, 2);

		final double[] tmp1 = new double[2];
		final double[] tmp2 = new double[2];
		final RealPoint tmp1p = RealPoint.wrap(tmp1);
		final RealPoint tmp2p = RealPoint.wrap(tmp2);

		BenchmarkHelper.benchmarkAndPrint(40, true, () -> {
			final double[] storage = baked.update(null).getCurrentStorageArray();
			final int zo = bsizeX * bsizeY;
			int o = 0;
			for (int y = 0; y < bsizeY; ++y) {
				for (int x = 0; x < bsizeX; ++x) {
					tmp1[0] = (x + boffsetX) * binvscale;
					tmp1[1] = (y + boffsetY) * binvscale;
					incrementalTransform.apply(tmp1p, tmp2p);
					positionFieldTransform.apply(tmp2p, tmp1p);
					storage[o] = tmp1[0] * bscale;
					storage[o + zo] = tmp1[1] * bscale;
					++o;
				}
			}
		});

		final PositionField bakedPositionField = new PositionField(baked,
				new long[] {boffsetX, boffsetY}, bscale,
				positionField.getBoundsMin(), positionField.getBoundsMax());
		final TwiceTransformedSurfacePyramid<?, ?> bakedtpyramid = new TwiceTransformedSurfacePyramid<>(
				pyramid,
				PositionFieldPyramid.createSingleLevelPyramid(bakedPositionField),
				IdentityTransform.get());
		final BdvStackSource<?> bsource = BdvFunctions.show(bakedtpyramid.getSourceAndConverter(), Bdv.options().is2D());
		bsource.setDisplayRange(0, 255);
		bsource.setDisplayRangeBounds(0, 255);
	}
}
