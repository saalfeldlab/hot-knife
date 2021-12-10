package org.janelia.saalfeldlab.hotknife.tools.util;

import org.janelia.saalfeldlab.hotknife.tools.ReviewFlattenedFace;

public class ReviewFlattenedFaceTest {

	public static void main(final String... args) throws Exception {

		final String testArgs = "--container /nrs/flyem/render/n5/Z0720_07m_BR --dataset /flat/Sec06/top13/face/s0";

		ReviewFlattenedFace.main(testArgs.split(" "));
	}

}
