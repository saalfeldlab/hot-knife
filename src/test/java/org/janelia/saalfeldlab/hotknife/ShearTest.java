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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import ij.ImageJ;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.in.TiffReader;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineRealRandomAccessible;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.LongAffineRandomAccessible;
import net.imglib2.realtransform.LongAffineTransform;
import net.imglib2.realtransform.RealViewsExtension;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.transform.integer.BoundingBox;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class ShearTest {

	public static RandomAccessibleInterval openSlice(
			final IFormatReader reader,
			final int slice,
			final int width,
			final int height ) throws IOException, FormatException {

		final byte[] bytes = new byte[ width * height * 2 ];
		reader.openBytes(slice, bytes, 0, 0, width, height);

		final ByteBuffer buffer = ByteBuffer.wrap(bytes);
		final short[] shorts = new short[width * height];
		buffer.order(reader.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

		buffer.asShortBuffer().get(shorts);

		return (RandomAccessibleInterval)ArrayImgs.unsignedShorts(shorts, width, height);
	}

	public static <T extends NativeType<T> & NumericType<T>> void main(final String... args) throws FormatException, IOException {

		new ImageJ();

//		final ImagePlus[] imps = BF.openImagePlus("/nrs/svoboda/wangt/bis25/march18_bis25_sample4_spim_r4/affineC/pos012_exp488AND561AND647nm_Z0989_cam1affine.tif");

		final TiffReader reader = new TiffReader();
		reader.setId("/nrs/svoboda/wangt/bis25/march18_bis25_sample4_spim_r4/affineC/pos012_exp488AND561AND647nm_Z0989_cam1affine.tif");
//		final int nSlices = reader.getImageCount();
		final int nSlices = 50;
		final int width = reader.getSizeX();
		final int height = reader.getSizeY();

		final ArrayList<RandomAccessibleInterval<T>> slices = new ArrayList<>();

		for (int i = 0; i < nSlices; ++i) {

			slices.add(openSlice(reader, i, width, height));
			System.out.println(i);
		}

		final RandomAccessibleInterval<T> stack = Views.stack(slices);
		ImageJFunctions.show(stack);

		final ExtendedRandomAccessibleInterval<T, ?> extended = Views.extendValue(stack, Util.getTypeFromInterval(stack).createVariable());
//		final RealRandomAccessible<UnsignedShortType> interpolant = Views.interpolate(extended, new NearestNeighborInterpolatorFactory<>());
		final RealRandomAccessible<T> interpolant = Views.interpolate(extended, new NLinearInterpolatorFactory<>());

		final AffineTransform3D affine = new AffineTransform3D();
//		affine.set(
//				1, 0.2, -12.8, 0,
//				0.1, 1, -3, 0,
//				0.08, 0.12, 1, 0);
		affine.set(
				1, 0.1, -12.8, 0,
				0.2, 1, -0.7, 0,
				0.01, 0, 1, 0);
//		affine.set(
//				1, 0, -12.8, 0,
//				0, 1, -0.7, 0,
//				0, 0, 1, 0);

		final Scale3D scale = new Scale3D(1, 1, 5);

		affine.preConcatenate(scale);

		final AffineRealRandomAccessible<T, AffineGet> transformed = RealViewsExtension.affineReal(interpolant, affine);

		final BdvStackSource<T> bdv = BdvFunctions.show(transformed, stack, "real");
		bdv.setDisplayRange(64, 512);

//		final AffineRealRandomAccessible<UnsignedShortType, AffineGet> transformedInteger = RealViews.affineReal(extended, affine, new NearestNeighborInterpolatorFactory<>());
		final AffineRealRandomAccessible<T, AffineGet> transformedInteger = RealViewsExtension.affineReal(extended, affine, new NLinearInterpolatorFactory<>());

		BdvFunctions.show(transformedInteger, stack, "integer + real", BdvOptions.options().addTo(bdv)).setDisplayRange(64, 512);

		/* test coverage */
		final Pair<AffineTransform, LongAffineTransform> decomposedInverse = LongAffineTransform.decomposeRealLong( affine.inverse() );
		final Interval testBlockInterval = new FinalInterval( 30, 30, 30 );
		final BoundingBox box = new BoundingBox( testBlockInterval );
		decomposedInverse.getB().transform(box);
		final long[] boxDimensions = new long[ 3 ];
		box.getInterval().dimensions( boxDimensions );
		final RandomAccessibleInterval< UnsignedByteType > source =
				Views.translate(
						ArrayImgs.unsignedBytes( boxDimensions ),
						box.corner1 );

		final LongAffineRandomAccessible< UnsignedByteType > integerTransformed =
				new LongAffineRandomAccessible<>(
						Views.extendZero(source),
						decomposedInverse.getB());


		final IntervalView<UnsignedByteType> cropIntegerTransformed = Views.interval(
				integerTransformed,
				testBlockInterval );

		int i = 0;
		for (final UnsignedByteType t : Views.iterable(cropIntegerTransformed))
		{
			t.set(i);
			++i;
			if ( i > 255 )
				i = 0;
		}

		/* test for overwriting */
		i = 0;
		for (final UnsignedByteType t : Views.iterable(cropIntegerTransformed))
		{
			if ( t.get() != i )
				System.out.println("expected <" + i + "> but was <" + t.get() );
			++i;
			if ( i > 255 )
				i = 0;
		}

		/* TODO test for density */


		BdvFunctions.show(source, "coverage", BdvOptions.options().addTo(bdv)).setDisplayRange(0, 255);
	}

}
