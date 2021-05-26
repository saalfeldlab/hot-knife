package org.janelia.saalfeldlab.hotknife;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.models.RigidModel2D;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.PositionFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class ModifyAlignment
{
	public static <T extends RealType<T>> RandomAccessibleInterval<T> modifyAlignmentBR07m(
			final RandomAccessibleInterval<T> positionField,
			final int surfaceCount,
			final double transformScale,
			final String datasetName )
	{
		/*
		z=0 >>> flat.Sec26.bot.face
		z=1 >>> flat.Sec26.top.face
		z=2 >>> flat.Sec27.bot.face
		z=3 >>> flat.Sec27.top.face
		z=4 >>> flat.Sec28.bot.face
		z=5 >>> flat.Sec28.top.face
		z=6 >>> flat.Sec29.bot.face
		z=7 >>> flat.Sec29.top.face
		z=8 >>> flat.Sec30.bot.face
		z=9 >>> flat.Sec30.top.face
		z=10 >>> flat.Sec31.bot.face
		z=11 >>> flat.Sec31.top.face
		z=12 >>> flat.Sec32.bot.face
		z=13 >>> flat.Sec32.top.face
		z=14 >>> flat.Sec33.bot.face
		z=15 >>> flat.Sec33.top.face
		z=16 >>> flat.Sec34.bot.face
		z=17 >>> flat.Sec34.top.face
		z=18 >>> flat.Sec35.bot.face
		z=19 >>> flat.Sec35.top.face
		z=20 >>> flat.Sec36.bot.face
		z=21 >>> flat.Sec36.top.face
		z=22 >>> flat.Sec37.bot.face
		z=23 >>> flat.Sec37.top.face
		z=24 >>> flat.Sec38.bot.face
		z=25 >>> flat.Sec38.top.face
		z=26 >>> flat.Sec39.bot.face
		z=27 >>> flat.Sec39.top.face
		
		Note:
		positive X: move left
		positive Y: move up
		 */

		if ( surfaceCount == 2 ) 
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1809, 139 },
					new double[] { -29, 17 },
					new double[] { 450, 50 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 4 ) // flat.Sec28.bot.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 2419, 843 },
					new double[] { 11, -13 },
					new double[] { 50, 150 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 6 ) 
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			// new modified-2
			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1722, 2390 },
					new double[] { -22, -3 },
					new double[] { 250, 50 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 7 )
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1925, 216 },
					new double[] { -8 / 2.0, -38 / 2.0 },
					new double[] { 500, 200 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 8 )
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1925, 216 },
					new double[] { 8 / 2.0, 38 / 2.0 },
					new double[] { 500, 200 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 15 )
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1947, 622 },
					new double[] { -33 / 2.0, 14 / 2.0 },
					new double[] { 400, 110 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 16 )
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1947, 622 },
					new double[] { 33 / 2.0, -14 / 2.0 },
					new double[] { 400, 110 } );

			scalePositionFieldBR07m(positionFieldCopy, 1211, 3017, 1739, 3151, 1.7 );

			/*
			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1385, 3227 },
					new double[] { -7, 25 },
					new double[] { 400, 140 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1385, 3227 },
					new double[] { -7, 25 },
					new double[] { 400, 140 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1335, 3057 },
					new double[] { 0, -40 },
					new double[] { 100, 50 } );
			*/

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 17 )
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			/*
			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1453, 3200 },
					new double[] { -7, 25 },
					new double[] { 400, 100 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1453, 3200 },
					new double[] { -7, 25 },
					new double[] { 400, 100 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1620, 3153 },
					new double[] { -20, 20 },
					new double[] { 70, 70 } );
			*/

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 18 )
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1691, 737 },
					new double[] { 18, -47 },
					new double[] { 175, 175 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 19 )
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1806, 777 },
					new double[] { 4, -40 },
					new double[] { 350, 200 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 20 )
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1586, 3116 },
					new double[] { 68, 23 },
					new double[] { 650, 110 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1129, 2975 },
					new double[] { 34, 18 },
					new double[] { 75, 75 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 26 )
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 2061, 1240 },
					new double[] { -26, -26 },
					new double[] { 200, 200 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else
		{
			System.out.println( datasetName + " (" + surfaceCount + ") was not changed." );
		}

		return positionField;
	}

	public static <T extends RealType<T>> RandomAccessibleInterval<T> modifyAlignmentVNC19m(
			final RandomAccessibleInterval<T> positionField,
			final int surfaceCount,
			final double transformScale,
			final String datasetName )
	{
		/*if ( surfaceCount == 1 ) // Loading: /align-v3/align-1-testb/align-v3.slab-2.bot.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1446, 245 },//new int[] { 800, 135 },
					new double[] { 0, -70 / -2.0 },
					new double[] { 350, 150 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1840, 475 },//new int[] { 1190, 390 },
					new double[] { 26 / -2.0, -20 / -2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1575, 435 },
					new double[] { 25 / -2.0, 10 / -2.0 },
					new double[] { 75, 75 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1688, 583 },
					new double[] { -10 / -2.0, 15 / -2.0 },
					new double[] { 75, 75 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1590, 703 },
					new double[] { -6 / -2.0, 4 / -2.0 },
					new double[] { 150, 150 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 2 ) // Loading: /align-v3/align-1-testb/align-v3.slab-3.top.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1446, 245 },
					new double[] { 0, -70 / 2.0 },
					new double[] { 350, 150 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1840, 475 },
					new double[] { 26 / 2.0, -20 / 2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1575, 435 },
					new double[] { 25 / 2.0, 10 / 2.0 },
					new double[] { 75, 75 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1688, 583 },
					new double[] { -10 / 2.0, 15 / 2.0 },
					new double[] { 75, 75 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1590, 703 },
					new double[] { -6 / 2.0, 4 / 2.0 },
					new double[] { 150, 150 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 3 ) // Loading: /align-v3/align-1-testb/align-v3.slab-3.bot.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1446, 245 },//new int[] { 800, 135 },
					new double[] { -5 / 2.0, -45 / 2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1802, 508 },//new int[] { 1152, 408 },
					new double[] { -10 / 2.0, -5 / 2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1600, 490 },//new int[] { 950, 390 },
					new double[] { 0, 10 / 2.0 },
					new double[] { 150, 150 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1423, 420 },
					new double[] { 0, 25 / 2.0 },
					new double[] { 100, 100 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 4 ) // Loading: /align-v3/align-1-testb/align-v3.slab-4.top.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1446, 245 },//new int[] { 800, 135 },
					new double[] { -5 / -2.0, -45 / -2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1802, 508 },//new int[] { 1152, 408 },
					new double[] { -10 / -2.0, -5 / -2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1600, 490 },//new int[] { 950, 390 },
					new double[] { 0, 10 / -2.0 },
					new double[] { 150, 150 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1423, 420 },
					new double[] { 0, 25 / -2.0 },
					new double[] { 100, 100 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 8 || surfaceCount == 9 ) // Loading: /align-v3/align-1/align-v3.slab-6.bot & top.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			// line defined by 660,1924 >> 1725,1800
			final double x1 = 882, y1 = 1900 + 35, x2 = 1683, y2 = 1807 + 35;

			// move parallel to this line in y (this is a concatenate)
			final double[] moveVector = new double[] { 260, -25 };
			final double[] additionalMoveVector = new double[ 2 ];

			final Cursor< DoubleType > c = Views.iterable( positionFieldCopy ).localizingCursor();

			while ( c.hasNext() )
			{
				final DoubleType t = c.next();

				final double x0 = c.getDoublePosition( 0 ), y0 = c.getDoublePosition( 1 );
				final double distance = ( (y2-y1)*x0 - (x2-x1)*y0 + x2*y1 - y2*x1 ) / ( Math.sqrt( (y2 - y1)*(y2 - y1) + (x2 - x1)*(x2 - x1 ) ));
				
				if ( distance < 0 )
				{
					if ( c.getIntPosition( 0 ) > 1740 )
					{
						additionalMoveVector[ 0 ] = -10;
						additionalMoveVector[ 1 ] = 65;
					}
					else
					{
						additionalMoveVector[ 0 ] = 0;
						additionalMoveVector[ 1 ] = 0;
					}

					// move parallel to the line
					if ( c.getIntPosition( 2 ) == 1 ) // y
						t.set( t.get() + moveVector[ 1 ] + additionalMoveVector[ 1 ] );
					else
						t.set( t.get() + moveVector[ 0 ] + additionalMoveVector[ 0 ]);
				}
			}

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 13 ) // Loading: align-v3.slab-8.bot.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 780, 1791 },
					new double[] { 0, -25 / 2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1105, 2088 },
					new double[] { -10 / 2.0,  6 / 2.0 },
					new double[] { 100, 100 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 14 ) // Loading: align-v3.slab-9.top.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 780, 1791 },
					new double[] { 0, -25 / -2.0 },
					new double[] { 200, 200 } );
			
			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1105, 2088 },
					new double[] { -10 / -2.0,  6 / -2.0 },
					new double[] { 100, 100 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 11 ) // Loading: align-v3.slab-7.bot.face 
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 850, 1950 },
					new double[] { 0, 15 / 2.0 },
					new double[] { 200, 200 } );
			
			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 12 ) // Loading: align-v3.slab-8.top.face 
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 850, 1950 },
					new double[] { 0, 15 / -2.0 },
					new double[] { 200, 200 } );
			
			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 25 ) // Loading: align-v3.slab-14.bot.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1280, 1045 },
					new double[] { 0, -15 / 2.0 },
					new double[] { 300, 80 } );
			
			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 26 ) // Loading: align-v3.slab-15.top.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1280, 1045 },
					new double[] { 0, -15 / -2.0 },
					new double[] { 300, 80 } );
			
			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else*/

		if ( surfaceCount == 12 ) // 
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );
		
			if ( transformScale != 0.25 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );
		
			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );
		
			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 3876, 8250 },
					new double[] { 0, -80 },
					new double[] { 300, 300 } );
		
			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 14 ) // 
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.25 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 4410, 8351 },
					new double[] { 0, 50 },
					new double[] { 200, 200 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 16 ) // 
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.25 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 3808, 6383 },
					new double[] { 15, -64 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 4124, 6573 },
					new double[] { 15, -64 },
					new double[] { 200, 200 } );


			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		if ( surfaceCount == 26 ) // 
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.25 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 5500, 4190 },
					new double[] { 0, -40 },
					new double[] { 300, 150 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 4900, 4238 },
					new double[] { 0, 50 },
					new double[] { 300, 150 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else if ( surfaceCount == 32 ) // Loading: align-v3.slab-18.top.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.25 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1900, 10725 },
					new double[] { 0, -70 },
					new double[] { 300, 300 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 2155, 10695 },
					new double[] { 0, 80 },
					new double[] { 100, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 2517, 10473 },
					new double[] { 0, 50 },
					new double[] { 100, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 2500, 10550 },
					new double[] { 0, 50 },
					new double[] { 300, 150 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 2510, 10515 },
					new double[] { 0, -50 },
					new double[] { 75, 75 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
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

	public static <T extends RealType<T>> void scalePositionFieldBR07m(
			final RandomAccessibleInterval<T> positionFieldCopy,
			final double x1,
			final double y1,
			final double x2,
			final double y2,
			final double scale )
	{
		final Cursor< T > c = Views.iterable( positionFieldCopy ).localizingCursor();

		while ( c.hasNext() )
		{
			final T t = c.next();

			final double x0 = c.getDoublePosition( 0 );
			final double y0 = c.getDoublePosition( 1 );

			double dist = ( (x2-x1)*(y1-y0) - (x1-x0)*(y2-y1) ) / Math.sqrt( Math.pow( (x2-x1), 2) + Math.pow((y2-y1), 2) );

			if ( dist < 0 )
			{
				// find closest point on the line
				double[] a = new double[] { x1, y1 };
				double[] m = new double[] { x2 - x1, y2 - y1 };
				double[] p = new double[] { x0, y0 };

				double[] pa = new double[] { x0 - x1, y0 - y1 };

				double t0 = LinAlgHelpers.dot( pa, m ) / LinAlgHelpers.dot( m, m );

				double px = a[ 0 ] + t0 * m[ 0 ];
				double py = a[ 1 ] + t0 * m[ 1 ];

				double dx = p[ 0 ] - px;
				double dy = p[ 1 ] - py;

				// different way to compute the distance
				dist = Math.sqrt( dx*dx + dy*dy );

				double moveLength = dist * scale;

				// vector from current point to closest point
				double[] move = new double[] {  px - x0, py - y0 };
				LinAlgHelpers.normalize( move );

				move[ 0 ] *= moveLength;
				move[ 1 ] *= moveLength;

				if ( c.getIntPosition( 2 ) == 1 ) // y
					t.setReal( t.getRealDouble() + move[ 1 ] );
				else // x
					t.setReal( t.getRealDouble() + move[ 0 ] );
			}

			// t.setReal( dist );

			/*
			if ( distX < halfKernelX.length && distY < halfKernelY.length )
			{
				w = halfKernelX[ distX ] * halfKernelY[ distY ];
	
				if ( c.getIntPosition( 2 ) == 1 ) // y
					t.setReal( t.getRealDouble() + moveBy[ 1 ] * w );
				else // x
					t.setReal( t.getRealDouble() + moveBy[ 0 ] * w );
			}*/
		}
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

	@SuppressWarnings("serial")
	public static class Options extends AbstractOptions implements Serializable {

		@Option(name = "--n5Path", required = true, usage = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
		private String n5Path = null;

		@Option(name = "-i", aliases = {"--n5Group"}, required = true, usage = "N5 group, e.g. /align-0")
		private String group = null;

		@Option(name = "-o", aliases = {"--n5GroupSave"}, required = false, usage = "N5 group that is saved, e.g. /align-0-modified")
		private String groupSave = null;

		@Option(name = "--scaleIndex", required = true, usage = "scale index for visualization, e.g. 4 (means scale = 1.0 / 2^4)")
		private int transformScaleIndex = 0;

		public Options(final String[] args) {

			final CmdLineParser parser = new CmdLineParser(this);
			try {
				parser.parseArgument(args);

				parsedSuccessfully = true;

			} catch (final CmdLineException e) {
				System.err.println(e.getMessage());
				parser.printUsage(System.err);
			}
		}

		/**
		 * @return the n5Path
		 */
		public String getN5Path() {

			return n5Path;
		}

		/**
		 * @return the scaleIndex
		 */
		public int getScaleIndex() {

			return transformScaleIndex;
		}

		/**
		 * @return the group
		 */
		public String getGroup() {

			return group;
		}

		/**
		 * @return the group
		 */
		public String getSaveGroup() {

			return groupSave;
		}

	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		/*
		RandomAccessibleInterval<FloatType > img = ArrayImgs.floats( 300, 300 );
		scalePositionFieldBR07m(img, 0, 50, 100, 200 );
		new ImageJ();
		ImageJFunctions.show( img );
		SimpleMultiThreading.threadHaltUnClean();
		*/

		final Options options = new Options(args);

		if (!options.parsedSuccessfully)
			return;

//		new ImageJ();
	
		final N5Reader n5in = new N5FSReader(options.getN5Path());

		final int showScaleIndex = options.getScaleIndex();
		final double showScale = 1.0 / (1 << showScaleIndex);

		Bdv bdv = null;

		final int numProc = Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 );
		final SharedQueue queue = new SharedQueue( numProc );
		final CacheHints cacheHints = new CacheHints( LoadingStrategy.VOLATILE, 0, true );

		// the n5 input, e.g. align-0
		final String group = options.getGroup();

		if ( options.getSaveGroup() == null )
			System.out.println( "WARNING: Result is not being saved" );
		else
			System.out.println( "Result will be saved as " + options.getSaveGroup() );

		if ( options.getSaveGroup() != null && options.getSaveGroup().equals( group ) )
			throw new RuntimeException( "Cannot overwrite the align N5 group." );

		System.out.println( "showscale: " + showScale );

		final String[] datasetNames = n5in.getAttribute(group, "datasets", String[].class);
		final String[] transformDatasetNames = n5in.getAttribute(group, "transforms", String[].class);
		final int scaleIndex = n5in.getAttribute(group, "scaleIndex", Integer.class );
		final double[] boundsMin = n5in.getAttribute(group, "boundsMin", double[].class);
		final double[] boundsMax = n5in.getAttribute(group, "boundsMax", double[].class);

		System.out.println( "scaleIndex: " + scaleIndex + ". NOTE: any deformations defined here need to be in that scale (i.e. open with ViewAlignment and measure in those images)" );
		

		/*
		final String[] datasetNames = new String[ 4 ];
		final String[] transformDatasetNames = new String[ 4 ];
		for ( int i = 0;i < datasetNames.length; ++i )
		{
			datasetNames[ i ] = allDatasetNames[ i + 7 ];
			transformDatasetNames[ i ] = allTransformDatasetNames[ i + 9 ];
			System.out.println( datasetNames[ i ] + ", " + transformDatasetNames[ i ]);
		}
		*/

		final RealTransform[] realTransforms = new RealTransform[datasetNames.length];

		// for saving later
		final ArrayList< RandomAccessibleInterval< DoubleType > > positionFields = new ArrayList<>(datasetNames.length);
		final ArrayList< Pair< double[], double[] > > positionFieldBounds = new ArrayList<>(datasetNames.length);
		final double[] transformScales = new double[datasetNames.length];

		for (int i = 0; i < datasetNames.length; ++i) {

			final String datasetName = group + "/" + transformDatasetNames[i];

			//if ( datasetNames[ i ].contains( "Sec32") )
			//{
				//System.out.println( datasetNames[ i ]);
				//datasetNames[ i ] = datasetNames[ i ].substring( 0, datasetNames[ i ].indexOf( "Sec") + 5 ) + "_pass3/" + datasetNames[ i ].substring( datasetNames[ i ].indexOf( "Sec") + 6, datasetNames[ i ].length() );
				//System.out.println( datasetNames[ i ]);
			//}

			//realTransforms[i] = Transform.loadScaledTransform(
			//		n5,
			//		group + "/" + transformDatasetNames[i]);

			// note: the bounds are as far as I see identical to the dataset bounds
			final double[] boundsMinSurface = n5in.getAttribute(datasetName, "boundsMin", double[].class);
			final double[] boundsMaxSurface = n5in.getAttribute(datasetName, "boundsMax", double[].class);
			final double transformScale = n5in.getAttribute(datasetName, "scale", double.class);
			
			System.out.println( "\n" + datasetName + ", transformscale: " + transformScale );
			if ( transformScale != showScale )
				System.out.println( "WARNING: transformscale does not match showscale, be careful!" );

			//System.exit( 0 );
			final RandomAccessibleInterval<DoubleType> positionField = N5Utils.open(n5in, datasetName);
			final int n = positionField.numDimensions() - 1;
			final long[] translation = Arrays.copyOf(Grid.floorScaled(boundsMinSurface, transformScale), n + 1);

			final RandomAccessibleInterval< DoubleType > positionFieldAdjusted = Views.translate(positionField, translation);
			
			// modify the field if necessary
			//final RandomAccessibleInterval< DoubleType > positionFieldModified = modifyAlignmentVNC19m( positionFieldAdjusted, i, transformScale, datasetName );
			final RandomAccessibleInterval< DoubleType > positionFieldModified = modifyAlignmentBR07m( positionFieldAdjusted, i, transformScale, datasetName );

			// remember it for saving
			positionFields.add( positionFieldModified );
			positionFieldBounds.add( new ValuePair<>( boundsMinSurface, boundsMaxSurface ) );
			transformScales[ i ] = transformScale;

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

		System.out.println( "copying entire stack ... " );
		long t = System.currentTimeMillis();
		final long[] min = new long[ stack.numDimensions() ];
		stack.min( min );

		final RandomAccessibleInterval<FloatType> copy = Views.translate( new CellImgFactory<>( new FloatType(), (int)stack.dimension( 2 ) ).create( stack.dimensionsAsLongArray() ), min );
		final ExecutorService service = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
		Util.copy(stack, copy, service);
		service.shutdown();

		System.out.println( "took " + (( System.currentTimeMillis() - t )/1000) + " secs.");

		//BdvFunctions.show( copy, "transformed", new BdvOptions().addTo( bdv ).numRenderingThreads(Runtime.getRuntime().availableProcessors() ));
		new ImageJ();
		ImagePlus imp = ImageJFunctions.show( copy );

		/*
		bdv = Show.transformedStack(
				(RandomAccessibleInterval)VolatileViews.wrapAsVolatile(
						Show.wrapAsVolatileCachedCellImg(stack, new int[]{256, 256, 26}),
						queue,
						cacheHints),
				bdv);
		*/

		if ( options.getSaveGroup() != null )
		{
			System.out.println( "Saving " + options.getSaveGroup() );

			/* save transforms */
			final N5Writer n5out = new N5FSWriter(options.getN5Path());
			n5out.createGroup(options.getSaveGroup());
			n5out.setAttribute(options.getSaveGroup(), "datasets", datasetNames);
			n5out.setAttribute(options.getSaveGroup(), "transforms", transformDatasetNames);
			n5out.setAttribute(options.getSaveGroup(), "scaleIndex", options.getScaleIndex());
			n5out.setAttribute(options.getSaveGroup(), "boundsMin", boundsMin);
			n5out.setAttribute(options.getSaveGroup(), "boundsMax", boundsMax);

			for (int i = 0; i < datasetNames.length; ++i)
			{
				final String datasetName = options.getSaveGroup() + "/" + transformDatasetNames[i];
				
				Transform.savePositionField(
						n5out,
						datasetName,
						positionFields.get( i ),
						transformScales[ i ],
						positionFieldBounds.get( i ).getA(),
						positionFieldBounds.get( i ).getB() );
			}
			System.out.println( "done. " );
		}

//			ImageJFunctions.show(stack, group);

	}

}
