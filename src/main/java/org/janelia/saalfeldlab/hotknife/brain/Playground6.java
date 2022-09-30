package org.janelia.saalfeldlab.hotknife.brain;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.ViewerPanel;
import ij.ImageJ;
import java.io.IOException;
import java.util.Arrays;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.ClampingNLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineRealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.InverseRealTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.ConstantUtils;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.hotknife.FlattenTransform;
import org.janelia.saalfeldlab.hotknife.brain.Playground3.MyHeightField;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import static org.janelia.saalfeldlab.hotknife.brain.Playground5.extendHeightfield;
import static org.janelia.saalfeldlab.hotknife.brain.Playground5.lscale;

public class Playground6 {

	// Apply heightfield transform to full volume
	public static void main(String[] args) throws IOException {

		System.setProperty("apple.laf.useScreenMenuBar", "true");

		// --------------------------------------------------------------------
		// load and crop image
		// (the crop region covers the full image in Y and Z)
		// --------------------------------------------------------------------
		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/s5/";
		final String imgGroup = ".";

		final N5Reader n5 = new N5FSReader(n5Path);
		final RandomAccessibleInterval<UnsignedByteType> imgBrain = N5Utils.openVolatile(n5, imgGroup);
		final int n5Level = 5;

		final long[] minIntervalS0 = {47204, 46557, 42756};
		final long[] maxIntervalS0 = {55779, 59038, 53664};
		final long[] minInterval = lscale(minIntervalS0, n5Level);
		final long[] maxInterval = lscale(maxIntervalS0, n5Level);
		System.out.println("minInterval = " + Arrays.toString(minInterval));
		System.out.println("maxInterval = " + Arrays.toString(maxInterval));
		final long[] translation = {-minInterval[1], -minInterval[2], maxInterval[0]};

		final RandomAccessibleInterval<UnsignedByteType> crop2 = Views.rotate(imgBrain, 1, 0 );
		final RandomAccessibleInterval<UnsignedByteType> crop3 = Views.permute(crop2, 1, 2 );
		final IntervalView<UnsignedByteType> crop = Views.translate(crop3, translation);
//		final RandomAccess<UnsignedByteType> a = crop.randomAccess();
//		a.setPosition(new long[] {0,0,0});



		// --------------------------------------------------------------------
		// load and expand heightfield
		// --------------------------------------------------------------------
		final MyHeightField hf = new MyHeightField("/Users/pietzsch/Desktop/data/janelia/Z0720_07m_VNC/heightfield/", ".", new double[] {6, 6, 1}, 4658.6666161072235);
		final RandomAccessibleInterval<FloatType> heightfield = hf.heightfield();
		final double avg = hf.avg();
		final double[] plane = {2.004294094052206, -1.8362464688517335, 4243.432822291761};
		final int fadeToPlaneDist = 1000;
		final int fadeToAvgDist = 2000;
		final RandomAccessible<FloatType> extendHeightfield = extendHeightfield(heightfield, avg, plane, fadeToPlaneDist, fadeToAvgDist);



		// --------------------------------------------------------------------
		// flatten crop with expanded heightfield
		// --------------------------------------------------------------------
		final double[] hfDownsamplingFactors = hf.downsamplingFactors();
		final double[] n5DownsamplingFactors = {1 << n5Level, 1 << n5Level, 1 << n5Level};
		final double[] hfRelativeScale = new double[3];
		Arrays.setAll(hfRelativeScale, d -> hfDownsamplingFactors[d] / n5DownsamplingFactors[d]);
		final RealRandomAccessible<DoubleType> scaledHeightfield = Transform.scaleAndShiftHeightFieldAndValues(extendHeightfield, hfRelativeScale);

		final double scaledAvg = (avg + 0.5) * hfRelativeScale[2] - 0.5;

//		final long max = maxInterval[0] - minInterval[0];
		final long max = 500;
		final RealTransform flatten = extendFlattenTransform(scaledHeightfield, scaledAvg, max, 1000);
		final RealRandomAccessible<UnsignedByteType> flattenedCrop = new RealTransformRealRandomAccessible<>(
				Views.interpolate(
						Views.extendValue(crop, new UnsignedByteType()),
						new ClampingNLinearInterpolatorFactory<>()),
				flatten);


		{
			final AffineTransform3D translate = new AffineTransform3D();
			translate.translate(0, 0, -(scaledAvg + 1));

			final long[] cmin = crop.minAsLongArray();
			final long[] cmax = crop.maxAsLongArray();
			cmin[2] = 0;
			cmax[2] = 0;

			final IntervalView<UnsignedByteType> thick = Views.interval(
					Views.raster(RealViews.affineReal(flattenedCrop, translate)),
					new FinalInterval(cmin, cmax));
			new ImageJ();
			ImageJFunctions.show(Views.hyperSlice(thick, 2, 0));
		}







		// --------------------------------------------------------------------
		// show in BDV: crop, flattened crop, extended heightfield
		// --------------------------------------------------------------------
		final BdvSource bdv = BdvFunctions.show(VolatileViews.wrapAsVolatile(crop), "crop", Bdv.options());
		final BdvSource flattenedCropSource = BdvFunctions.show(flattenedCrop, crop, "flattened", Bdv.options().addTo(bdv));

		final AffineTransform3D uncrop = new AffineTransform3D();
		uncrop.set(
				0, 1, 0, translation[0],
				0, 0, 1, translation[1],
				-1, 0, 0, translation[2] );
		final RealRandomAccessible<UnsignedByteType> flattenedBrain = RealViews.affineReal(flattenedCrop, uncrop.inverse());
		final BdvSource bdv2 = BdvFunctions.show(VolatileViews.wrapAsVolatile(imgBrain), "imgBrain", Bdv.options());
		final BdvSource flattenedBrainSource = BdvFunctions.show(flattenedBrain, imgBrain, "flattenedBrain", Bdv.options().addTo(bdv2));



		final RandomAccessible<DoubleType> hfExpanded = Views.addDimension(Views.raster(scaledHeightfield));//, crop.min(2), crop.max(2));
		final BdvSource hfSource = BdvFunctions.show(Views.interval(hfExpanded, crop), "heightfield", Bdv.options().addTo(bdv));
		hfSource.setDisplayRangeBounds(0, 255);
		hfSource.setDisplayRange(0, 255);
		hfSource.setColor(new ARGBType(0x00ff00));

		final ViewerPanel viewerPanel = bdv.getBdvHandle().getViewerPanel();
		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(viewerPanel);
		viewerPanel.getDisplay().overlays().add(overlay);
	}


	/**
	 * Transform that behaves like a {@code FlattenTransform} from {@code
	 * minHeightfield} at {@code z=minZ} to constant height {@code maxZ} at
	 * {@code z=maxZ}. For {@code z>maxZ}, the transformation is identity.
	 * For {@code z<minZ} the heightfield exponentially fades to identity at
	 * distance {@code fadeDist}, approximately.
	 */
	public static RealTransform extendFlattenTransform(
			final RealRandomAccessible<DoubleType> minHeightfield,
			final double minZ,
			final double maxZ,
			final double fadeDist)
	{
		final RealRandomAccessible<DoubleType> maxHeightfield = ConstantUtils.constantRealRandomAccessible(new DoubleType(maxZ), 2);
		final FlattenTransform<?> flattenTransform = new FlattenTransform<>(
				minHeightfield,
				maxHeightfield,
				minZ, maxZ);
		return new ExtendedFlattenTransform(flattenTransform.inverse(), minZ, maxZ, fadeDist);
	}

	static class ExtendedFlattenTransform implements RealTransform {

		private final RealTransform invFlatten;
		private final double minZ;
		private final double maxZ;
		private double fadeDist;

		final RealPoint flattened = new RealPoint(3);

		ExtendedFlattenTransform(final RealTransform invFlatten, final double minZ, final double maxZ, final double fadeDist) {
			assert invFlatten.numSourceDimensions() == 3;
			assert invFlatten.numTargetDimensions() == 3;
			this.invFlatten = invFlatten;
			this.minZ = minZ;
			this.maxZ = maxZ;
			this.fadeDist = fadeDist;
		}

		@Override
		public int numSourceDimensions() {
			return 3;
		}

		@Override
		public int numTargetDimensions() {
			return 3;
		}

		@Override
		public void apply(final double[] source, final double[] target) {
			apply(RealPoint.wrap(source), RealPoint.wrap(target));
		}

		@Override
		public void apply(final RealLocalizable source, final RealPositionable target) {
			final double z = source.getDoublePosition(2);
			if (z > maxZ)
				target.setPosition(source);
			else if (z > minZ)
				invFlatten.apply(source, target);
			else {
				final double dd = ( minZ - z ) * 4.0 / fadeDist;
				final double s = Math.exp(dd * dd / (-2.0));
				invFlatten.apply(source, flattened);
				final double fz = flattened.getDoublePosition(2);
				target.setPosition(source.getDoublePosition(0), 0);
				target.setPosition(source.getDoublePosition(1), 1);
				target.setPosition(s * (fz - z) + z, 2);
			}
		}

		@Override
		public RealTransform copy() {
			return new ExtendedFlattenTransform(invFlatten.copy(), minZ, maxZ, fadeDist);
		}
	}
}
