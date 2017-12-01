package org.janelia.saalfeldlab.hotknife;

import java.util.Random;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

public class ValueToNoise
{
	final static private void processFloatNaN(final FloatProcessor ip, final double min, final double max) {
		final double scale = max - min;
		final Random rnd = new Random();
		final int n = ip.getWidth() * ip.getHeight();
		for (int i =0; i < n; ++i) {
			final float v = ip.getf(i);
			if (Float.isNaN(v))
				ip.setf(i, (float)(rnd.nextDouble() * scale + min));
		}
	}

	final static private void processFloat(final FloatProcessor ip, final float value, final double min, final double max) {
		final double scale = max - min;
		final Random rnd = new Random();
		final int n = ip.getWidth() * ip.getHeight();
		for (int i =0; i < n; ++i) {
			final float v = ip.getf(i);
			if (v == value)
				ip.setf(i, (float)(rnd.nextDouble() * scale + min));
		}
	}

	final static private void processGray(final ImageProcessor ip, final int value, final int min, final int max) {
		final int scale = max - min + 1;
		final Random rnd = new Random();
		final int n = ip.getWidth() * ip.getHeight();
		for (int i =0; i < n; ++i) {
			final int v = ip.get(i);
			if (v == value)
				ip.set(i, rnd.nextInt(scale) + min);
		}
	}

	final static private void processColor(final ColorProcessor ip, final int value, final int min, final int max) {
		final int scale = max - min + 1;
		final Random rnd = new Random();
		final int n = ip.getWidth() * ip.getHeight();
		for (int i =0; i < n; ++i) {
			final int v = ip.get(i);
			if (v == value)
			{
				final int r = rnd.nextInt(scale) + min;
				final int g = rnd.nextInt(scale) + min;
				final int b = rnd.nextInt(scale) + min;

				ip.set(i, (((((0xff << 8) | r) << 8) | g) << 8) | b);
			}
		}
	}

	protected double value = Double.NaN, min = 0, max = 255;

	public ValueToNoise() {}

	public ValueToNoise(
			final double value,
			final double min,
			final double max) {
		set(value, min, max);
	}

	private final void set(
			final double value,
			final double min,
			final double max) {
		this.value = value;
		this.min = min;
		this.max = max;
	}

	public ImageProcessor process(final ImageProcessor ip) {
		try {
			if (FloatProcessor.class.isInstance(ip)) {
				if (Double.isNaN(value))
					processFloatNaN((FloatProcessor)ip, min, max);
				else
					processFloat((FloatProcessor)ip, (float)value, min, max);
			} else {
				if (ColorProcessor.class.isInstance(ip))
					processColor((ColorProcessor)ip, (int)Math.round(value), (int)Math.round(min), (int)Math.round(max));
				else
					processGray(ip, (int)Math.round(value), (int)Math.round(min), (int)Math.round(max));
			}
		} catch (final Exception e) {
			e.printStackTrace();
		}
		return ip;
	}
}
