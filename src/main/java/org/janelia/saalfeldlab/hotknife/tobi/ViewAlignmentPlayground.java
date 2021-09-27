package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.AxisOrder;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import java.io.IOException;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.DoubleType;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

public class ViewAlignmentPlayground
{
	public static void main( String[] args ) throws IOException
	{
		System.setProperty( "apple.laf.useScreenMenuBar", "true" );

		final String n5Path = "/Volumes/External/data/janelia/Z0720_07m_BR";
		final int scaleIndex = 6;
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader( n5Path );

		final String transformGroup = passGroup + "/" + "flat.Sec26.top.face";


		final RandomAccessibleInterval< DoubleType > positionField = N5Utils.open( n5, transformGroup );
		final BdvStackSource< DoubleType > bdv = BdvFunctions.show( positionField, "positionField", Bdv.options().is2D().axisOrder( AxisOrder.XYC ) );

		final CoordinatesAndValuesOverlay overlay = new CoordinatesAndValuesOverlay(bdv.getBdvHandle().getViewerPanel());
		bdv.getBdvHandle().getViewerPanel().getDisplay().overlays().add( overlay );
	}

//		final String[] datasetNames = n5.getAttribute( passGroup, "datasets", String[].class );
//
//		System.out.println( "datasets" );
//		for ( String s : n5.getAttribute( passGroup, "datasets", String[].class ) )
//			System.out.println( s );
//
//		System.out.println( "transforms" );
//		for ( String s : n5.getAttribute( passGroup, "transforms", String[].class ) )
//			System.out.println( s );

//		final RealTransform t = Transform.loadScaledTransform( n5, transformGroup );

//		final double showScale = 1.0 / ( 1 << scaleIndex );

}
