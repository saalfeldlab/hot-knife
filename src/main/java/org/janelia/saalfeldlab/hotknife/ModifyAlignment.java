package org.janelia.saalfeldlab.hotknife;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.hotknife.ViewAlignment.Options;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.Bdv;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.realtransform.PositionFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

public class ModifyAlignment
{
	public static <T extends RealType<T>> RandomAccessibleInterval<T> modifyAlignmentVNC19m(
			final RandomAccessibleInterval<T> positionField,
			final int surfaceCount,
			final String datasetName )
	{
		/*
		Loading: /align-v3/align-1-testb/align-v3.slab-2.top.face
		count: 0
		Loading: /align-v3/align-1-testb/align-v3.slab-2.bot.face
		count: 1
		Loading: /align-v3/align-1-testb/align-v3.slab-3.top.face
		count: 2
		Loading: /align-v3/align-1-testb/align-v3.slab-3.bot.face
		count: 3
		Loading: /align-v3/align-1-testb/align-v3.slab-4.top.face
		count: 4
		Loading: /align-v3/align-1-testb/align-v3.slab-4.bot.face
		count: 5

		 */

		if ( surfaceCount == 1 ) // Loading: /align-v3/align-1-testb/align-v3.slab-2.bot.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 800, 135 },
					new double[] { 0, -70 / -2.0 },
					new double[] { 350, 150 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1190, 390 },
					new double[] { 26 / -2.0, -20 / -2.0 },
					new double[] { 200, 200 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 2 ) // Loading: /align-v3/align-1-testb/align-v3.slab-3.top.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 800, 135 },
					new double[] { 0, -70 / 2 },
					new double[] { 350, 150 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1190, 390 },
					new double[] { 26 / 2, -20 / 2 },
					new double[] { 200, 200 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 3 ) // Loading: /align-v3/align-1-testb/align-v3.slab-3.bot.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 800, 135 },
					new double[] { -25 / 2.0, -45/ 2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1152, 408 },
					new double[] { -10 / 2.0, -5 / 2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 950, 390 },
					new double[] { 0, 10 / 2.0 },
					new double[] { 150, 150 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 4 ) // Loading: /align-v3/align-1-testb/align-v3.slab-4.top.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 800, 135 },
					new double[] { -25 / -2.0, -45 / -2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1152, 408 },
					new double[] { -10 / -2.0, -5 / -2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 950, 390 },
					new double[] { 0, 10 / -2.0 },
					new double[] { 150, 150 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else
		{
			System.out.println( datasetName + " (" + surfaceCount + ") was not changed." );

			return positionField;
		}
	}

	public static ArrayImg<DoubleType, ?> copyPositionField( final RandomAccessibleInterval<DoubleType> positionFieldIn )
	{
		final ArrayImg<DoubleType, ?> positionFieldCopy = new ArrayImgFactory<>(new DoubleType()).create(Views.zeroMin( positionFieldIn ));

		Util.copy(Views.zeroMin( positionFieldIn ), positionFieldCopy);
		
		return positionFieldCopy;
	}

	public static < T > RandomAccessibleInterval< T > setPositionFieldBounds(
			final RandomAccessibleInterval< T > positionFieldCopy,
			final RandomAccessibleInterval< ? > positionFieldIn)
	{
		final long[] min = new long[ positionFieldIn.numDimensions() ];
		positionFieldIn.min( min );

		return Views.translate( positionFieldCopy, min );
	}

	public static <T extends RealType<T>> void modifyPositionField(
			final RandomAccessibleInterval<T> positionFieldCopy,
			final int[] loc,
			final double[] moveBy,
			final double[] sigma )
	{
		double[] halfKernelX = Gauss3.halfkernel( sigma[ 0 ], Gauss3.halfkernelsizes( new double[] { sigma[ 0 ] } )[ 0 ] ,false );
		double[] halfKernelY = Gauss3.halfkernel( sigma[ 1 ], Gauss3.halfkernelsizes( new double[] { sigma[ 1 ] } )[ 0 ] ,false );

		
		final Cursor< T > c = Views.iterable( positionFieldCopy ).localizingCursor();

		while ( c.hasNext() )
		{
			final T t = c.next();

			final int distX = Math.abs( c.getIntPosition( 0 ) - loc[0] );
			final int distY = Math.abs( c.getIntPosition( 1 ) - loc[1] );

			final double w;

			if ( distX < halfKernelX.length && distY < halfKernelY.length )
			{
				w = halfKernelX[ distX ] * halfKernelY[ distY ];
	
				if ( c.getIntPosition( 2 ) == 1 ) // y
					t.setReal( t.getRealDouble() + moveBy[ 1 ] * w );
				else // x
					t.setReal( t.getRealDouble() + moveBy[ 0 ] * w );
			}
		}
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

//		new ImageJ();

		if ( options.getGroups().size() != 1 )
			throw new RuntimeException( "This works only with a single align group, ideally align-1 or align-0." );

		final N5Reader n5 = new N5FSReader(options.getN5Path());

		final int showScaleIndex = options.getScaleIndex();
		final double showScale = 1.0 / (1 << showScaleIndex);

		System.out.println( "showscale: " + showScale );
		Bdv bdv = null;

		final int numProc = Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 );
		final SharedQueue queue = new SharedQueue( numProc );
		final CacheHints cacheHints = new CacheHints( LoadingStrategy.VOLATILE, 0, true );

		for (final String group : options.getGroups()) {

			final String[] datasetNames = n5.getAttribute(group, "datasets", String[].class);
			final String[] transformDatasetNames = n5.getAttribute(group, "transforms", String[].class);
			final double[] boundsMin = n5.getAttribute(group, "boundsMin", double[].class);
			final double[] boundsMax = n5.getAttribute(group, "boundsMax", double[].class);

			final RealTransform[] realTransforms = new RealTransform[datasetNames.length];
			for (int i = 0; i < datasetNames.length; ++i) {

				final String datasetName = group + "/" + transformDatasetNames[i];

				//realTransforms[i] = Transform.loadScaledTransform(
				//		n5,
				//		group + "/" + transformDatasetNames[i]);

				final double[] boundsMinSurface = n5.getAttribute(datasetName, "boundsMin", double[].class);
				final double transformScale = n5.getAttribute(datasetName, "scale", double.class);
				
				System.out.println( "\n" + datasetName + ", transformscale: " + transformScale );
				if ( transformScale != showScale )
					System.out.println( "WARNING: transformscale does not match showscale, be careful!" );

				final RandomAccessibleInterval<DoubleType> positionField = N5Utils.open(n5, datasetName);
				final int n = positionField.numDimensions() - 1;
				final long[] translation = Arrays.copyOf(Grid.floorScaled(boundsMinSurface, transformScale), n + 1);

				final RandomAccessibleInterval< DoubleType > positionFieldAdjusted = Views.translate(positionField, translation);
				
				// modify the field if necessary
				final RandomAccessibleInterval< DoubleType > positionFieldModified = modifyAlignmentVNC19m( positionFieldAdjusted, i, datasetName );

				final PositionFieldTransform<DoubleType> transform =
						Transform.createPositionFieldTransform( positionFieldModified );
				realTransforms[i] = Transform.createScaledRealTransform(transform, transformScale);

			}

			final RandomAccessibleInterval<FloatType> stack = Transform.createTransformedStack(

					options.getN5Path(),
					Arrays.asList(datasetNames),
					showScaleIndex,
					Arrays.asList(realTransforms),
					new FinalInterval(
							Grid.floorScaled(boundsMin, showScale),
							Grid.ceilScaled(boundsMax, showScale)));

			bdv = Show.transformedStack(
					(RandomAccessibleInterval)VolatileViews.wrapAsVolatile(
							Show.wrapAsVolatileCachedCellImg(stack, new int[]{256, 256, 26}),
							queue,
							cacheHints),
					bdv);

//			ImageJFunctions.show(stack, group);
		}
	}

}
