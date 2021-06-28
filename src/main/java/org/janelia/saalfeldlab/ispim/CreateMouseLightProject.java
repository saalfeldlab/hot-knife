package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.janelia.saalfeldlab.hotknife.SparkConvertBlockedTiffSeriestoN5;
import org.janelia.saalfeldlab.hotknife.SparkConvertBlockedTiffSeriestoN5.MetaData;
import org.janelia.saalfeldlab.n5.N5FSWriter;

import com.google.gson.GsonBuilder;

import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.in.TiffReader;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.ByteType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;

public class CreateMouseLightProject {

	public static <T extends NativeType<T>> MetaData<T> openAndParseMetaData( final String file ) throws FormatException, IOException
	{
		final TiffReader r = new TiffReader();
		r.setId( file );

		final long[] dim;

		if ( r.getSizeT() > 1 )
			System.out.println( "Warning, more than one timepoints, not supported right now.");

		if ( r.getSizeC() > 1 )
			System.out.println( "Warning, more than one channel, not supported right now.");

		if ( r.getSizeZ() > 1 )
			dim = new long[] { r.getSizeX(), r.getSizeY(), r.getSizeZ() };
		else
			dim = new long[] { r.getSizeX(), r.getSizeY() };

		final int pixelType = r.getPixelType();
		final T type;

		if ( pixelType == FormatTools.UINT8 )
			type = (T)(Object) new UnsignedByteType();
		else if ( pixelType == FormatTools.INT8 )
			type = (T)(Object) new ByteType();
		else if ( pixelType == FormatTools.UINT16 )
			type = (T)(Object) new UnsignedShortType();
		else if ( pixelType == FormatTools.INT16 )
			type = (T)(Object) new ShortType();
		else if ( pixelType == FormatTools.FLOAT )
			type = (T)(Object) new FloatType();
		else
			throw new RuntimeException( "Import type " + pixelType + " not supported." );

		return new MetaData<>( dim, type, r, pixelType, r.getBitsPerPixel(), r.isLittleEndian() );
	}

	public static void main( String[] args ) throws IOException, FormatException
	{
		final N5FSWriter n5 = new N5FSWriter(
				"/nrs/mouselight/lightsheet/2021-02-12/021221_JChandrashekar_HHMI/S1.n5",
				new GsonBuilder().registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		// only a single camtransform with identity transformation
		final String channel = "Ch488nm";
		final String cam = "cam1";

		final HashMap<String, HashMap<String, AffineTransform2D>> camTransforms = new HashMap<>();

		camTransforms.put( channel, new HashMap<>() );
		camTransforms.get( channel ).put( cam, new AffineTransform2D() );

		n5.setAttribute(
				"/",
				"camTransforms",
				camTransforms );
		/*n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, AffineTransform2D>>>() {}.getType());*/

		// define all datasets and set slices for each of them
		final String basepath = "/nrs/mouselight/lightsheet/2021-02-12/021221_JChandrashekar_HHMI/S1/S1_0000";

		final ArrayList< String > stacks = new ArrayList<>();
		for ( int y = 0; y <= 3 /*69*/; ++y )
			for ( int x = 0; x <= 2 /*24*/; ++x )
			{
				final String posShort = "Pos" + String.format("%03d", y ) + "-" + String.format("%03d", x );

				stacks.add( posShort );// "S1_0000_MMStack_Pos" + y + "_" + x + ".ome.tif" );

				final String fileName = basepath + "/S1_0000_MMStack_Pos" + y + "_" + x + ".ome.tif";
				final ArrayList<Slice> slices = new ArrayList<>();

				System.out.println( "Parsing " + fileName );

				final TiffReader r = new TiffReader();
				r.setId( fileName );

				// timepoints is z ?!?!?
				for ( int z = 0; z < r.getSizeT(); ++z )
				{
					Slice s = new Slice();
					s.affine = null;
					s.index = z;
					s.path = fileName;

					slices.add( s );
				}

				r.close();

				n5.createGroup( "/" + posShort + "/" + channel + "/" + cam );
				n5.setAttribute( "/" + posShort + "/" + channel + "/" + cam, "slices", slices );
				/*
				stack = n5.getAttribute(
					groupName,
					"slices",
					new TypeToken<ArrayList<Slice>>() {}.getType());
				 */
			}

		n5.setAttribute( "/", "stacks", stacks );
		/*
		n5.getAttribute(
		"/",
		"stacks",
		new TypeToken<ArrayList<String>>() {}.getType()); */

		// set slices for each dataset

		n5.close();
	}
}
