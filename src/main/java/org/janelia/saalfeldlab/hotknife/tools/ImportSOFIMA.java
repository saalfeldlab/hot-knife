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
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
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
	@Option(names = "--n5PathIn", required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5PathIn = null;

	@Option(names = "--n5PathOut", required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5PathOut = null;

	@Option(names = {"-i", "--n5GroupIn"}, required = true, description = "N5 group to load, e.g. /pass01")
	private String groupIn = "/";

	@Option(names = {"-o", "--n5GroupOut"}, required = true, description = "N5 group to save, e.g. /pass01-sofima")
	private String groupOut;

	@Option(names = {"--overwrite"}, required = false, description = "Overwrite an existing N5 group, specified with --n5GroupOut, default: false")
	private boolean overwrite = false;

	@Option(names = {"-s", "--sofimaField"}, required = true, description = "The SOFIMA transformation field, e.g. /nrs/flyem/data/sofima/3.invmap.zarr or gs://jane")
	private String sofimaField;

	@Option(names = "--scaleIndexSOFIMAinput", required = true, description = "The scale index at which the deformed images were fed to SOFIMA, needed for vector size adjustment (the same as --scaleIndex that was used in SparkViewAlignment)")
	private int scaleIndexSOFIMAinput;

	@Option(names = "--z", required = true, description = "surface slice index to apply the SOFIMA field to (if --zSplit is selected, only 50% will be applied to this surface)")
	private int z;

	@Option(names = "--zSplit", required = false, description = "second surface slice index to apply half of the deformation field to (i.e. the corresponding bot/top surface)")
	private Integer zSplit = null;

	@Override
	public final Void call()// throws IOException, InterruptedException, ExecutionException
	{
		for ( int z = 0; z < 179; ++z)
		{
		System.out.println( "hot-knife in: " + n5PathIn + Path.SEPARATOR + groupIn );
		System.out.println( "sofima: " + sofimaField );
		System.out.println( "hot-knife out: " + n5PathOut + Path.SEPARATOR + groupOut );

		final N5Reader n5 = new N5Factory().openReader( StorageFormat.N5, n5PathIn );

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

		System.out.println( "z (target): " + z );
		System.out.println( " dataset: " + datasetNames[ z ] );
		System.out.println( " transformDatasetName: " + transformDatasetNames[ z ] );
		System.out.println( " N5 transform datasetName: " + datasetName );

		final double amount = ( zSplit == null ) ? 1.0 : 0.5;
		final double direction = 1.0;

		process( sofimaField, scaleIndexSOFIMAinput, n5PathIn, n5PathOut, groupOut, datasetName, z, transformScaleDataset, blockSize, dataType, transformScaleIndexPass, datasetNames, transformDatasetNames, boundsMin, boundsMax, amount, direction, overwrite );

		System.out.println( "z (split): " + (zSplit == null ? "not active" : zSplit ) );

		if ( zSplit != null )
		{
			final String datasetName_ZSplit = groupIn + Path.SEPARATOR + transformDatasetNames[ zSplit ];
			final double transformScaleDataset_ZSplit = n5.getAttribute(datasetName_ZSplit, "scale", double.class);
			final int[] blockSize_ZSplit = n5.getAttribute( datasetName_ZSplit, "blockSize", int[].class);
			final DataType dataType_ZSplit = n5.getAttribute( datasetName_ZSplit, "dataType", DataType.class);

			System.out.println( " dataset zSplit: " + datasetNames[ zSplit ] );
			System.out.println( " transformDatasetName zSplit: " + transformDatasetNames[ zSplit ] );
			System.out.println( " N5 transform datasetName zSplit: " + datasetName_ZSplit );

			process( sofimaField, scaleIndexSOFIMAinput, n5PathIn, n5PathOut, groupOut, datasetName_ZSplit, zSplit, transformScaleDataset_ZSplit, blockSize_ZSplit, dataType_ZSplit, transformScaleIndexPass, datasetNames, transformDatasetNames, boundsMin, boundsMax, amount, -direction, overwrite );
		}

		n5.close();
		}
		return null;
	}

	public static void process(
			final String sofimaField,
			final int scaleIndexSOFIMAinput,
			final String n5PathIn,
			final String n5PathOut,
			final String groupOut,
			final String datasetName,
			final int z,
			final double transformScaleDataset,
			final int[] blockSize,
			final DataType dataType,
			final int transformScaleIndexPass,
			final String[] datasetNames,
			final String[] transformDatasetNames,
			final double[] boundsMin,
			final double[] boundsMax,
			final double amount, // for splitting
			final double direction, // for splitting
			final boolean overwrite )
	{
		final N5Reader n5in = new N5Factory().openWriter( StorageFormat.N5, n5PathIn );
		final N5Writer n5out = new N5Factory().openWriter( StorageFormat.N5, n5PathOut );
		final N5Reader sofimaContainer = new N5Factory().openReader( StorageFormat.N5, sofimaField );

		//
		// load the hot-knife position field
		//

		// we cannot use the convenience method because we need the translation[]
		//final RealTransform transform = Transform.loadScaledTransform( n5, datasetName );

		final RandomAccessibleInterval<DoubleType> positionFieldHotKnife = N5Utils.open(n5in, datasetName);
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
		System.out.println( "amount: " + amount);
		System.out.println( "direction: " + direction);

		//
		// load the SOFIMA relative deformation field and scale it
		//

		// XY axes are flipped compared to python (N5 solves that already)
		// still, first slice are X vectors, 2nd slice are Y vectors
		final RandomAccessibleInterval< DoubleType > sofimaRaw = N5Utils.open( sofimaContainer, "/" );

		System.out.println( Util.printInterval( sofimaRaw ));
		//System.exit( 0 );

		// Note: the SOFIMA field can contain NaN's
		final RandomAccessibleInterval< DoubleType > sofima;
		if ( sofimaRaw.numDimensions() == 4 && sofimaRaw.dimension( 2 ) == 0 )
			sofima = Converters.convertRAI(
					Views.hyperSlice(sofimaRaw, 2, 0),
					(i, o) -> o.set(Double.isNaN(i.get()) ? 0 : i.get()),
					new DoubleType());
		else
			sofima = Converters.convertRAI(
					(RandomAccessibleInterval< FloatType >)(RandomAccessibleInterval)sofimaRaw, // michal's field is actually float
					(i,o) -> o.set( Double.isNaN( i.getRealDouble() ) ? 0 : i.getRealDouble() ),
					new DoubleType() );

		// Michal's field is 4D, [2342, 2374, 2, 91]; the ZARR to N5 conversion mixed up Z and C, now [X,Y,C,Z]
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

		/*
		if ( scalingFactorSofima[ 0 ] < 1 || scalingFactorSofima[ 1 ] < 1 )
		{
			System.out.println( "WARNING: SOFIMA field is higher resolved than hot-knife field (e.g. SOFIMA used pass00 rendered at s2 as input), increasing scale of hot-knife field."  );
			//adjust scale of the hot-knife field, *2 until it's bigger, update transformScaleIndexPass accordingly

//			Our pass00: gs://janelia-spark-test/hess_wafers_60_61_export/surface-align/run_20260303_130000/pass00/
//			dim: "dimensions":[1466,1486,2]
//
//			Michal's surfaces
//			/nrs/hess/data/hess_wafers_60_61/export/zarr_datasets/surface-align/run_20260303_130000/pass00-scale1/260310_assembled_inv_highprec_ext.npy.zarr/
//			2342x2374x91x2
//
//			This map is computed at 40x reduced XY resolution and 2x reduced Z resolution, but the displacement vectors are expressed in the units of the original volume (16 nm/px I think?). 
//			I accidentally flipped the XY axes when importing your images, and the map reflects that. The axis order is [c, z, y, x], where the 2 'c' channels represent the x (0) and y (1) components of the vector. 
//			For your original coordinates system, you will therefore want something like: np.transpose(map[::-1, ...], (0, 1, 3, 2)). 
//			The map is in the 'pull' format, so to render a pixel at (x, y, z) of your target volume, you read the map as: x_off, y_off = map[:, z/2, y/40, x/40] and pull data from (x + x_off, y + y_off, z) in the original images.

			// now the size of the sofima field is <= size hot-knife field
			System.out.println( "new transformScaleIndexPass: " + transformScaleDataset  );
			System.out.println( "updated scalingFactor (SOFIMA relative to hot-knife): " + Arrays.toString( scalingFactorSofima ) );
			System.exit( 0 );

			// scale positionFieldHotKnife
		}
		*/

		// TODO: hack, for now we downsample the sofima field, it's just for a video; see above what we would need to do
		//if ( scalingFactorSofima[ 0 ] > 1 )
		{
			final AffineRandomAccessible<DoubleType, AffineGet> transformedX, transformedY;

			if ( sofima.numDimensions() == 3 )
			{
				// TODO: this is a rough approximation, need to handle this properly (right now x and y factor is slightly different)
				transformedX = RealViews.affine(
						Views.interpolate(
								Views.extendMirrorDouble( Views.hyperSlice( sofima, 2, 0 ) ),
								new NLinearInterpolatorFactory<>()),
						new Scale( scalingFactorSofima ) );

				// TODO: this is a rough approximation, need to handle this properly (right now x and y factor is slightly different)
				transformedY = RealViews.affine(
						Views.interpolate(
								Views.extendMirrorDouble( Views.hyperSlice( sofima, 2, 1 ) ),
								new NLinearInterpolatorFactory<>()),
						new Scale( scalingFactorSofima ) );
			}
			else if ( sofima.numDimensions() == 4 ) // Michal's 4D stack
			{
				// TODO: this is a rough approximation, need to handle this properly (right now x and y factor is slightly different)
				transformedX = RealViews.affine(
						Views.interpolate(
								Views.extendMirrorDouble( Views.hyperSlice( Views.hyperSlice( sofima, 3, z/2), 2, 0 ) ),
								new NLinearInterpolatorFactory<>()),
						new Scale( scalingFactorSofima ) );

				// TODO: this is a rough approximation, need to handle this properly (right now x and y factor is slightly different)
				transformedY = RealViews.affine(
						Views.interpolate(
								Views.extendMirrorDouble( Views.hyperSlice( Views.hyperSlice( sofima, 3, z/2), 2, 1 ) ),
								new NLinearInterpolatorFactory<>()),
						new Scale( scalingFactorSofima ) );
			}
			else
			{
				throw new IllegalArgumentException( "SOFIMA field of dim=" + sofima.numDimensions() + " not known." );
			}

			final RandomAccessibleInterval< DoubleType > sofimaScaledX = Views.interval( Views.raster( transformedX ), positionField2dInterval );
			final RandomAccessibleInterval< DoubleType > sofimaScaledY = Views.interval( Views.raster( transformedY ), positionField2dInterval );

			sofimaScaled = Views.stack( sofimaScaledX, sofimaScaledY );
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

			o.get().set( identity + ( direction * ( ( i.next().get() / sofimaBaseScale ) * transformScaleDataset ) ) * amount );
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
			if ( !n5out.exists( groupOut ) )
			{
				System.out.println( "Creating output group: " + groupOut );

				n5out.createGroup(groupOut);
				n5out.setAttribute(groupOut, "datasets", datasetNames);
				n5out.setAttribute(groupOut, "transforms", transformDatasetNames);
				n5out.setAttribute(groupOut, "scaleIndex", transformScaleIndexPass ); // TODO: is that still true, and does it matter?
				n5out.setAttribute(groupOut, "boundsMin", boundsMin);
				n5out.setAttribute(groupOut, "boundsMax", boundsMax);
			}

			if ( n5out.exists( datasetNameOut ) && overwrite )
			{
				System.out.println( "Deleting existing output group: " + datasetNameOut );
				n5out.remove( datasetNameOut );
			}

			if ( n5out.exists( datasetNameOut ) )
			{
				System.out.println( "Output group dataset " + datasetNameOut + " exists. Stopping.");
				return;
			}

			Transform.saveScaledTransform( n5out, datasetNameOut, transformSequence, transformScaleDataset, boundsMin, boundsMax );

		} catch (IOException e)
		{
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		n5in.close();
		n5out.close();
		sofimaContainer.close();

		System.out.println( "Done.");

		return;
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
