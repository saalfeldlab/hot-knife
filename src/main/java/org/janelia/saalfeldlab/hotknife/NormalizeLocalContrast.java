package org.janelia.saalfeldlab.hotknife;

import java.util.Map;

import ij.process.ImageProcessor;

public class NormalizeLocalContrast
{
	protected int brx = 500, bry = 500;
	protected float stds = 3;
	protected boolean cent = true, stret = true;

	public NormalizeLocalContrast() {}

	public NormalizeLocalContrast(
			final int blockRadiusX,
			final int blockRadiusY,
			final float stdDevs,
			final boolean center,
			final boolean stretch) {
		set(blockRadiusX, blockRadiusY, stdDevs, center, stretch);
	}

	private final void set(final int blockRadiusX,
			final int blockRadiusY,
			final float stdDevs,
			final boolean center,
			final boolean stretch) {
		this.brx = blockRadiusX;
		this.bry = blockRadiusY;
		this.stds = stdDevs;
		this.cent = center;
		this.stret = stretch;
	}

	public NormalizeLocalContrast(final Map<String,String> params) {
		try {
			set(Integer.parseInt(params.get("brx")),
			    Integer.parseInt(params.get("bry")),
			    Float.parseFloat(params.get("stds")),
			    Boolean.parseBoolean(params.get("stret")),
			    Boolean.parseBoolean(params.get("cent")));
		} catch (final NumberFormatException nfe) {
			throw new IllegalArgumentException("Could not create LocalContrast filter!", nfe);
		}
	}

	public ImageProcessor process(final ImageProcessor ip) {
		try {
			mpicbg.ij.plugin.NormalizeLocalContrast.run(ip, brx, bry, stds, cent, stret);
		} catch (final Exception e) {
			e.printStackTrace();
		}
		return ip;
	}
}
