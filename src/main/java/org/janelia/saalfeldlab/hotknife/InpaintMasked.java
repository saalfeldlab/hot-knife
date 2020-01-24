/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.hotknife;

import java.util.Arrays;
import java.util.concurrent.Callable;

import gnu.trove.set.hash.TIntHashSet;
import ij.IJ;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 * Inpaint masked values by iteratively diffusing in their immediate
 * adjacent neighbors.
 *
 * @author Stephan Saalfeld
 */
public class InpaintMasked implements Callable<Void> {

	@Option(
			names = {"--image", "-i"},
			required = true,
			description = "image path, e.g. /nrs/flyem/alignment/test_flow/Sec06_24129_test.tif")
	private String imgPath;

	@Option(
			names = {"--mask", "-m"},
			required = true,
			split = ",",
			description = "mask paths, e.g. /nrs/flyem/alignment/test_flow/Sec06_24129_mask.tif or /nrs/flyem/alignment/test_flow/Sec06_24129_mask1.tif,/nrs/flyem/alignment/test_flow/Sec06_24129_mask2.tif")
	private String[] maskPaths;

	@Option(
			names = {"--output", "-o"},
			required = true,
			description = "output path, e.g. /nrs/flyem/alignment/test_flow/Sec06_24129_inpainted.tif")
	private String outPath;

	private static int indexOf(
			final int x,
			final int y,
			final int w) {

		return y * w + x;
	}

	private static void initBorder(
			final TIntHashSet border,
			final FloatProcessor fp) {

		final int width = fp.getWidth();
		final int height = fp.getHeight();
		final int wh = width * height;

		final int w = width - 1;
		final int h = height - 1;

		for (int i = 0; i < wh; ++i) {
			final float v = fp.getf(i);

			if (Float.isNaN(v)) {
				final int y = i / width;
				final int x = i - width * y;

				if (y > 0) {
					if (x > 0) {
						final float tl = fp.getf(x - 1, y - 1);
						if (!Float.isNaN(tl)) {
							border.add(i);
							continue;
						}
					}
					final float t = fp.getf(x, y - 1);
					if (!Float.isNaN(t)) {
						border.add(i);
						continue;
					}
					if (x < w) {
						final float tr = fp.getf(x + 1, y - 1);
						if (!Float.isNaN(tr)) {
							border.add(i);
							continue;
						}
					}
				}

				if (x > 0) {
					final float l = fp.getf(x - 1, y);
					if (!Float.isNaN(l)) {
						border.add(i);
						continue;
					}
				}
				if (x < w) {
					final float r = fp.getf(x + 1, y);
					if (!Float.isNaN(r)) {
						border.add(i);
						continue;
					}
				}

				if (y < h) {
					if (x > 0) {
						final float bl = fp.getf(x - 1, y + 1);
						if (!Float.isNaN(bl)) {
							border.add(i);
							continue;
						}
					}
					final float b = fp.getf(x, y + 1);
					if (!Float.isNaN(b)) {
						border.add(i);
						continue;
					}
					if (x < w) {
						final float br = fp.getf(x + 1, y + 1);
						if (!Float.isNaN(br)) {
							border.add(i);
							continue;
						}
					}
				}
			}
		}
	}

	@Override
	public Void call() {

		final ImagePlus imp = IJ.openImage(imgPath);
		final ImagePlus[] masks = new ImagePlus[maskPaths.length];
		Arrays.setAll(masks, i -> IJ.openImage(maskPaths[i]));

		final ImageProcessor ipImp = imp.getProcessor();
		final ImageProcessor[] ipMasks = new ImageProcessor[masks.length];
		Arrays.setAll(ipMasks, i -> masks[i].getProcessor());

		final FloatProcessor fp = imp.getType() == ImagePlus.GRAY32 ? (FloatProcessor)ipImp
				: ipImp.convertToFloatProcessor();

		final int n = imp.getWidth() * imp.getHeight();
		for (int m = 0; m < masks.length; ++m) {
			final ImageProcessor ipMask = ipMasks[m];
			for (int i = 0; i < n; ++i) {
				if (ipMask.getf(i) == 0) {
					fp.setf(i, Float.NaN);
				}
			}
		}

		run(fp);

		final ImageProcessor ipOut;
		switch (imp.getType()) {
		case ImagePlus.GRAY8:
			ipOut = fp.convertToByte(false);
			break;
		case ImagePlus.GRAY16:
			ipOut = fp.convertToShort(false);
			break;
		default:
			ipOut = fp;
		}

		IJ.save(new ImagePlus("", ipOut), outPath);

		return null;
	}


	public void run(final FloatProcessor fp) {

		final int width = fp.getWidth();
		final int height = fp.getHeight();

		final int w = width - 1;
		final int h = height - 1;

		final FloatProcessor fpCopy = (FloatProcessor)fp.duplicate();

		TIntHashSet border = new TIntHashSet();
		initBorder(border, fp);

		while (border.size() > 0) {
			final TIntHashSet newBorder = new TIntHashSet();
			final TIntHashSet fBorder = border;
			border.forEach(i -> {

				final int y = i / width;
				final int x = i - y * width;
				int j;

				float s = 0;
				float n = 0;
				if (y > 0) {
					if (x > 0) {
						j = indexOf(x - 1, y - 1, width);
						final float tl = fp.getf(j);
						if (Float.isNaN(tl)) {
							if (!fBorder.contains(j))
								newBorder.add(j);
						} else {
							s += 0.5f * tl;
							n += 0.5f;
						}
					}
					j = indexOf(x, y - 1, width);
					final float t = fp.getf(j);
					if (Float.isNaN(t)) {
						if (!fBorder.contains(j))
							newBorder.add(j);
					} else {
						s += t;
						n += 1;
					}
					if (x < w) {
						j = indexOf(x + 1, y - 1, width);
						final float tr = fp.getf(j);
						if (Float.isNaN(tr)) {
							if (!fBorder.contains(j))
								newBorder.add(j);
						} else {
							s += 0.5f * tr;
							n += 0.5f;
						}
					}

					if (x > 0) {
						j = indexOf(x - 1, y, width);
						final float l = fp.getf(j);
						if (Float.isNaN(l)) {
							if (!fBorder.contains(j))
								newBorder.add(j);
						} else {
							s += l;
							n += 1;
						}
					}
					if (x < w) {
						j = indexOf(x + 1, y, width);
						final float r = fp.getf(j);
						if (Float.isNaN(r)) {
							if (!fBorder.contains(j))
								newBorder.add(j);
						} else {
							s += r;
							n += 1;
						}
					}

					if (y < h) {
						if (x > 0) {
							j = indexOf(x - 1, y + 1, width);
							final float bl = fp.getf(j);
							if (Float.isNaN(bl)) {
								if (!fBorder.contains(j))
									newBorder.add(j);
							} else {
								s += 0.5f * bl;
								n += 0.5f;
							}
						}
						j = indexOf(x, y + 1, width);
						final float b = fp.getf(j);
						if (Float.isNaN(b)) {
							if (!fBorder.contains(j))
								newBorder.add(j);
						} else {
							s += b;
							n += 1;
						}
						if (x < w) {
							j = indexOf(x + 1, y + 1, width);
							final float br = fp.getf(j);
							if (Float.isNaN(br)) {
								if (!fBorder.contains(j))
									newBorder.add(j);
							} else {
								s += 0.5f * br;
								n += 0.5f;
							}
						}
					}

					if (n > 0)
						fpCopy.setf(i, s / n);
				}
				return true;
			});

			border.forEach(i -> {
				fp.setf(i, fpCopy.getf(i));
				return true;
			});

			border = newBorder;
		}
	}

	public static void main(final String... args) {

		CommandLine.call(new InpaintMasked(), args);
	}
}
