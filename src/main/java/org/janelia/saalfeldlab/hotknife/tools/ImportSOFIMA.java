package org.janelia.saalfeldlab.hotknife.tools;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.apache.hadoop.fs.Path;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;

import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineRandomAccessible;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

/**
 * This code imports a deformation field as computed by SOFIMA (https://github.com/google-research/sofima) into the hot-knife pipeline.
 * The general workflow is as follows:
 *  - use SparkViewAlignment to export transformed images at a certain pass (e.g. pass00 - just affine, or pass03 - just local SIFT)
 *  - use SOFIMA to align the exported, transformed images
 *  - load the SOFIMA deformation field and apply it to the deformation field of the pass we exported the images for (e.g. to pass03-sofima)
 *  - continue with the normal workflow (e.g. manual correction, export, further alignment)
 */
public class ImportSOFIMA implements Callable<Void>
{
	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"-i", "--n5GroupIn"}, required = true, description = "N5 group to load, e.g. /pass01")
	private String groupIn = "/";

	@Option(names = {"-o", "--n5GroupOut"}, required = true, description = "N5 group to save, e.g. /pass01-sofima")
	private String groupOut;

	@Option(names = {"-s", "--sofimaField"}, required = true, description = "The SOFIMA transformation field, e.g. /nrs/flyem/data/sofima/3.invmap.zarr")
	private String sofimaField;

	@Option(names = "--z", required = true, description = "surface slice index to apply it to")
	private int z;

	@Override
	public final Void call()// throws IOException, InterruptedException, ExecutionException
	{
		System.out.println( n5Path );
		System.out.println( sofimaField );

		final N5Reader n5 = new N5Factory().openReader( StorageFormat.N5, n5Path );
		final N5Reader zarr = new N5Factory().openReader( StorageFormat.ZARR, sofimaField );

		final String[] datasetNames = n5.getAttribute(groupIn, "datasets", String[].class);
		final String[] transformDatasetNames = n5.getAttribute(groupIn, "transforms", String[].class);
		final double[] boundsMin = n5.getAttribute(groupIn, "boundsMin", double[].class);
		final double[] boundsMax = n5.getAttribute(groupIn, "boundsMax", double[].class);

		final String datasetName = groupIn + Path.SEPARATOR + transformDatasetNames[ z ];

		final double transformScale = n5.getAttribute(datasetName, "scale", double.class);

		System.out.println( Arrays.toString( boundsMin ) + " >> " + Arrays.toString( boundsMax ));

		System.out.println( "dataset: " + datasetNames[ z ] );
		System.out.println( "transformDatasetName: " + transformDatasetNames[ z ] );
		System.out.println( "N5 transform datasetName: " + datasetName );

		// load the position field
		final RandomAccessibleInterval<DoubleType> positionField = N5Utils.open(n5, datasetName);
		final int n = positionField.numDimensions() - 1;
		final long[] translation = Arrays.copyOf(Grid.floorScaled(boundsMin, transformScale), n + 1);

		System.out.println( "scale: " + transformScale);
		System.out.println( "translation: " + Arrays.toString( translation ));

		// Note: the SOFIMA field can contain NaN's
		final RandomAccessibleInterval< DoubleType > sofimaRaw = N5Utils.open( zarr, "/" );
		final RandomAccessibleInterval< DoubleType > sofima;

		if ( sofimaRaw.numDimensions() == 4 )
			sofima = Views.hyperSlice( sofimaRaw, 2, 1 );
		else
			sofima = sofimaRaw;

		System.out.println( "dimensions of hot-knife position field: " + Arrays.toString( positionField.dimensionsAsLongArray() ) );
		System.out.println( "dimensions of SOFIMA deformation field: " + Arrays.toString( sofima.dimensionsAsLongArray() ) );

		final Interval positionField2dInterval = new FinalInterval( positionField.dimension( 0 ), positionField.dimension( 1 ) );
		final Interval sofima2DInterval = new FinalInterval( sofima.dimension( 0 ), sofima.dimension( 1 ) );

		final double[] scalingFactor = scalingFactor( positionField2dInterval, sofima2DInterval );

		System.out.println( "scalingFactor (SOFIMA relative to hot-knife): " + Arrays.toString( scalingFactor ) );

		new ImageJ();
		ImageJFunctions.show( positionField, Executors.newFixedThreadPool( 36 ) );
		//ImageJFunctions.show( sofima, Executors.newFixedThreadPool( 36 ) );

		final RandomAccessibleInterval< DoubleType > sofimaScaledX, sofimaScaledY;
		final RandomAccessibleInterval< DoubleType > positionFieldScaledX, positionFieldScaledY;
		final RandomAccessibleInterval< DoubleType > outputX, outputY;

		if ( scalingFactor[ 0 ] > 1 )
		{
			// we need to increase the size of the SOFIMA field
			final AffineRandomAccessible<DoubleType, AffineGet> transformedX = RealViews.affine(
					Views.interpolate(
							Views.extendMirrorDouble( Views.hyperSlice( sofima, 2, 0 ) ),
							new NLinearInterpolatorFactory<>()),
					new Scale( scalingFactor ) );

			final AffineRandomAccessible<DoubleType, AffineGet> transformedY = RealViews.affine(
					Views.interpolate(
							Views.extendMirrorDouble( Views.hyperSlice( sofima, 2, 1 ) ),
							new NLinearInterpolatorFactory<>()),
					new Scale( scalingFactor ) );

			sofimaScaledX = Views.interval( Views.raster( transformedX ), positionField2dInterval );
			sofimaScaledY = Views.interval( Views.raster( transformedY ), positionField2dInterval );

			ImageJFunctions.show( sofimaScaledX );
			ImageJFunctions.show( sofimaScaledY );

			// original scale
			positionFieldScaledX = Views.hyperSlice( positionField, 2, 0 );
			positionFieldScaledY = Views.hyperSlice( positionField, 2, 0 );

			outputX = new CellImgFactory<>( new DoubleType() ).create( positionFieldScaledX );
			outputY = new CellImgFactory<>( new DoubleType() ).create( positionFieldScaledY );
		}
		else
		{
			// TODO
			sofimaScaledX = sofimaScaledY = positionFieldScaledX = positionFieldScaledY = outputX = outputY = null;
		}

		Cursor< DoubleType > outC = Views.flatIterable( outputX ).localizingCursor();
		Cursor< DoubleType > sofimaC = Views.flatIterable( sofimaScaledX ).localizingCursor();
		Cursor< DoubleType > pfC = Views.flatIterable( positionFieldScaledX ).localizingCursor();

		while ( outC.hasNext() )
			outC.next().set( pfC.next().get() + sofimaC.next().get() );

		outC = Views.flatIterable( outputY ).localizingCursor();
		sofimaC = Views.flatIterable( sofimaScaledY ).localizingCursor();
		pfC = Views.flatIterable( positionFieldScaledY ).localizingCursor();

		while ( outC.hasNext() )
			outC.next().set( pfC.next().get() + sofimaC.next().get() );

		RandomAccessibleInterval<DoubleType> output = Views.stack( outputX, outputY );
		
		ImageJFunctions.show( output );
		//System.out.println( pass + ": " + Util.printInterval( pass ) );
		
		//System.out.println( sofima + ": " + Util.printInterval( sofima ) );

		//final double[] boundsMin = n5.getAttribute(datasetName, "boundsMin", double[].class);
		
		//Transform.loadScaledTransform(n5, groupIn);
		//final List<RealTransform> realTransforms = Collections.singletonList(Transform.loadScaledTransform(n5, group + "/" + transformDatasetName));
		//Transform.createScaledRealTransform(transforms.get(i), scaleIndex);

		n5.close();
		zarr.close();

		return null;
	}

	public static double[] scalingFactor( final Interval a, Interval b )
	{
		final double[] s = new double[ a.numDimensions() ];

		for ( int d = 0; d < a.numDimensions(); ++d )
			s[ d ] = (double) a.dimension( d ) / (double) b.dimension( d );

		return s;
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new ImportSOFIMA(), args);
	}

}
