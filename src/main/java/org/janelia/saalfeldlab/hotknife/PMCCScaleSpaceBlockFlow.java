package org.janelia.saalfeldlab.hotknife;
import java.util.ArrayList;
import java.util.Arrays;

import org.janelia.saalfeldlab.hotknife.util.Util;

import ij.ImageStack;
import ij.plugin.filter.GaussianBlur;
import ij.process.Blitter;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;
import mpicbg.ij.integral.BlockPMCC;
import mpicbg.models.NotEnoughDataPointsException;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.array.ArrayRandomAccess;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.img.planar.PlanarImg;
import net.imglib2.img.planar.PlanarImgs;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.position.RealPositionRealRandomAccessible;
import net.imglib2.realtransform.DeformationFieldTransform;
import net.imglib2.realtransform.PositionFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRandomAccessible;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

/**
 *
 * @author Stephan Saalfeld
 */
public class PMCCScaleSpaceBlockFlow
{
	static public void opticFlow(
			final FloatProcessor ip1,
			final FloatProcessor ip2,
			final int distance,
			final ImageStack r,
			final ImageStack shiftVectors,
			final double scaleFactor )
	{
		final BlockPMCC bc = new BlockPMCC( ip1.getWidth(), ip1.getHeight(), ip1, ip2 );
		//final BlockPMCC bc = new BlockPMCC( ip1, ip2 );

		final FloatProcessor ipR = bc.getTargetProcessor();
		final float[] ipRPixels = ( float[] )ipR.getPixels();

		final ArrayList< Double > radiusList = new ArrayList< Double >();

		for ( double radius = 1; radius < r.getWidth() / 4; radius *= scaleFactor )
		{
			radiusList.add( radius );

			final FloatProcessor ipRMax = new FloatProcessor( ipR.getWidth(), ipR.getHeight() );
			final float[] ipRMaxPixels = ( float[] )ipRMax.getPixels();
			{
				for ( int i = 0; i < ipRMaxPixels.length; ++i )
					ipRMaxPixels[ i ] = -1;
			}
			final ShortProcessor ipX = new ShortProcessor( ipR.getWidth(), ipR.getHeight() );
			final ShortProcessor ipY = new ShortProcessor( ipR.getWidth(), ipR.getHeight() );

			r.addSlice( "" + radius, ipRMax );
			shiftVectors.addSlice( "" + radius, ipX );
			shiftVectors.addSlice( "" + radius, ipY );
		}

		/* assemble into typed arrays for quicker access */
		final float[][] rArrays = new float[ r.getSize() ][];
		final short[][] xShiftArrays = new short[ rArrays.length ][];
		final short[][] yShiftArrays = new short[ rArrays.length ][];
		final int[] radii = new int[ rArrays.length ];
		for ( int i = 0; i < radii.length; ++i )
		{
			rArrays[ i ] = ( float[] )r.getImageArray()[ i ];
			xShiftArrays[ i ] = ( short[] )shiftVectors.getImageArray()[ i << 1 ];
			yShiftArrays[ i ] = ( short[] )shiftVectors.getImageArray()[ ( i << 1 ) | 1 ];

			radii[ i ] = ( int )Math.round( radiusList.get( i ) );
		}

		for ( int yo = -distance; yo <= distance; ++yo )
		{
			for ( int xo = -distance; xo <= distance; ++xo )
			{
				// continue if radius is larger than maxDistance
				if ( yo * yo + xo * xo > distance * distance ) continue;

				bc.setOffset( xo, yo );

				for ( int ri = 0; ri < radii.length; ++ri )
				{
					final int blockRadius = radii[ ri ];

					bc.rSignedSquare( blockRadius );

					final float[] ipRMaxPixels = rArrays[ ri ];
					final short[] ipXPixels = xShiftArrays[ ri ];
					final short[] ipYPixels = yShiftArrays[ ri ];

					// update the translation fields
					final int h = ipR.getHeight() - distance;
					final int width = ipR.getWidth();
					final int w = width - distance;

					for ( int y = distance; y < h; ++y )
					{
						final int row = y * width;
						final int rowR;
						if ( yo < 0 )
							rowR = row;
						else
							rowR = ( y - yo ) * width;
						for ( int x = distance; x < w; ++x )
						{
							final int i = row + x;
							final int iR;
							if ( xo < 0 )
								iR = rowR + x;
							else
								iR = rowR + ( x - xo );

							final float ipRPixel = ipRPixels[ iR ];
							final float ipRMaxPixel = ipRMaxPixels[ i ];

							if ( ipRPixel > ipRMaxPixel )
							{
								ipRMaxPixels[ i ] = ipRPixel;
								ipXPixels[ i ] = ( short )xo;
								ipYPixels[ i ] = ( short )yo;
							}
						}
					}
				}
			}
		}
	}

	private static final RandomAccessibleInterval< FloatType > createTransformedInterval(
			final FloatProcessor source,
			final Interval targetInterval,
			final RealTransform transformFromSource )
	{
		return Views.interval(
						new RealTransformRandomAccessible<>(
							Views.interpolate(
									Views.extendBorder(
										ArrayImgs.floats(
											( float[] )source.convertToFloatProcessor().getPixels(),
											source.getWidth(),
											source.getHeight() ) ),
									new NLinearInterpolatorFactory<>() ),
							transformFromSource ),
						targetInterval );
	}

	private final static void filterOpticFlowScaleSpace(
			final ImageStack shiftVectors,
			final FloatProcessor shiftX,
			final FloatProcessor shiftY,
			final FloatProcessor inlierRatio,
			final short distance ) throws NotEnoughDataPointsException
	{
		/* assemble into typed arrays for quicker access */
		/* TODO This is still inefficient because scale dimension is fastest but should be slowest
		 * this is true here and in opticFlow, i.e. scale should be interleaved (size of array
		 * becomes concern, use ImgLib2).
		 */
		final int scaleLevels = shiftVectors.size() / 2;
		final short[][] xShiftArrays = new short[ scaleLevels ][];
		final short[][] yShiftArrays = new short[ scaleLevels ][];
		for ( int i = 0; i < xShiftArrays.length; ++i )
		{
			xShiftArrays[ i ] = ( short[] )shiftVectors.getImageArray()[ i << 1 ];
			yShiftArrays[ i ] = ( short[] )shiftVectors.getImageArray()[ ( i << 1 ) | 1 ];
		}

		final int n = shiftVectors.getWidth() * shiftVectors.getHeight();
		final int m = xShiftArrays.length;


		final int w = ( distance * 2 + 1 );
		final int[] countsArray = new int[ w * w ];
		final ArrayImg< IntType, IntArray > countsImg = ArrayImgs.ints( countsArray, distance * 2 + 1, distance * 2 + 1 );
		final ArrayRandomAccess< IntType > countsAccess = countsImg.randomAccess( countsImg );

		for ( int i = 0; i < n; ++i )
		{
			Arrays.fill( countsArray, 0 );
			for ( int j = 0; j < m; ++j )
			{
				final int x = xShiftArrays[ j ][ i ] + distance;
				final int y = yShiftArrays[ j ][ i ] + distance;

				countsAccess.setPosition( x, 0 );
				countsAccess.setPosition( y, 1 );
				countsAccess.get().inc();
			}

			float bestX = 0;
			float bestY = 0;
			int bestCount = 0;

			for ( int x = 0; x < w; ++x )
			{
				countsAccess.setPosition( x, 0 );
				for ( int y = 0; y < w; ++y )
				{
					countsAccess.setPosition( y, 1 );
					final int count = countsAccess.get().get();

					if ( count > bestCount )
					{
						bestCount = count;
						bestX = x - distance;
						bestY = y - distance;
					}
				}
			}

			shiftX.setf( i, bestX );
			shiftY.setf( i, bestY );

			inlierRatio.setf( i, ( float )bestCount / scaleLevels );
		}
	}

	private static final DeformationFieldTransform< DoubleType > createDeformationFieldTransform(
			final FloatProcessor shiftX,
			final FloatProcessor shiftY,
			final InterpolatorFactory< DoubleType, RandomAccessible< DoubleType > > interpolatorFactory )
	{
		final PlanarImg< DoubleType, DoubleArray > data = PlanarImgs.doubles( shiftX.getWidth(), shiftX.getHeight(), 2 );
		final float[] floatXPixels = ( float[] )shiftX.getPixels();
		int i = 0;
		for ( final DoubleType t : Views.flatIterable( Views.hyperSlice( data, 2, 0 ) ) )
			t.set( floatXPixels[ i++ ] );
		final float[] floatYPixels = ( float[] )shiftY.getPixels();
		i = 0;
		for ( final DoubleType t : Views.flatIterable( Views.hyperSlice( data, 2, 1 ) ) )
			t.set( floatYPixels[ i++ ] );

		return new DeformationFieldTransform<>(
				Views.interpolate(
						Views.extendBorder( data ),
						interpolatorFactory ) );
	}


	private static final DeformationFieldTransform< DoubleType > createDeformationFieldTransform(
			final FloatProcessor shiftX,
			final FloatProcessor shiftY )
	{
		return createDeformationFieldTransform( shiftX, shiftY, new NLinearInterpolatorFactory<>() );
	}


	public static final Pair< PositionFieldTransform< DoubleType >, FloatProcessor > scaleSpaceOpticFlow(
			final FloatProcessor ip1,
			final FloatProcessor ip2,
			final short radius,
			final double sigma,
			final int numIterations)
	{
		/* create background mask */
		final ByteProcessor backgroundMask = new ByteProcessor( ip1.getWidth(), ip1.getHeight() );
		final byte[] backgroundMaskPixels = ( byte[] )backgroundMask.getPixels();
		final float[] ip1Pixels = ( float[] )ip1.getPixels();
		final float[] ip2Pixels = ( float[] )ip2.getPixels();
		for ( int i = 0; i < backgroundMaskPixels.length; ++i )
			if ( !( ip1Pixels[ i ] == 0 || ip2Pixels[ i ] == 0 ) )
				backgroundMaskPixels[ i ] = 1;

		/* initialize composed weights */
		FloatProcessor weights = null;

		/* initialize position field with identity */
		RealRandomAccessible< DoubleType > xPositions = new RealPositionRealRandomAccessible( 2, 0 );
		RealRandomAccessible< DoubleType > yPositions = new RealPositionRealRandomAccessible( 2, 1 );

		/* filters to mask saturated pixels with noise */
		final ValueToNoise filter1 = new ValueToNoise( 0, 0, 255 );
		final ValueToNoise filter2 = new ValueToNoise( 255, 0, 255 );

		FloatProcessor ip1Filtered = filter1.process( ip1 ).convertToFloatProcessor();
		ip1Filtered = filter2.process( ip1Filtered ).convertToFloatProcessor();

		/* repeat numIteration times for each scale */
		for ( int j = 0; j < numIterations ; ++j )
		{
			@SuppressWarnings( "unchecked" )
			FloatProcessor ip2Transformed = Util.materialize(
					createTransformedInterval(
							ip2,
							new FinalInterval( ip2.getWidth(), ip2.getHeight() ),
							new PositionFieldTransform<>(
									( RealRandomAccessible< DoubleType >[] )new RealRandomAccessible[]{
										xPositions,
										yPositions } ) ) );

			ip2Transformed = filter1.process( ip2Transformed ).convertToFloatProcessor();
			ip2Transformed = filter2.process( ip2Transformed ).convertToFloatProcessor();

			final ImageStack seqR = new ImageStack( ip1Filtered.getWidth(), ip1Filtered.getHeight() );
			final ImageStack seqFlowVectors = new ImageStack( ip1Filtered.getWidth(), ip1Filtered.getHeight() );

			opticFlow(
					ip1Filtered,
					ip2Transformed,
					radius,
					seqR,
					seqFlowVectors,
					1.5 );

			final FloatProcessor shiftXFloat = new FloatProcessor( ip1Filtered.getWidth(), ip1Filtered.getHeight() );
			final FloatProcessor shiftYFloat = new FloatProcessor( ip1Filtered.getWidth(), ip1Filtered.getHeight() );
			weights = new FloatProcessor( ip1Filtered.getWidth(), ip1Filtered.getHeight() );
			try
			{
				filterOpticFlowScaleSpace(
						seqFlowVectors,
						shiftXFloat,
						shiftYFloat,
						weights,
						radius );
			}
			catch ( final NotEnoughDataPointsException e )
			{
				e.printStackTrace();
			}

			/* weight flow vectors by mask * max_R and Gaussian blur */
			weights.copyBits( backgroundMask, 0, 0, Blitter.MULTIPLY );
			shiftXFloat.copyBits( weights, 0, 0, Blitter.MULTIPLY );
			shiftYFloat.copyBits( weights, 0, 0, Blitter.MULTIPLY );

			new GaussianBlur().blurGaussian( shiftXFloat, sigma );
			new GaussianBlur().blurGaussian( shiftYFloat, sigma );
			new GaussianBlur().blurGaussian( weights, sigma );

			final FloatProcessor divisionWeights = ( FloatProcessor )weights.duplicate();

			final float[] divisionWeightsPixels = ( float[] )divisionWeights.getPixels();
			for ( int o = 0; o < divisionWeightsPixels.length; ++o )
				if ( divisionWeightsPixels[ o ] == 0 )
					divisionWeightsPixels[ o ] = 1;

			shiftXFloat.copyBits( divisionWeights, 0, 0, Blitter.DIVIDE );
			shiftYFloat.copyBits( divisionWeights, 0, 0, Blitter.DIVIDE );

			/* append deformation field to existing transformation */
			final DeformationFieldTransform< DoubleType > deformationField = createDeformationFieldTransform(
					shiftXFloat,
					shiftYFloat );

			xPositions = new RealTransformRandomAccessible<>(
					xPositions,
					deformationField );
			yPositions = new RealTransformRandomAccessible<>(
					yPositions,
					deformationField );
		}

		@SuppressWarnings( "unchecked" )
		final PositionFieldTransform< DoubleType > transform = new PositionFieldTransform<>(
				new RealRandomAccessible[]{
						xPositions,
						yPositions } );

		return new ValuePair< PositionFieldTransform< DoubleType >, FloatProcessor >( transform, weights );
	}
}
