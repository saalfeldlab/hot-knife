package org.janelia.saalfeldlab.hotknife.tools;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.hadoop.fs.Path;
import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.janelia.saalfeldlab.hotknife.util.Transform;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineRandomAccessible;
import net.imglib2.realtransform.PositionFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformSequence;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale;
import net.imglib2.type.numeric.real.DoubleType;
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

	@Option(names = {"--overwrite"}, required = false, description = "Overwrite an existing N5 group, specified with --n5GroupOut, default: false")
	private boolean overwrite = false;

	@Option(names = {"-s", "--sofimaField"}, required = true, description = "The SOFIMA transformation field, e.g. /nrs/flyem/data/sofima/3.invmap.zarr")
	private String sofimaField;

	@Option(names = "--scaleIndexSOFIMAinput", required = true, description = "The scale index at which the deformed images were fed to SOFIMA, needed for vector size adjustment (the same as --scaleIndex that was used in SparkViewAlignment)")
	private int scaleIndexSOFIMAinput;

	@Option(names = "--z", required = true, description = "surface slice index to apply it to")
	private int z;

	@Override
	public final Void call()// throws IOException, InterruptedException, ExecutionException
	{
		System.out.println( "hot-knife in: " + n5Path + Path.SEPARATOR + groupIn );
		System.out.println( "sofima: " + sofimaField );
		System.out.println( "hot-knife out: " + n5Path + Path.SEPARATOR + groupOut );

		final N5Writer n5 = new N5Factory().openWriter( StorageFormat.N5, n5Path );
		final N5Reader zarr = new N5Factory().openReader( StorageFormat.ZARR, sofimaField );

		//
		// load metadata
		//
		final String[] datasetNames = n5.getAttribute(groupIn, "datasets", String[].class);
		final String[] transformDatasetNames = n5.getAttribute(groupIn, "transforms", String[].class);
		final double[] boundsMin = n5.getAttribute(groupIn, "boundsMin", double[].class);
		final double[] boundsMax = n5.getAttribute(groupIn, "boundsMax", double[].class);
		final int transformScaleIndexPass = n5.getAttribute(groupIn, "scaleIndex", int.class);

		final String datasetName = groupIn + Path.SEPARATOR + transformDatasetNames[ z ];
		final double transformScaleDataset = n5.getAttribute(datasetName, "scale", double.class);
		final int[] blockSize = n5.getAttribute( datasetName, "blockSize", int[].class);
		final DataType dataType = n5.getAttribute( datasetName, "dataType", DataType.class);

		System.out.println( Arrays.toString( boundsMin ) + " >> " + Arrays.toString( boundsMax ));
		System.out.println( "dataset: " + datasetNames[ z ] );
		System.out.println( "transformDatasetName: " + transformDatasetNames[ z ] );
		System.out.println( "N5 transform datasetName: " + datasetName );
		System.out.println( "dataset: " + datasetNames[ z ] );

		//
		// load the hot-knife position field
		//

		// we cannot use the convenience method because we need the translation[]
		//final RealTransform transform = Transform.loadScaledTransform( n5, datasetName );

		final RandomAccessibleInterval<DoubleType> positionFieldHotKnife = N5Utils.open(n5, datasetName);
		final int n = positionFieldHotKnife.numDimensions() - 1;
		final long[] translation = Arrays.copyOf(Grid.floorScaled(boundsMin, transformScaleDataset), n + 1);
		final PositionFieldTransform<DoubleType> positionFieldHotKnifeTransform = Transform.createPositionFieldTransform(
				Views.translate(positionFieldHotKnife, translation));
		final RealTransform transformHotKnife = Transform.createScaledRealTransform(positionFieldHotKnifeTransform, transformScaleDataset);

		System.out.println( "dimensions of hot-knife position field: " + Arrays.toString( positionFieldHotKnife.dimensionsAsLongArray() ) );
		System.out.println( "translation: " + Arrays.toString( translation ));
		System.out.println( "scale: " + transformScaleDataset);
		System.out.println( "blockSize: " + Arrays.toString( blockSize ));
		System.out.println( "dataType: " + dataType);

		//
		// load the SOFIMA relative deformation field and scale it
		//

		// XY axes are flipped compared to python (N5 solves that already)
		// still, first slice are X vectors, 2nd slice are Y vectors
		final RandomAccessibleInterval< DoubleType > sofimaRaw = N5Utils.open( zarr, "/" );

		// Note: the SOFIMA field can contain NaN's
		final RandomAccessibleInterval< DoubleType > sofima;
		if ( sofimaRaw.numDimensions() == 4 )
			sofima = Converters.convertRAI(
					Views.hyperSlice(sofimaRaw, 2, 1),
					(i, o) -> o.set(Double.isNaN(i.get()) ? 0 : i.get()),
					new DoubleType());
		else
			sofima = Converters.convertRAI(
					sofimaRaw,
					(i,o) -> o.set( Double.isNaN( i.get() ) ? 0 : i.get() ),
					new DoubleType() );

		System.out.println( "dimensions of SOFIMA deformation field: " + Arrays.toString( sofima.dimensionsAsLongArray() ) );

		//
		// scale relative SOFIMA field and prepare to be pre-concatenated to hot-knife positionfield
		//
		final Interval positionField2dInterval = new FinalInterval( positionFieldHotKnife.dimension( 0 ), positionFieldHotKnife.dimension( 1 ) );
		final Interval sofima2DInterval = new FinalInterval( sofima.dimension( 0 ), sofima.dimension( 1 ) );

		// TODO: this is a rough approximation, need to handle this properly (right now x and y factor is slightly different)
		final double[] scalingFactorSofima = scalingFactor( positionField2dInterval, sofima2DInterval );

		// the vectors are scaled relative to the input image size, i.e. we need to know at which factor the images
		// that were fed into SOFIMA were scaled
		final double sofimaBaseScale = 1.0 / (1 << scaleIndexSOFIMAinput );

		System.out.println( "scalingFactor (SOFIMA relative to hot-knife): " + Arrays.toString( scalingFactorSofima ) );
		System.out.println( "scale at which the deformed images were fed to SOFIMA (needed for vector size adjustment): " + sofimaBaseScale );

		final RandomAccessibleInterval< DoubleType > sofimaScaled;

		if ( scalingFactorSofima[ 0 ] > 1 )
		{
			// TODO: this is a rough approximation, need to handle this properly (right now x and y factor is slightly different)
			final AffineRandomAccessible<DoubleType, AffineGet> transformedX = RealViews.affine(
					Views.interpolate(
							Views.extendMirrorDouble( Views.hyperSlice( sofima, 2, 0 ) ),
							new NLinearInterpolatorFactory<>()),
					new Scale( scalingFactorSofima ) );

			// TODO: this is a rough approximation, need to handle this properly (right now x and y factor is slightly different)
			final AffineRandomAccessible<DoubleType, AffineGet> transformedY = RealViews.affine(
					Views.interpolate(
							Views.extendMirrorDouble( Views.hyperSlice( sofima, 2, 1 ) ),
							new NLinearInterpolatorFactory<>()),
					new Scale( scalingFactorSofima ) );

			RandomAccessibleInterval< DoubleType > sofimaScaledX = Views.interval( Views.raster( transformedX ), positionField2dInterval );
			RandomAccessibleInterval< DoubleType > sofimaScaledY = Views.interval( Views.raster( transformedY ), positionField2dInterval );

			sofimaScaled = Views.stack( sofimaScaledX, sofimaScaledY );
		}
		else
		{
			throw new RuntimeException( "not supported yet." );
		}

		//
		// create a new positionfield for the ZARR SOFIMA import
		//
		final RandomAccessibleInterval<DoubleType> positionFieldSofimaRaw = ArrayImgs.doubles( positionFieldHotKnife.dimensionsAsLongArray() );
		final RandomAccessibleInterval<DoubleType> positionFieldSofima = Views.translate( positionFieldSofimaRaw, translation );

		final Cursor< DoubleType > o = Views.flatIterable( positionFieldSofima ).localizingCursor();
		final Cursor< DoubleType > i = Views.flatIterable( sofimaScaled ).localizingCursor();

		while ( o.hasNext() )
		{
			o.fwd();

			// create identity transformation (identity means X value contains X location, Y value contains Y location)
			final double identity = o.getDoublePosition( o.getIntPosition( 2 ) );

			// we need to adjust the sofima vectors for the original scale of the images and the scale of the hot-knife field
			//
			// The SOFIMA vectors have the same size, no matter with which stride they were computed,
			// so they must be in the size of the input images fed to SOFIMA
			//
			// Saalfeld's absolute transformation fields store the vectors in the scale the transformation fields
			// are stored in. E.g. at scale 0.03125 a value that is 2400, will be 4800 at scale 0.0625
			//
			// Next topic, values:
			// SOFIMA imports e.g. 343, 516 X=1.3092;Y=7.3169 (positive means move up)
			// SOFIMA x positive means move left

			o.get().set( identity + ( i.next().get() / sofimaBaseScale ) * transformScaleDataset );
		}

		final PositionFieldTransform<DoubleType> positionFieldSofimaTransform = Transform.createPositionFieldTransform( positionFieldSofima );
		final RealTransform transformSofima = Transform.createScaledRealTransform( positionFieldSofimaTransform, transformScaleDataset );

		final RealTransformSequence transformSequence = new RealTransformSequence();
		transformSequence.add(transformSofima); // pre-concatenate SOFIMA
		transformSequence.add(transformHotKnife);

		/*
		final double[] l1 = new double[] { 1000, 2000 };
		final double[] l2 = new double[ 2 ];
		transformA.apply(l1, l2);
		System.out.println( "transformA: " + Arrays.toString( l1 ) + " maps to: " + Arrays.toString( l2 ) );

		transformB.apply(l1, l2);
		System.out.println( "transformB: " + Arrays.toString( l1 ) + " maps to: " + Arrays.toString( l2 ) );

		transformSequence.apply(l1, l2);
		System.out.println( "transformSequence: " + Arrays.toString( l1 ) + " maps to: " + Arrays.toString( l2 ) );
		*/

		final String datasetNameOut = groupOut + Path.SEPARATOR + transformDatasetNames[ z ];

		try
		{
			if ( !n5.exists( groupOut ) )
			{
				System.out.println( "Creating output group: " + groupOut );

				n5.createGroup(groupOut);
				n5.setAttribute(groupOut, "datasets", datasetNames);
				n5.setAttribute(groupOut, "transforms", transformDatasetNames);
				n5.setAttribute(groupOut, "scaleIndex", transformScaleIndexPass ); // TODO: is that still true, and does it matter?
				n5.setAttribute(groupOut, "boundsMin", boundsMin);
				n5.setAttribute(groupOut, "boundsMax", boundsMax);
			}

			if ( n5.exists( datasetNameOut ) && overwrite )
			{
				System.out.println( "Deleting existing output group: " + datasetNameOut );
				n5.remove( datasetNameOut );
			}

			if ( n5.exists( datasetNameOut ) )
			{
				System.out.println( "Output group dataset " + datasetNameOut + " exists. Stopping.");
				return null;
			}

			Transform.saveScaledTransform( n5, datasetNameOut, transformSequence, transformScaleDataset, boundsMin, boundsMax );

		} catch (IOException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		n5.close();
		zarr.close();

		System.out.println( "Done.");

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
