package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.util.ArrayList;

import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;

import ij.ImageJ;
import loci.formats.FormatException;
import loci.formats.in.TiffReader;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.RealViews;
import net.imglib2.view.Views;

public class PresentationTools {

	public static void loadStackRaw( final String n5Path, final String id, final String channel, final String cam ) throws IOException, FormatException
	{
		N5Data n5 = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id );

		final int width, height;
		{
			try(final TiffReader firstSliceReader = new TiffReader()) {

				firstSliceReader.setId( n5.stacks.get( channel ).get( cam ).get(0).path);
				width = firstSliceReader.getSizeX();
				height = firstSliceReader.getSizeY();
				firstSliceReader.close();
			}
		}

		ArrayList<Integer> slices = new ArrayList<>();
		for ( int i = 0; i <= n5.lastSliceIndex; ++i )
			slices.add( i );

		SparkExtractGeometricPointDescriptorMatches.showStack(n5.stacks.get( channel ).get( cam ), slices, width, height);
	}

	public static void main( String[] args ) throws IOException, FormatException
	{
		final String n5Path = "/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5";

		new ImageJ();
		loadStackRaw( n5Path, "Pos007", "Ch515+594nm", "cam1");
	}
}
