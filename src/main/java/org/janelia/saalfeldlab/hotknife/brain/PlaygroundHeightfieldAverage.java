package org.janelia.saalfeldlab.hotknife.brain;

import java.io.IOException;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;

public class PlaygroundHeightfieldAverage {

	public static void main(String[] args) throws IOException {
		final MyHeightField hf = new MyHeightField("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/", ".",
				new double[] {6, 6, 1},
				4658.6666161072235);
		final RandomAccessibleInterval<FloatType> heightfield = hf.heightfield();

		double sum = 0;
		for (final FloatType t : Views.iterable(heightfield)) {
			sum += t.getRealDouble();
		}
		final long n = Intervals.numElements(heightfield);
		System.out.println("n = " + n);
		System.out.println("sum = " + sum);
		System.out.println("avg = " + sum/n);
	}
}
