package org.janelia.saalfeldlab.hotknife.tools.util;

import java.util.Arrays;
import java.util.List;

import org.janelia.saalfeldlab.hotknife.tools.ReviewFlattenedFace;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.junit.Test;

public class ReviewFlattenedFaceTest {

	public static void main(final String... args) throws Exception {

//		final String testArgs = "--container /nrs/flyem/render/n5/Z0720_07m_BR --dataset /flat/Sec06/top13/s4";
		final String testArgs = "--container /nrs/flyem/render/n5/Z0720_07m_BR --dataset /flat/Sec06/top13/s6";

		ReviewFlattenedFace.main(testArgs.split(" "));
	}

	@Test
	public void testGrid() {
		final int[] outBlockSize = {1024, 1024};

		showGrid("s4", new long[]{1770, 2660}, outBlockSize);
		showGrid("s5", new long[]{885, 1330}, outBlockSize);
		showGrid("s6", new long[]{442, 665}, outBlockSize);
	}

	private void showGrid(final String context,
						  final long[] absSize,
						  final int[] outBlockSize) {

		final long[] outDimensions = new long[]{absSize[0], absSize[1]};

		final List<long[][]> grid = Grid.create(outDimensions, outBlockSize);

		System.out.println("\n" + context + ":");
		for (long[][] column : grid) {
			System.out.println(Arrays.deepToString(column));
		}

	}

}
