package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.util.ArrayList;

import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;

import ij.ImageJ;
import loci.formats.FormatException;
import loci.formats.in.TiffReader;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Intervals;
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

	public static void printAlignments( final String n5Path, final String id, final String channel, final String cam ) throws IOException
	{
		N5Data n5 = SparkPaiwiseAlignChannelsGeo.openN5( n5Path, id );
		RandomAccessible<AffineTransform2D> align = n5.alignments.get( channel );

		AffineTransform2D last = null;

		for ( final AffineTransform2D t : Views.iterable( Views.interval( align, new long[] { 0 }, new long[]{ n5.lastSliceIndex } ) ) )
		{
			if ( last != null )
			{
				double x0 = last.getRowPackedCopy()[ 2 ];
				double y0 = last.getRowPackedCopy()[ 5 ];

				double x1 = t.getRowPackedCopy()[ 2 ];
				double y1 = t.getRowPackedCopy()[ 5 ];

				double deltaX = x0 - x1;
				double deltaY = y0 - y1;

				System.out.println( deltaX + "\t" + deltaY );
			}

			last = t;
		}
	}

	public static void loadUnshearedStack( final String n5Path, final String id, final String channel, final String cam ) throws IOException, FormatException
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

		ArrayList<RandomAccessibleInterval<UnsignedShortType>> imgs = SparkExtractGeometricPointDescriptorMatches.loadStack(n5.stacks.get( channel ).get( cam ), slices, width, height);
		RandomAccessible<AffineTransform2D> align = n5.alignments.get( channel );

		ArrayList<RandomAccessible<UnsignedShortType>> unsheared = new ArrayList<>();

		RealInterval interval = null;

		for ( final int i : slices )
		{
			final RandomAccessibleInterval< UnsignedShortType > img = imgs.get( i );

			if ( interval == null )
				interval = align.getAt( i ).estimateBounds( img );
			else
				interval = Intervals.union( interval, align.getAt( i ).estimateBounds( img ) );

			unsheared.add( RealViews.affine( Views.interpolate( Views.extendZero( imgs.get( i ) ), new NLinearInterpolatorFactory<>() ), align.getAt( i ) ) );
		}

		Interval outInterval = Intervals.smallestContainingInterval( interval );

		ArrayList<RandomAccessibleInterval<UnsignedShortType>> unshearedInterval = new ArrayList<>();

		for ( final RandomAccessible<UnsignedShortType> img : unsheared )
			unshearedInterval.add( Views.interval( img, outInterval ) );

		ImageJFunctions.show( Views.stack( unshearedInterval ) );
	}

	public static void main( String[] args ) throws IOException, FormatException
	{
		final String n5Path = "/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5";

		//new ImageJ();
		//loadStackRaw( n5Path, "Pos007", "Ch488+561+647nm", "cam1");
		printAlignments( n5Path, "Pos007", "Ch488+561+647nm", "cam1");
	}
}
