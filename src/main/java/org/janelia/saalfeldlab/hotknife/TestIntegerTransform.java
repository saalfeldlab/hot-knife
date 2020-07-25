package org.janelia.saalfeldlab.hotknife;

import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.LinAlgHelpers;
import net.imglib2.util.Util;

public class TestIntegerTransform
{
	public static void main( String[] args )
	{
		//
		// set up transformation with shearing, scaling, rotation
		//
		final AffineTransform3D tr = new AffineTransform3D();
		
		tr.set( 1.0, 0.0, 0.0, 0.0,
				0.0, 1.0, -5.5, 0.0, // arbitrary shearing
				0.0, 0.0, 1.0, 0.0 );

		System.out.println( "input shearing: " + tr );

//		final AffineTransform3D scale = new AffineTransform3D();
//		scale.scale( 2, 3, 4 );
//		tr.preConcatenate( scale );
//		System.out.println( "+ scale: " + tr );

		final AffineTransform3D rot = new AffineTransform3D();
		rot.rotate( 0, Math.toRadians( 20 ));
		rot.rotate( 1, Math.toRadians( 20 ));
		rot.rotate( 2, Math.toRadians( 20 ));
		tr.preConcatenate( rot );
		System.out.println( "+ rotation: " + tr );

		//
		// compute normalized axes in target coordinate system (extract shearing/scaling combo)
		// with a length of 1 (no gaps)
		//
		final double[] px = new double[] { 1, 0, 0 };
		final double[] qx = px.clone();
		tr.apply( px, qx );
		System.out.println( "l (qx): " + LinAlgHelpers.length( qx ) );
		LinAlgHelpers.normalize( qx );

		final double[] py = new double[] { 0, 1, 0 };
		final double[] qy = py.clone();
		tr.apply( py, qy );
		System.out.println( "l (qy): " + LinAlgHelpers.length( qy ) );
		LinAlgHelpers.normalize( qy );

		final double[] pz = new double[] { 0, 0, 1 };
		final double[] qz = pz.clone();
		tr.apply( pz, qz );
		System.out.println( "l (qz): " + LinAlgHelpers.length( qz ) );
		LinAlgHelpers.normalize( qz );

		System.out.println( "x: " + Util.printCoordinates( px ) + " > " + Util.printCoordinates( qx ) );
		System.out.println( "y: " + Util.printCoordinates( py ) + " > " + Util.printCoordinates( qy ) );
		System.out.println( "z: " + Util.printCoordinates( pz ) + " > " + Util.printCoordinates( qz ) );

		// https://math.stackexchange.com/questions/1267817/angle-between-two-4d-vectors
		final double angleXY = Math.acos( LinAlgHelpers.dot( qx, qy ) );
		final double angleXZ = Math.acos( LinAlgHelpers.dot( qx, qz ) );
		final double angleYZ = Math.acos( LinAlgHelpers.dot( qy, qz ) );

		System.out.println( "xy (out): " +  Math.toDegrees( angleXY ) );
		System.out.println( "xz (out): " +  Math.toDegrees( angleXZ ) );
		System.out.println( "yz (out): " +  Math.toDegrees( angleYZ ) );

		System.out.println( "shearing XY " + 1.0 / Math.tan( angleXY ) );
		System.out.println( "shearing XZ " + 1.0 / Math.tan( angleXZ ) );
		System.out.println( "shearing YZ " + 1.0 / Math.tan( angleYZ ) );
	}
}
