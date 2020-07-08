package org.janelia.saalfeldlab.hotknife;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

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
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.realtransform.PositionFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

public class ModifyAlignment
{
	public static <T extends RealType<T>> RandomAccessibleInterval<T> modifyAlignmentVNC19m(
			final RandomAccessibleInterval<T> positionField,
			final int surfaceCount,
			final double transformScale,
			final String datasetName )
	{
		/*
		Loading: /align-v3/align-1-testb/align-v3.slab-2.top.face
		count: 0
		Loading: /align-v3/align-1-testb/align-v3.slab-2.bot.face
		count: 1
		Loading: /align-v3/align-1-testb/align-v3.slab-3.top.face
		count: 2
		Loading: /align-v3/align-1-testb/align-v3.slab-3.bot.face
		count: 3
		Loading: /align-v3/align-1-testb/align-v3.slab-4.top.face
		count: 4
		Loading: /align-v3/align-1-testb/align-v3.slab-4.bot.face
		count: 5

		 */

		if ( surfaceCount == 1 ) // Loading: /align-v3/align-1-testb/align-v3.slab-2.bot.face
		{
			System.out.println( "Modifying: " + datasetName + " (" + surfaceCount + ")" );

			if ( transformScale != 0.0625 )
				throw new RuntimeException( "These parameters were designed for a transform scaling of 0.0625 and do not match for other scalings." );

			final RandomAccessibleInterval< DoubleType > positionFieldCopy =
					ModifyAlignment.copyPositionField( (RandomAccessibleInterval)positionField );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 800, 135 },
					new double[] { 0, -70 / -2.0 },
					new double[] { 350, 150 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1190, 390 },
					new double[] { 26 / -2.0, -20 / -2.0 },
					new double[] { 200, 200 } );

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
					new int[] { 800, 135 },
					new double[] { 0, -70 / 2 },
					new double[] { 350, 150 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1190, 390 },
					new double[] { 26 / 2, -20 / 2 },
					new double[] { 200, 200 } );

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
					new int[] { 800, 135 },
					new double[] { -25 / 2.0, -45/ 2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1152, 408 },
					new double[] { -10 / 2.0, -5 / 2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 950, 390 },
					new double[] { 0, 10 / 2.0 },
					new double[] { 150, 150 } );

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
					new int[] { 800, 135 },
					new double[] { -25 / -2.0, -45 / -2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 1152, 408 },
					new double[] { -10 / -2.0, -5 / -2.0 },
					new double[] { 200, 200 } );

			ModifyAlignment.modifyPositionField(
					positionFieldCopy,
					new int[] { 950, 390 },
					new double[] { 0, 10 / -2.0 },
					new double[] { 150, 150 } );

			return (RandomAccessibleInterval)ModifyAlignment.setPositionFieldBounds( positionFieldCopy, positionField );
		}
		else
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
		final double[] boundsMin = n5in.getAttribute(group, "boundsMin", double[].class);
		final double[] boundsMax = n5in.getAttribute(group, "boundsMax", double[].class);

		final RealTransform[] realTransforms = new RealTransform[datasetNames.length];

		// for saving later
		final ArrayList< RandomAccessibleInterval< DoubleType > > positionFields = new ArrayList<>(datasetNames.length);
		final ArrayList< Pair< double[], double[] > > positionFieldBounds = new ArrayList<>(datasetNames.length);
		final double[] transformScales = new double[datasetNames.length];

		for (int i = 0; i < datasetNames.length; ++i) {

			final String datasetName = group + "/" + transformDatasetNames[i];

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

			final RandomAccessibleInterval<DoubleType> positionField = N5Utils.open(n5in, datasetName);
			final int n = positionField.numDimensions() - 1;
			final long[] translation = Arrays.copyOf(Grid.floorScaled(boundsMinSurface, transformScale), n + 1);

			final RandomAccessibleInterval< DoubleType > positionFieldAdjusted = Views.translate(positionField, translation);
			
			// modify the field if necessary
			final RandomAccessibleInterval< DoubleType > positionFieldModified = modifyAlignmentVNC19m( positionFieldAdjusted, i, transformScale, datasetName );

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

		bdv = Show.transformedStack(
				(RandomAccessibleInterval)VolatileViews.wrapAsVolatile(
						Show.wrapAsVolatileCachedCellImg(stack, new int[]{256, 256, 26}),
						queue,
						cacheHints),
				bdv);

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
