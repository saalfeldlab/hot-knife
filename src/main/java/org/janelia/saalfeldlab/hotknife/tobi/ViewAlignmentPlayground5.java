package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import java.io.IOException;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RealPoint;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.iterator.LocalizingZeroMinIntervalIterator;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;


// bake transform into positionField
public class ViewAlignmentPlayground5 {

	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader(n5Path);

		final String transformGroup = passGroup + "/" + "flat.Sec33.bot.face";
		final String faceGroup = "/flat/Sec33/bot/face";

		final SurfacePyramid<?, ?> pyramid = new SurfacePyramid<>(n5, faceGroup);
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


		final Interval interval = positionField.getPositionFieldRAI();
		System.out.println("interval = " + Intervals.toString(interval));
		final Img<DoubleType> baked = new ArrayImgFactory<>(new DoubleType()).create(interval);
		final RealTransform positionFieldTransform = positionField.getTransform(0);
		final double scale = positionField.getScale();
		final double offsetX = positionField.getOffset(0);
		final double offsetY = positionField.getOffset(1);
		final double[] tmp1 = new double[2];
		final double[] tmp2 = new double[2];
		final RealPoint tmp1p = RealPoint.wrap(tmp1);
		final RealPoint tmp2p = RealPoint.wrap(tmp2);

		final IntervalIterator iter = new LocalizingZeroMinIntervalIterator(Intervals.hyperSlice(baked, 2));
		final RandomAccess<DoubleType> a = baked.randomAccess();
		while(iter.hasNext())
		{
			iter.fwd();
			final long x = iter.getLongPosition(0);
			final long y = iter.getLongPosition(1);

//			lookup'(l) = lookup(M((l+offset)/scale)*scale - offset)
//			lookup'(xy) = positionFieldTransform(
//							movingTransform(( xy+offsetXY)/scale )
//							* scale - offsetXY)
			tmp1[0] = (x + offsetX) / scale;
			tmp1[1] = (y + offsetY) / scale;
			movingTransform.apply(tmp1p, tmp2p);
			positionFieldTransform.apply(tmp2p, tmp1p);

			a.setPosition(x,0);
			a.setPosition(y,1);
			a.setPosition(0, 2);
			a.get().set(tmp1[0] * scale);
			a.setPosition(1, 2);
			a.get().set(tmp1[1] * scale);
		}

		final PositionField bakedPositionField = new PositionField(baked,
				positionField.getOffset(), positionField.getScale(),
				positionField.getBoundsMin(), positionField.getBoundsMax());
		final TransformedSurfacePyramid<?, ?> bakedtpyramid = new TransformedSurfacePyramid<>(
				pyramid,
				PositionFieldPyramid.createSingleLevelPyramid(bakedPositionField),
				IdentityTransform.get());
		final BdvStackSource<?> bsource = BdvFunctions.show(bakedtpyramid.getSourceAndConverter(), Bdv.options().is2D());
		bsource.setDisplayRange(0, 255);
		bsource.setDisplayRangeBounds(0, 255);
	}
}
