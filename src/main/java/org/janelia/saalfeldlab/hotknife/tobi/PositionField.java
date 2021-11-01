package org.janelia.saalfeldlab.hotknife.tobi;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealPositionable;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;


/**
 * A position field representing a 2D coordinate transform.
 * <p>
 * {@code PositionField} is either read from N5 (float64 with "scale",
 * "boundsMin", and "boundsMax" attributes), or constructed directly from a
 * {@code RandomAccessibleInterval<DoubleType>}.
 * <p>
 * The represented 2D {@code RealTransform} can be obtained via {@link
 * #getTransform(int)}. Optionally, an incremental 2D {@code RealTransform} can
 * be concatenated via {@link #getTransform(int, RealTransform)}.
 */
public class PositionField {

	private final RandomAccessibleInterval<DoubleType> positionField;
	private final RealTransform positionFieldLookup;
	private final long[] offset;
	private final double scale;
	private final double[] boundsMin;
	private final double[] boundsMax;

	public PositionField(final N5Reader n5, final String datasetName)
			throws IOException {
		scale = n5.getAttribute(datasetName, "scale", double.class);
		boundsMin = n5.getAttribute(datasetName, "boundsMin", double[].class);
		boundsMax = n5.getAttribute(datasetName, "boundsMax", double[].class);
		offset = Grid.floorScaled(boundsMin, scale);
		positionField = N5Utils.open(n5, datasetName);
//		System.out.println("positionField = " + Intervals.toString(positionField));
		positionFieldLookup = Transform.createPositionFieldTransform(positionField);
	}

	public void write(final N5Writer n5, final String datasetName, int[] blockSize) throws IOException {
		System.out.println("PositionField.write");
		System.out.println("n5 = " + n5 + ", datasetName = " + datasetName);
		final int nThreads = Runtime.getRuntime().availableProcessors();
		final ExecutorService exec = Executors.newFixedThreadPool( nThreads );
		try {
			N5Utils.save(positionField, n5, datasetName, blockSize, new GzipCompression(), exec);
			n5.setAttribute(datasetName, "scale", scale);
			n5.setAttribute(datasetName, "boundsMin", boundsMin);
			n5.setAttribute(datasetName, "boundsMax", boundsMax);
		} catch (InterruptedException | ExecutionException e) {
			throw new IOException(e);
		} finally {
			exec.shutdown();
		}
	}

	public PositionField(final RandomAccessibleInterval<DoubleType> positionField, final double scale, final double[] boundsMin, final double[] boundsMax)
	{
		this.positionField = positionField;
		this.scale = scale;
		this.boundsMin = boundsMin;
		this.boundsMax = boundsMax;
		offset = Grid.floorScaled(boundsMin, scale);
		positionFieldLookup = Transform.createPositionFieldTransform(positionField);
	}

	// TODO: remove / deprecate ?
	public PositionField(final RandomAccessibleInterval<DoubleType> positionField, final long[] offset, final double scale, final double[] boundsMin, final double[] boundsMax)
	{
		this.positionField = positionField;
		this.offset = offset.clone();
		this.scale = scale;
		this.boundsMin = boundsMin;
		this.boundsMax = boundsMax;
		positionFieldLookup = Transform.createPositionFieldTransform(positionField);
	}

	public RealTransform getTransform(final int level) {
		return getTransform(level, IdentityTransform.get());
	}

	public RealTransform getTransform(final int level, final RealTransform incrementalTransform) {
		return new CombinedTransform(positionFieldLookup, scale, offset[0], offset[1], level, incrementalTransform);
	}

	public RandomAccessibleInterval<DoubleType> getPositionFieldRAI() {
		return positionField;
	}

	public long[] getOffset() {
		return offset;
	}

	public long getOffset(final int d) {
		return offset[d];
	}

	public double getScale() {
		return scale;
	}

	public int getLevel() {
		return (int) Math.round(-Math.log(scale) / Math.log(2));
	}

	public double[] getBoundsMin() {
		return boundsMin;
	}

	public double[] getBoundsMax() {
		return boundsMax;
	}

	private static class CombinedTransform implements RealTransform {

		private final RealTransform positionFieldLookup;
		private final double scale;
		private final double offsetX;
		private final double offsetY;

		// scale level that this Transform applies to
		private int level;

		// manual transform defined on scale level 0
		private final RealTransform incrementalTransform;

		// 1 << level
		private final double levelscale;

		// 1 / (scale * (1 << level))
		private final double invlevelscale;

		CombinedTransform(final RealTransform positionFieldTransform,
				final double scale,
				final double offsetX,
				final double offsetY,
				final int level,
				final RealTransform incrementalTransform) {
			this.positionFieldLookup = positionFieldTransform.copy();
			this.incrementalTransform = incrementalTransform.copy();

			levelscale = 1 << level;
			invlevelscale =  1.0 / (scale * (1 << level));

			this.scale = scale;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
			this.level = level;
		}

		@Override
		public int numSourceDimensions() {
			return 2;
		}

		@Override
		public int numTargetDimensions() {
			return 2;
		}

		@Override
		public void apply(final double[] source, final double[] target) {
			apply(RealPoint.wrap(source), RealPoint.wrap(target));
		}

		private final double[] tmp1 = new double[2];
		private final double[] tmp2 = new double[2];
		private final double[] tmp3 = new double[2];
		private final double[] tmp4 = new double[2];
		private final RealPoint tmp1p = RealPoint.wrap(tmp1);
		private final RealPoint tmp2p = RealPoint.wrap(tmp2);
		private final RealPoint tmp3p = RealPoint.wrap(tmp3);
		private final RealPoint tmp4p = RealPoint.wrap(tmp4);

		@Override
		public void apply(final RealLocalizable source, final RealPositionable target) {
			final double x = source.getDoublePosition(0);
			final double y = source.getDoublePosition(1);
			// scale by (1<<level)
			tmp1[ 0 ] = x * levelscale;
			tmp1[ 1 ] = y * levelscale;
			// apply manualTransform
			incrementalTransform.apply(tmp1p, tmp2p);
			// scale by transformScale and subtract offset
			tmp3[0] = scale * tmp2[0] - offsetX;
			tmp3[1] = scale * tmp2[1] - offsetY;
			// apply positionField lookup
			positionFieldLookup.apply(tmp3p, tmp4p);
			// scale by 1 / (transformScale * (1 << level))
			target.setPosition(tmp4[0] * invlevelscale, 0);
			target.setPosition(tmp4[1] * invlevelscale, 1);
		}

		@Override
		public RealTransform copy() {
			return new CombinedTransform(positionFieldLookup, scale, offsetX, offsetY, level, incrementalTransform);
		}
	}
}
