/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.janelia.saalfeldlab.hotknife.util.Util;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;
import org.janelia.saalfeldlab.n5.N5FSReader;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.Interpolation;
import ij.ImageJ;
import loci.formats.FormatException;
import loci.formats.in.TiffReader;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImgs;
import net.imglib2.img.imageplus.ShortImagePlus;
import net.imglib2.img.list.ListImg;
import net.imglib2.interpolation.Interpolant;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.interpolation.stack.LinearRealRandomAccessibleStackInterpolatorFactory;
import net.imglib2.interpolation.stack.NearestNeighborRealRandomAccessibleStackInterpolatorFactory;
import net.imglib2.outofbounds.OutOfBoundsConstantValueFactory;
import net.imglib2.outofbounds.OutOfBoundsFactory;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineRealRandomAccessible;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.ExtendedRandomAccessibleInterval;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
@Command(name = "ViewWangStack", mixinStandardHelpOptions = true, version = "0.0.4-SNAPSHOT", description = "Visualize a stack from Tim Wang's multi-camera iSPIM")
public class ViewISPIMStack implements Callable<Void>, Serializable {

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--id", required = true, description = "Stack key, e.g. Pos012")
	private String id = null;

	@Option(names = "--first", required = false, description = "First slice index, e.g. 0 (default 0)")
	private int firstSliceIndex = 0;

	@Option(names = "--last", required = false, description = "Last slice index, e.g. 1000 (default MAX)")
	private int lastSliceIndex = Integer.MAX_VALUE;

	@Option(names = "--shearX", required = false, description = "shearing of z into x, e.g. -13 (default 0)")
	private double shearX = 0;

	@Option(names = "--shearY", required = false, description = "shearing of z into y, e.g. -1 (default 0)")
	private double shearY = 0;

	@Option(names = "--displayImageJStack", required = false, description = "display as ImageJ stack (default off)")
	private boolean displayImageJStack = false;

	@Option(names = "--overlayDoG", required = false, description = "overlay 3D DoG detections (default false)")
	private boolean overlayDoG = false;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		new CommandLine(new ViewISPIMStack()).execute(args);
	}

	public static <T extends NumericType<T> & NativeType<T>> ValuePair<List<RealRandomAccessible<T>>, RealInterval> openStack(
			final List<Slice> slices,
			final OutOfBoundsFactory<T, RandomAccessibleInterval<T>> outOfBoundsFactory,
			final InterpolatorFactory<T, RandomAccessible<T>> interpolatorFactory,
			final int firstSliceIndex,
			final int lastSliceIndex) throws FormatException, IOException {

		try (TiffReader reader = new TiffReader()) {
			reader.setId(slices.get(firstSliceIndex).path);
			final int width = reader.getSizeX();
			final int height = reader.getSizeY();

			final ArrayList<RealRandomAccessible<T>> slicesList = new ArrayList<>();
			RealInterval bounds = null;
			for (int i = firstSliceIndex; i <= lastSliceIndex; ++i) {
				final Slice slice = slices.get(i);
				final RandomAccessibleInterval<T> img =
						Opener.openSlice(
								reader,
								slice.path,
								slice.index,
								width,
								height);
				if (bounds == null)
					bounds = img;
				else
					bounds = Intervals.union(bounds, img);
				final ExtendedRandomAccessibleInterval<T, RandomAccessibleInterval<T>> extended = Views.extend(img, outOfBoundsFactory);
				final RealRandomAccessible<T> interpolant = Views.interpolate(extended, interpolatorFactory);
				if (slice.affine == null || slice.affineTransform().isIdentity() )
					slicesList.add(interpolant);
				else
					slicesList.add(RealViews.affineReal(interpolant, slice.affineTransform()));
			}

			return new ValuePair<>(slicesList, bounds);
		}
	}

	public static Interval openStackSize(
			final List<Slice> slices,
			final int firstSliceIndex ) throws FormatException, IOException {

		try (TiffReader reader = new TiffReader()) {

			reader.setId(slices.get(firstSliceIndex).path);
			final int width = reader.getSizeX();
			final int height = reader.getSizeY();

			return new FinalInterval( new long[] { 0, 0 }, new long[] { width - 1, height - 1 } );
		}
	}

	public static <T extends RealType<T> & NativeType<T>> ValuePair<List<RealRandomAccessible<FloatType>>, RealInterval> openFloatConvertedStack(
			final List<Slice> slices,
			final float background,
			final Interpolation interpolationMethod,
			final int firstSliceIndex,
			final int lastSliceIndex) throws FormatException, IOException {

		try (TiffReader reader = new TiffReader()) {
			reader.setId(slices.get(0).path);
			final int width = reader.getSizeX();
			final int height = reader.getSizeY();

			final ArrayList<RealRandomAccessible<FloatType>> slicesList = new ArrayList<>();
			RealInterval bounds = null;
			for (int i = firstSliceIndex; i < lastSliceIndex; ++i) {

				final Slice slice = slices.get(i);
				final RandomAccessibleInterval<T> img =
						Opener.openSlice(
								reader,
								slice.path,
								slice.index,
								width,
								height);
				if (bounds == null)
					bounds = img;
				else
					bounds = Intervals.union(bounds, img);

				final RandomAccessibleInterval<FloatType> converted =
						Converters.convert(
								img,
								(a, b) -> b.setReal(a.getRealFloat()),
								new FloatType());
				final ExtendedRandomAccessibleInterval<FloatType, RandomAccessibleInterval<FloatType>> extended =
						Views.extendValue(
								converted,
								new FloatType(background));
				final RealRandomAccessible<FloatType> interpolant =
						Views.interpolate(
								extended,
								interpolationMethod == Interpolation.NLINEAR ?
										new NLinearInterpolatorFactory<>() :
										new NearestNeighborInterpolatorFactory<>());
				if (slice.affine == null || slice.affineTransform().isIdentity() )
					slicesList.add(interpolant);
				else
					slicesList.add(RealViews.affineReal(interpolant, slice.affineTransform()));
			}

			return new ValuePair<>(slicesList, bounds);
		}
	}

	/**
	 * Open a stack of slices, each transformed by a constant camera
	 * transformation and a per slice alignment transformation.  Also
	 * returns the resulting 2D bounds of the transformed slice series.
	 *
	 * Note: the RealRandomAccessible is sitting at z=0, independent of the firstSliceIndex
	 *
	 * @param <T>
	 * @param slices
	 * @param camTransform invertible forward transform
	 * @param alignment invertible forward transforms
	 * @param transform
	 * @param firstSliceIndex
	 * @param lastSliceIndex
	 * @return
	 * @throws FormatException
	 * @throws IOException
	 */
	public static <T extends NumericType<T> & NativeType<T>> ValuePair<RealRandomAccessible<T>, RealInterval> openAlignedStack(
			final List<Slice> slices,
			final T background,
			final Interpolation interpolationMethod,
			final AffineTransform2D camTransform,
			final RandomAccessible<AffineTransform2D> alignment,
			final int firstSliceIndex,
			final int lastSliceIndex) throws FormatException, IOException
	{
		return openAlignedStack( slices, background, interpolationMethod, camTransform, alignment, firstSliceIndex, lastSliceIndex, true );
	}

	/**
	 * Open a stack of slices, each transformed by a constant camera
	 * transformation and a per slice alignment transformation.  Also
	 * returns the resulting 2D bounds of the transformed slice series.
	 *
	 * Note: the RealRandomAccessible is sitting at z=0, independent of the firstSliceIndex
	 *
	 * @param <T>
	 * @param slices
	 * @param camTransform invertible forward transform
	 * @param alignment invertible forward transforms
	 * @param transform
	 * @param firstSliceIndex
	 * @param lastSliceIndex
	 * @param maxbounds - if true the maximal bounding box encompassing everything will be computed,
	 * otherwise the minimal bounding box that contains where data is available for every xy location in every z slice
	 * @return
	 * @throws FormatException
	 * @throws IOException
	 */
	public static <T extends NumericType<T> & NativeType<T>> ValuePair<RealRandomAccessible<T>, RealInterval> openAlignedStack(
			final List<Slice> slices,
			final T background,
			final Interpolation interpolationMethod,
			final AffineTransform2D camTransform,
			final RandomAccessible<AffineTransform2D> alignment,
			final int firstSliceIndex,
			final int lastSliceIndex,
			final boolean maxBounds ) throws FormatException, IOException {

		/* get slices */
		final ValuePair<List<RealRandomAccessible<T>>, RealInterval> realSlices = openStack(
				slices,
				new OutOfBoundsConstantValueFactory<T, RandomAccessibleInterval<T>>(background),
				interpolationMethod == Interpolation.NLINEAR ?
						new NLinearInterpolatorFactory<>() :
						new NearestNeighborInterpolatorFactory<>(),
				firstSliceIndex,
				lastSliceIndex);

		/* transform slices */
		final RandomAccess<AffineTransform2D> alignmentAccess = alignment.randomAccess();
		final ArrayList<RealRandomAccessible<T>> transformedRealSlices = new ArrayList<>();
		RealInterval bounds = null;
		alignmentAccess.setPosition(firstSliceIndex, 0);
		for (final RealRandomAccessible<T> slice : realSlices.getA()) {
			final AffineTransform2D combinedTransform = camTransform.copy();
			combinedTransform.preConcatenate(alignmentAccess.get());
			final FinalRealInterval sliceBounds = combinedTransform.estimateBounds(realSlices.getB());
			if (bounds == null)
				bounds = sliceBounds;
			else
				bounds = maxBounds ? Intervals.union(bounds, sliceBounds) : Intervals.intersect(bounds, sliceBounds);
			transformedRealSlices.add(RealViews.affineReal(slice, combinedTransform));
			alignmentAccess.fwd(0);
		}

		final Interpolant<T, ArrayList<RealRandomAccessible<T>>> interpolatedStack = new Interpolant<>(
				transformedRealSlices,
				interpolationMethod == Interpolation.NLINEAR ?
						new LinearRealRandomAccessibleStackInterpolatorFactory<>() :
						new NearestNeighborRealRandomAccessibleStackInterpolatorFactory<>(),
				3);

		return new ValuePair<>(interpolatedStack, bounds);
	}

	public static RealInterval estimateStackBounds(
			final List<Slice> slices,
			final AffineTransform2D camTransform,
			final RandomAccessible<AffineTransform2D> alignment,
			final int firstSliceIndex,
			final int lastSliceIndex,
			final boolean maxBounds ) throws FormatException, IOException {

		/* get slices interval*/
		final RealInterval inputBounds = openStackSize( slices, firstSliceIndex );

		return estimateStackBounds( inputBounds, slices, camTransform, alignment, firstSliceIndex, lastSliceIndex, maxBounds );
	}

	public static RealInterval estimateStackBounds(
			final RealInterval inputBounds,
			final List<Slice> slices,
			final AffineTransform2D camTransform,
			final RandomAccessible<AffineTransform2D> alignment,
			final int firstSliceIndex,
			final int lastSliceIndex,
			final boolean maxBounds ) throws FormatException, IOException {

		/* transform bounds */
		final RandomAccess<AffineTransform2D> alignmentAccess = alignment.randomAccess();
		//final ArrayList<RealRandomAccessible<T>> transformedRealSlices = new ArrayList<>();
		RealInterval bounds = null;
		alignmentAccess.setPosition(firstSliceIndex, 0);
		for ( int i = firstSliceIndex; i <= lastSliceIndex; ++i ) {
			final AffineTransform2D combinedTransform = camTransform.copy();
			combinedTransform.preConcatenate(alignmentAccess.get());
			final FinalRealInterval sliceBounds = combinedTransform.estimateBounds(inputBounds);
			if (bounds == null)
				bounds = sliceBounds;
			else
				bounds = maxBounds ? Intervals.union(bounds, sliceBounds) : Intervals.intersect(bounds, sliceBounds);
			alignmentAccess.fwd(0);
		}

		return bounds;
	}

	public static <T extends NumericType<T> & NativeType<T>> ValuePair<RealRandomAccessible<T>, RealInterval> transform(
			final RealRandomAccessible<T> source,
			final RealInterval sourceBounds,
			final AffineGet transform) {

		final AffineTransform3D boundsTransform = new AffineTransform3D();
		boundsTransform.set(transform.getRowPackedCopy());

		final AffineTransform3D sourceTransform = new AffineTransform3D();
		sourceTransform.translate(0, 0, sourceBounds.realMin(2));
		sourceTransform.preConcatenate(boundsTransform);

		final AffineRealRandomAccessible<T, AffineGet> transformedStack =
				RealViews.affineReal(source, sourceTransform);

		return new ValuePair<>(transformedStack, boundsTransform.estimateBounds(sourceBounds));
	}

	public static <T extends NumericType<T> & NativeType<T>> Pair< RealRandomAccessible<T>, Interval > prepareCamSource(
			final List<Slice> slices,
			final T background,
			final Interpolation interpolationMethod,
			final AffineTransform2D camTransform,
			final AffineGet transform,
			final RandomAccessible<AffineTransform2D> alignment,
			final int firstSliceIndex,
			final int lastSliceIndex) throws FormatException, IOException {

			final ValuePair<RealRandomAccessible<T>, RealInterval> alignedStackBounds =
					openAlignedStack(
							slices,
							background,
							interpolationMethod,
							camTransform,
							alignment,
							firstSliceIndex,
							lastSliceIndex);

			final RealInterval realBounds2D = alignedStackBounds.getB();
			final RealInterval realBounds3D = Intervals.createMinMax(
					(long)Math.floor(realBounds2D.realMin(0)),
					(long)Math.floor(realBounds2D.realMin(1)),
					firstSliceIndex,
					(long)Math.ceil(realBounds2D.realMax(0)),
					(long)Math.ceil(realBounds2D.realMax(1)),
					lastSliceIndex);

			final ValuePair<RealRandomAccessible<T>, RealInterval> transformedStackBounds =
					transform(
							alignedStackBounds.getA(),
							realBounds3D,
							transform);


			final RealInterval realBounds = transformedStackBounds.getB();
			final Interval bounds = Intervals.smallestContainingInterval(realBounds);

			return new ValuePair<RealRandomAccessible<T>, Interval>( transformedStackBounds.getA(), bounds );
		}

	protected static <T extends NumericType<T> & NativeType<T>> BdvStackSource<T> showCamSource(
			final BdvStackSource<?> bdv,
			final String title,
			final List<Slice> slices,
			final T background,
			final Interpolation interpolationMethod,
			final AffineTransform2D camTransform,
			final AffineGet transform,
			final RandomAccessible<AffineTransform2D> alignment,
			final int firstSliceIndex,
			final int lastSliceIndex) throws FormatException, IOException {

		final BdvOptions options = bdv == null ? Bdv.options().screenScales(new double[] {1}) : Bdv.options().addTo(bdv);
		// options.numRenderingThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

		/*
		final ValuePair<RealRandomAccessible<T>, RealInterval> alignedStackBounds =
				openAlignedStack(
						slices,
						background,
						interpolationMethod,
						camTransform,
						alignment,
						firstSliceIndex,
						lastSliceIndex);

		final RealInterval realBounds2D = alignedStackBounds.getB();
		final RealInterval realBounds3D = Intervals.createMinMax(
				(long)Math.floor(realBounds2D.realMin(0)),
				(long)Math.floor(realBounds2D.realMin(1)),
				firstSliceIndex,
				(long)Math.ceil(realBounds2D.realMax(0)),
				(long)Math.ceil(realBounds2D.realMax(1)),
				lastSliceIndex);

		final ValuePair<RealRandomAccessible<T>, RealInterval> transformedStackBounds =
				transform(
						alignedStackBounds.getA(),
						realBounds3D,
						transform);


		final RealInterval realBounds = transformedStackBounds.getB();
		final Interval bounds = Intervals.smallestContainingInterval(realBounds);
		*/

		final Pair< RealRandomAccessible<T>, Interval > data = prepareCamSource(slices, background, interpolationMethod, camTransform, transform, alignment, firstSliceIndex, lastSliceIndex);

		final BdvStackSource<T> stackSource = BdvFunctions.show(
				data.getA(),
				data.getB(),
				title,
				options);
		stackSource.setDisplayRange(0, 2048);

		return stackSource;
	}

	private static <T extends NumericType<T> & NativeType<T>> BdvStackSource<?> run(
			final HashMap<String, HashMap<String, AffineTransform2D>> camTransforms,
			final AffineGet transform,
			final HashMap<String, HashMap<String, List<Slice>>> stacks,
			final HashMap<String, RandomAccessible<AffineTransform2D>> alignments,
			final int firstSliceIndex,
			final int lastSliceIndex ) throws FormatException, IOException {

		BdvStackSource<?> bdv = null;
		// final SharedQueue queue = new SharedQueue(Math.max(1,
		// Runtime.getRuntime().availableProcessors() / 2));

		for (final Entry<String, HashMap<String, List<Slice>>> channel : stacks.entrySet()) {

			for (final Entry<String, List<Slice>> cam : channel.getValue().entrySet()) {

				/* this is the inverse */
				final AffineTransform2D camTransform = camTransforms.get(channel.getKey()).get(cam.getKey());
				final String title = channel.getKey() + " " + cam.getKey();
				bdv = showCamSource(
						bdv,
						title,
						cam.getValue(),
						new UnsignedShortType(0),
						Interpolation.NLINEAR,
						camTransform.inverse(), // pass the forward transform
						transform,
						alignments.get(channel.getKey()),
						firstSliceIndex,
						lastSliceIndex);
			}
		}

		return bdv;
	}

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException {

		final N5FSReader n5 = new N5FSReader(
				n5Path,
				new GsonBuilder().registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		final HashMap<String, HashMap<String, AffineTransform2D>> camTransforms = n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, AffineTransform2D>>>() {}.getType());
		final ArrayList<String> ids = n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());

		if (!ids.contains(id))
		{
			for ( final String s : ids )
				System.out.println( s );
			System.out.println( id + " not present (available ones listed above).");
			return null;
		}

		final HashMap<String, HashMap<String, List<Slice>>> stacks = new HashMap<>();
		final HashMap<String, RandomAccessible<AffineTransform2D>> alignments = new HashMap<>();

		int localLastSliceIndex = lastSliceIndex;

		for (final Entry<String, HashMap<String, AffineTransform2D>> channel : camTransforms.entrySet()) {
			final HashMap<String, List<Slice>> channelStacks = new HashMap<>();
			stacks.put(channel.getKey(), channelStacks);

			/* stack alignment transforms */
			final ArrayList<AffineTransform2D> transforms = n5.getAttribute(
					id + "/" + channel.getKey(),
					"transforms",
					new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

			final RandomAccessible<AffineTransform2D> alignmentTransforms;
			if (transforms == null) {
				/*
				 * if there are no stack alignment transforms do a default
				 * shearing
				 */
				alignmentTransforms = new FunctionRandomAccessible<>(1,
						(location, affine) -> {
							affine.set(
									1, 0, shearX * location.getDoublePosition(0),
									0, 1, shearY * location.getDoublePosition(0));
						},
						AffineTransform2D::new);
			} else {
				/* if they do exist, use them */
				alignmentTransforms = Views.extendBorder(new ListImg<>(transforms, transforms.size()));
			}
			alignments.put(channel.getKey(), alignmentTransforms);

			/* add all camera stacks that exist */
			for (final String camKey : channel.getValue().keySet()) {
				final String groupName = id + "/" + channel.getKey() + "/" + camKey;
				if (n5.exists(groupName)) {
					final ArrayList<Slice> stack = n5.getAttribute(
							groupName,
							"slices",
							new TypeToken<ArrayList<Slice>>(){}.getType());
					channelStacks.put(
							camKey,
							stack);

					localLastSliceIndex = Math.min(localLastSliceIndex, stack.size() - 1);
				}
			}
		}

		final Gson gson = new GsonBuilder().registerTypeAdapter(
				AffineTransform2D.class,
				new AffineTransform2DAdapter()).create();

		System.out.println(gson.toJson(camTransforms));
		System.out.println(gson.toJson(ids));
		// System.out.println(new Gson().toJson(stacks));

//		final Scale3D stretchTransform = new Scale3D(0.2, 0.2, 0.85);
		final AffineTransform3D stretchTransform = new AffineTransform3D();

		if ( displayImageJStack )
		{
			new ImageJ();

			final ExecutorService service = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );

			for (final Entry<String, HashMap<String, List<Slice>>> channel : stacks.entrySet()) {
	
				for (final Entry<String, List<Slice>> cam : channel.getValue().entrySet()) {
	
					/* this is the inverse */
					final AffineTransform2D camTransform = camTransforms.get(channel.getKey()).get(cam.getKey());
					final String title = channel.getKey() + " " + cam.getKey();
					final Pair< RealRandomAccessible<UnsignedShortType>, Interval > data =
							prepareCamSource(cam.getValue(), new UnsignedShortType(0), Interpolation.NLINEAR, camTransform.inverse(), stretchTransform, alignments.get(channel.getKey()), firstSliceIndex, localLastSliceIndex);
	
					RandomAccessibleInterval< UnsignedShortType > ra = Views.zeroMin( Views.interval( Views.raster( data.getA() ), data.getB() ) );
					System.out.println( "copying..." );
					ShortImagePlus<UnsignedShortType> img = ImagePlusImgs.unsignedShorts( ra.dimensionsAsLongArray() );
					Util.copy(ra, img, service);
					img.getImagePlus().setDimensions(1, (int)img.dimension( 2 ), 1 );
					img.getImagePlus().setTitle(title);
					img.getImagePlus().show();
				}
			}
			service.shutdown();
		}
		else
		{
			BdvStackSource<?> bdv = run(camTransforms, stretchTransform, stacks, alignments, firstSliceIndex, localLastSliceIndex );
			
			if ( overlayDoG )
			{
				for (final Entry<String, HashMap<String, List<Slice>>> channel : stacks.entrySet()) {
					
					for (final Entry<String, List<Slice>> cam : channel.getValue().entrySet()) {

						Pair<ArrayList<InterestPoint>, N5Data> points =
								SparkPairwiseStitchSlabs.loadPoints( n5Path, id, channel.getKey(), cam.getKey(), null, 0 );

						System.out.println( "Loaded " + points.getA().size() + " interest points.");

						final AffineTransform3D t = new AffineTransform3D();
						t.preConcatenate( stretchTransform );
		
						bdv = BdvFunctions.show(
								SparkPaiwiseAlignChannelsGeo.renderPoints(
										points.getA(),
										false ),
								Intervals.createMinMax( 0, 0, 0, 1, 1, 1),
								"detections",
								new BdvOptions().addTo( bdv ).sourceTransform( t ) );

						bdv.setDisplayRange(0, 256);
						bdv.setColor( new ARGBType( ARGBType.rgba(255, 0, 0, 0)));
					}
				}
			}

		}

		return null;
	}
}
