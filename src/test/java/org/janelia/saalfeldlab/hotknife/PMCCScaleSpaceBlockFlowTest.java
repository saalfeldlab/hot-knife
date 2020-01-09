package org.janelia.saalfeldlab.hotknife;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import net.imglib2.realtransform.PositionFieldTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Pair;

public class PMCCScaleSpaceBlockFlowTest {

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	@Before
	public void setUp() throws Exception {
	}

	@After
	public void tearDown() throws Exception {
	}

	static public void main(final String... args) {

		new ImageJ();

		ImagePlus imp0 = IJ.openImage("/home/saalfeld/tmp/sharmi/img0.png");
		ImagePlus imp1 = IJ.openImage("/home/saalfeld/tmp/sharmi/img1.png");

		ImagePlus flowX = IJ.openImage("/home/saalfeld/tmp/sharmi/flowx_gt.png");
		ImagePlus flowY = IJ.openImage("/home/saalfeld/tmp/sharmi/flowy_gt.png");

		final FloatProcessor fpFlowX = flowX.getProcessor().convertToFloatProcessor();
		fpFlowX.add(-100);
		fpFlowX.multiply(0.1);
		final FloatProcessor fpFlowY = flowY.getProcessor().convertToFloatProcessor();
		fpFlowY.add(-100);
		fpFlowY.multiply(0.1);

		final short radius = (short)10;
		final int iterations = 1;

//		ImageJFunctions.show(
//				Show.compareTransforms(
//						new Translation2D(),
//						PMCCScaleSpaceBlockFlow.createDeformationFieldTransform(
//								fpFlowX,
//								fpFlowY),
//						Intervals.createMinSize(0, 0, imp0.getWidth(), imp0.getHeight()), iterations * radius),
//				);

		Pair<PositionFieldTransform<DoubleType>, FloatProcessor> result = PMCCScaleSpaceBlockFlow.scaleSpaceOpticFlow(
				imp0.getProcessor().convertToFloatProcessor(),
				imp1.getProcessor().convertToFloatProcessor(),
				radius,
				10.0,
				iterations);

//		ImageJFunctions.show(
//				Show.compareTransforms(
//						new Translation2D(),
//						result.getA(),
//						Intervals.createMinSize(0, 0, imp0.getWidth(), imp0.getHeight()),
//						iterations * radius));
	}
}
