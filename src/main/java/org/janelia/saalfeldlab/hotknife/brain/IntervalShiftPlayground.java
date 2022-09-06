package org.janelia.saalfeldlab.hotknife.brain;

import bdv.tools.brightness.ConverterSetup;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.viewer.ConverterSetups;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.SynchronizedViewerState;
import java.util.List;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import org.janelia.saalfeldlab.hotknife.util.Transform;

public class IntervalShiftPlayground {

	static Img<UnsignedByteType> checkerboard(final int w, final int h, final int cw, final int ch) {
		Img<UnsignedByteType> img = ArrayImgs.unsignedBytes(w, h);
		Cursor<UnsignedByteType> c = img.localizingCursor();
		while (c.hasNext()) {
			c.fwd();
			final int x = c.getIntPosition(0);
			final int y = c.getIntPosition(1);
			final int i = ((x / cw) + (y / ch)) % 2;
			c.get().set(i == 0 ? 255 : 0);
		}
		return img;
	}

	public static void main(String[] args) {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final Img<UnsignedByteType> rai = checkerboard(1000, 1000, 20, 20);
		final Bdv bdv = BdvFunctions.show(rai, "image", Bdv.options().is2D());

		final FinalInterval interval = Intervals.createMinSize(760, 210, 20, 300);
		final double[] translation = {10, 50};
		final double[] weights = {100, 50};
		final RealTransform transformFromSource = new IntervalShift(interval, translation, weights);

		final UnsignedByteType background = new UnsignedByteType(100);
		final RandomAccessibleInterval<UnsignedByteType> transformed = Transform.createTransformedInterval(
				rai,
				Intervals.expand(rai, 1000),
				transformFromSource,
				background);

		BdvFunctions.show(transformed, "transformed", Bdv.options().addTo(bdv));

		final SynchronizedViewerState state = bdv.getBdvHandle().getViewerPanel().state();
		final ConverterSetups setups = bdv.getBdvHandle().getConverterSetups();
		final List<SourceAndConverter<?>> sources = state.getSources();
		final List<ConverterSetup> converterSetups = setups.getConverterSetups(sources);
		converterSetups.get(0).setColor(new ARGBType(0x00ff00));
		converterSetups.get(1).setColor(new ARGBType(0xff00ff));
	}
}
