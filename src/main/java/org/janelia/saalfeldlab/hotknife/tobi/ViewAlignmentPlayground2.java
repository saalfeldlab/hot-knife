package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.tools.brightness.ConverterSetup;
import bdv.util.AxisOrder;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.Bounds;
import bdv.viewer.ConverterSetups;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.SynchronizedViewerState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

public class ViewAlignmentPlayground2
{
	public static void main( String[] args ) throws IOException
	{
		System.setProperty( "apple.laf.useScreenMenuBar", "true" );

		final String n5Path = "/Volumes/External/data/janelia/Z0720_07m_BR";
//		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final int scaleIndex = 6;
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader( n5Path );

		final String[] transforms = n5.getAttribute( passGroup, "transforms", String[].class );

		final List< RandomAccessibleInterval< DoubleType > > absoluteFields = new ArrayList<>();
		final List< RandomAccessibleInterval< DoubleType > > relativeFields = new ArrayList<>();
		for ( String s : transforms )
		{
			final String transformGroup = passGroup + "/" + s;
			final RandomAccessibleInterval< DoubleType > absolute = N5Utils.open( n5, transformGroup );
			final RandomAccessibleInterval< DoubleType > relative = Views.interval(
					new FunctionRandomAccessible<>(
							absolute.numDimensions(),
							() -> {
								final RandomAccess< DoubleType > input = Views.extendBorder( absolute ).randomAccess();
								return ( pos, value ) -> {
									input.setPosition( pos );
									final int d = pos.getIntPosition( pos.numDimensions() - 1 );
									value.set( input.get().get() - pos.getDoublePosition( d ) );
								};
							},
							DoubleType::new ),
					absolute );

			absoluteFields.add( absolute );
			relativeFields.add( relative );
		}

		final RandomAccessibleInterval< DoubleType > absolute = Views.stack( absoluteFields );
		final Bdv bdv = BdvFunctions.show( absolute, "fields", Bdv.options().is2D().axisOrder( AxisOrder.XYCT ) );

		final RandomAccessibleInterval< DoubleType > relative = Views.stack( relativeFields );
		BdvFunctions.show( relative, "relative", Bdv.options().addTo( bdv ).axisOrder( AxisOrder.XYCT ) );


		final SynchronizedViewerState state = bdv.getBdvHandle().getViewerPanel().state();
		final ConverterSetups setups = bdv.getBdvHandle().getConverterSetups();
		final List< SourceAndConverter< ? > > sources = state.getSources();
		final List< ConverterSetup > converterSetups = setups.getConverterSetups( sources );
		state.setSourceActive( sources.get( 0 ), false );
		state.setSourceActive( sources.get( 1 ), false );
		state.setSourceActive( sources.get( 2 ), true );
		state.setSourceActive( sources.get( 3 ), true );
		converterSetups.get( 0 ).setColor( new ARGBType(0xff00ff) );
		converterSetups.get( 1 ).setColor( new ARGBType(0x00ff00) );
		converterSetups.get( 2 ).setColor( new ARGBType(0xff00ff) );
		converterSetups.get( 3 ).setColor( new ARGBType(0x00ff00) );
		converterSetups.forEach( s -> s.setDisplayRange( -2000, 2000 ) );
		converterSetups.forEach( s -> setups.getBounds().setBounds( s, new Bounds( -5000, 5000 ) ) );
	}
}
