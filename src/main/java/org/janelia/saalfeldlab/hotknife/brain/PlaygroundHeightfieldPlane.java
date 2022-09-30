package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.viewer.ViewerPanel;
import java.io.IOException;
import java.util.Arrays;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.util.Localizables;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;

public class PlaygroundHeightfieldPlane {

	/**
	 * Fit plane to {@code heightfield}.
	 *
	 * @param heightfield
	 *
	 * @return an array {@code {a,b,c}}, representing the plane {@code z = ax + by + c}.
	 */
	public static  <T extends RealType<T>> double[] fitPlaneToHeightfield(final RandomAccessibleInterval<T> heightfield) {
		double sumX = 0;
		double sumXX = 0;
		double sumXY = 0;
		double sumXZ = 0;
		double sumY = 0;
		double sumYY = 0;
		double sumYZ = 0;
		double sumZ = 0;
		double sum1 = Intervals.numElements(heightfield);
		{
			final Cursor<T> cursor = Views.iterable(heightfield).localizingCursor();
			while (cursor.hasNext()) {
				final double z = cursor.next().getRealDouble();
				final double x = cursor.getDoublePosition(0);
				final double y = cursor.getDoublePosition(1);
				sumX += x;
				sumXX += x * x;
				sumXY += x * y;
				sumXZ += x * z;
				sumY += y;
				sumYY += y * y;
				sumYZ += y * z;
				sumZ += z;
			}
		}

		// plane[] = {a,b,c} represents the plane z = ax + by + c
		final double[] plane = new double[3];

		final double[][] A = {
				{sumXX, sumXY, sumX},
				{sumXY, sumYY, sumY},
				{sumX, sumY, sum1}};
		final double[] b = {sumXZ, sumYZ, sumZ};
		final double[][] invA = new double[3][3];
		LinAlgHelpers.invertSymmetric3x3(A, invA);
		LinAlgHelpers.mult(invA, b, plane);
		return plane;
	}



	public static void main(String[] args) throws IOException {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final MyHeightField hf = new MyHeightField("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/", ".",
				new double[] {6, 6, 1},
				4658.6666161072235);
		final RandomAccessibleInterval<FloatType> heightfield = hf.heightfield();

		// plane[] = {a,b,c} represents the plane z = ax + by + c
		final double[] plane = fitPlaneToHeightfield(heightfield);
		System.out.println("plane = " + Arrays.toString(plane));

		// subtract the plane from the heightfield.
		final Img<FloatType> diff = ArrayImgs.floats(heightfield.dimensionsAsLongArray());
		LoopBuilder.setImages(
						Localizables.randomAccessibleInterval(heightfield),
						heightfield,
						diff)
				.forEachPixel((xy, z, o) -> {
					final double x = xy.getDoublePosition(0);
					final double y = xy.getDoublePosition(1);
					final double pz = plane[0] * x + plane[1] * y + plane[2];
					o.set(z.get() - (float) pz);
				});

		final Bdv bdv = BdvFunctions.show(heightfield, "heightfield", BdvOptions.options().is2D());
		BdvFunctions.show(diff, "diff", BdvOptions.options().addTo(bdv));

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);

	}
}
