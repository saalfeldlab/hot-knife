package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvSource;
import bdv.viewer.ViewerPanel;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.ToDoubleFunction;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.util.Localizables;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;

import static org.janelia.saalfeldlab.hotknife.brain.PlaygroundHeightfieldPlane.fitPlaneToHeightfield;

public class PlaygroundHeightfieldPlane2 {
	public static void main(String[] args) throws IOException {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final MyHeightField hf = new MyHeightField("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/", ".",
				new double[] {6, 6, 1},
				4658.6666161072235);
		final RandomAccessibleInterval<FloatType> heightfield = hf.heightfield();

		// plane[] = {a,b,c} represents the plane z = ax + by + c
		final double[] plane = fitPlaneToHeightfield(heightfield);
		System.out.println("plane = " + Arrays.toString(plane));


		// make a FunctionRandomAccessible that subtracts the plane from a heightfield
		final ThreadLocal<RandomAccess<FloatType>> hfAccess = ThreadLocal.withInitial(() -> heightfield.randomAccess());
		final RandomAccessible<FloatType> planeDiff = new FunctionRandomAccessible<>(2,
				(xy, t) -> {
					final double x = xy.getDoublePosition(0);
					final double y = xy.getDoublePosition(1);
					final RandomAccess<FloatType> a = hfAccess.get();
					a.setPosition(xy);
					final double z = a.get().getRealDouble();
					final double pz = plane[0] * x + plane[1] * y + plane[2];
					t.set((float) (z-pz));
				},
				FloatType::new);

		final RandomAccessible<FloatType> planeDiffExt = Views.extendBorder(Views.interval(planeDiff, heightfield));
		final ToDoubleFunction<Localizable> fadeDiff = intervalDistWeights(heightfield, 1000);
		final RandomAccessible<DoubleType> weights = new FunctionRandomAccessible<>(2,
				(pos, w) -> {
					w.set(fadeDiff.applyAsDouble(pos));
				}, DoubleType::new);

		final ThreadLocal<RandomAccess<FloatType>> pdAccess = ThreadLocal.withInitial(() -> planeDiffExt.randomAccess());
		final RandomAccessible<FloatType> planeDiffFade = new FunctionRandomAccessible<>(2,
				(xy, t) -> {
					final RandomAccess<FloatType> a = pdAccess.get();
					a.setPosition(xy);
					final double z = a.get().getRealDouble();
					t.set((float) (z * fadeDiff.applyAsDouble(xy)));
				},
				FloatType::new);
		final RandomAccessible<FloatType> planeFade = new FunctionRandomAccessible<>(2,
				(xy, t) -> {
					final double x = xy.getDoublePosition(0);
					final double y = xy.getDoublePosition(1);
					final RandomAccess<FloatType> a = pdAccess.get();
					a.setPosition(xy);
					final double z = a.get().getRealDouble() * fadeDiff.applyAsDouble(xy);
					final double pz = plane[0] * x + plane[1] * y + plane[2];
					t.set((float) (z + pz));
				},
				FloatType::new);

		final ToDoubleFunction<Localizable> fadePlane = intervalDistWeights(heightfield, 2000);
		final double avg = hf.avg();
		final RandomAccessible<FloatType> planeFadeToAvg = new FunctionRandomAccessible<>(2,
				(xy, t) -> {
					final double x = xy.getDoublePosition(0);
					final double y = xy.getDoublePosition(1);
					final RandomAccess<FloatType> a = pdAccess.get();
					a.setPosition(xy);
					final double z = a.get().getRealDouble() * fadeDiff.applyAsDouble(xy);
					final double pz = plane[0] * x + plane[1] * y + plane[2];

					final double w = fadePlane.applyAsDouble(xy);
					t.set((float) (avg + (z + pz - avg) * w));
				},
				FloatType::new);

		final Bdv bdv = BdvFunctions.show(heightfield, "heightfield", BdvOptions.options().is2D());
		BdvFunctions.show(Views.interval(planeDiff, heightfield),"planeDiff", BdvOptions.options().addTo(bdv));
		BdvFunctions.show(planeDiffExt, Intervals.expand(heightfield,1000),"planeDiff extended", BdvOptions.options().addTo(bdv));
		BdvSource ws = BdvFunctions.show(weights, Intervals.expand(heightfield,1000),"planeDiff fade weights", BdvOptions.options().addTo(bdv));
		ws.setDisplayRange(0,1);
		ws.setColor(new ARGBType(0x00ff00));
		BdvFunctions.show(planeDiffFade, Intervals.expand(heightfield,1000),"planeDiff extended faded", BdvOptions.options().addTo(bdv));
		BdvFunctions.show(planeFade, Intervals.expand(heightfield,1000),"plane extended faded", BdvOptions.options().addTo(bdv));
		BdvFunctions.show(planeFadeToAvg, Intervals.expand(heightfield,1000),"plane extended faded to avg", BdvOptions.options().addTo(bdv));

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);

	}

	/**
	 * @param fadeDist
	 * 		approximate distance to interval where weight fades to zero
	 */
	public static ToDoubleFunction<Localizable> intervalDistWeights(final RealInterval interval, final double fadeDist) {
		final double[] min = interval.minAsDoubleArray();
		final double[] max = interval.maxAsDoubleArray();
		final int n = interval.numDimensions();
		final double wFadeOut = 4.0 / fadeDist;
		return (pos) -> {
			double squdist = 0;
			for (int d = 0; d < n; d++) {
				final double x = pos.getDoublePosition(d);
				final double dd = distToInterval(x, min[d], max[d]) * wFadeOut;
				squdist += dd * dd;
			}
			return Math.exp(squdist / (-2.0));
		};
	}

	private static double distToInterval(final double x, final double min, final double max) {
		if (x < min)
			return min - x;
		else if (x > max)
			return x - max;
		else
			return 0;
	}

}
