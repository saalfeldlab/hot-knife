package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.n5.N5FSReader;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import bdv.viewer.Interpolation;
import ij.ImageJ;
import ij.ImagePlus;
import loci.formats.FormatException;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.list.ListImg;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Intervals;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class AlignChannels implements Callable<Void>, Serializable {

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--id", required = true, description = "Stack key, e.g. Pos012")
	private String id = null;

	@Option(names = "--channelA", required = true, description = "Channel A key, e.g. Ch488+561+647nm")
	private String channelA = null;

	@Option(names = "--channelB", required = true, description = "Channel B key, e.g. Ch405nm")
	private String channelB = null;

	@Option(names = "--camA", required = true, description = "CamA key, e.g. cam1")
	private String camA = null;

	@Option(names = "--camB", required = true, description = "CamB key, e.g. cam1")
	private String camB = null;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new AlignChannels()).execute(args);
	}

	@SuppressWarnings("serial")
	@Override
	public Void call() throws IOException, InterruptedException, ExecutionException, FormatException
	{
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
			return null;

		final HashMap<String, HashMap<String, List<Slice>>> stacks = new HashMap<>();
		final HashMap<String, RandomAccessible<AffineTransform2D>> alignments = new HashMap<>();

		int localLastSliceIndex = Integer.MAX_VALUE;

		for (final Entry<String, HashMap<String, AffineTransform2D>> channel : camTransforms.entrySet()) {
			final HashMap<String, List<Slice>> channelStacks = new HashMap<>();
			stacks.put(channel.getKey(), channelStacks);

			/* stack alignment transforms */
			final ArrayList<AffineTransform2D> transforms = n5.getAttribute(
					id + "/" + channel.getKey(),
					"transforms",
					new TypeToken<ArrayList<AffineTransform2D>>(){}.getType());

			final RandomAccessible<AffineTransform2D> alignmentTransforms= Views.extendBorder(new ListImg<>(transforms, transforms.size()));

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

		System.out.println( "localLastSliceIndex: " + localLastSliceIndex );

		final Gson gson = new GsonBuilder().registerTypeAdapter(
				AffineTransform2D.class,
				new AffineTransform2DAdapter()).create();

		System.out.println(gson.toJson(camTransforms));
		System.out.println(gson.toJson(ids));
		// System.out.println(new Gson().toJson(stacks));

		final Scale3D stretchTransform = new Scale3D(0.2, 0.2, 0.85);

		// testing
		final int firstSliceIndex = 510;
		final int lastSliceIndex = 520;

		final HashMap<String, List<Slice>> chA = stacks.get( channelA );
		final HashMap<String, List<Slice>> chB = stacks.get( channelB );
		final List< Slice > slicesA = chA.get( camA );
		final List< Slice > slicesB = chA.get( camB );

		/* this is the inverse */
		final AffineTransform2D camAtransform = camTransforms.get( channelA ).get( camA );
		final AffineTransform2D camBtransform = camTransforms.get( channelB ).get( camB );

		final RandomAccessibleInterval< UnsignedShortType > imgA =
				openRandomAccessibleInterval(
						slicesA,
						new UnsignedShortType(0),
						Interpolation.NLINEAR,
						camAtransform,
						alignments.get( channelA ),
						firstSliceIndex,
						lastSliceIndex);


		final RandomAccessibleInterval< UnsignedShortType > imgB =
				openRandomAccessibleInterval(
						slicesB,
						new UnsignedShortType(0),
						Interpolation.NLINEAR,
						camAtransform,
						alignments.get( channelB ),
						firstSliceIndex,
						lastSliceIndex);

		new ImageJ();

		final ImagePlus impA = ImageJFunctions.wrap(imgA, "imgA" ).duplicate();
		impA.setDimensions( 1, impA.getStackSize(), 1 );
		impA.resetDisplayRange();
		impA.show();

		final ImagePlus impB = ImageJFunctions.wrap(imgB, "imgB" ).duplicate();
		impB.setDimensions( 1, impB.getStackSize(), 1 );
		impB.resetDisplayRange();
		impB.show();

		//ImageJFunctions.show( imgA );
		SimpleMultiThreading.threadHaltUnClean();
		return null;
	}

	public static < T extends NumericType<T> & NativeType<T> > RandomAccessibleInterval< T > openRandomAccessibleInterval(
			final List< Slice > slices,
			final T background,
			final Interpolation interpolation,
			final AffineTransform2D camTransform,
			final RandomAccessible<AffineTransform2D> alignments,
			final int firstSliceIndex,
			final int lastSliceIndex ) throws FormatException, IOException
	{
		ValuePair<RealRandomAccessible<T>, RealInterval> alignedStackBounds =
				ViewISPIMStack.openAlignedStack(
						slices,
						background,
						interpolation,
						camTransform,
						alignments,
						firstSliceIndex,
						lastSliceIndex);

		// the stack is sitting at z=0, independent of the firstslice index
		if ( firstSliceIndex != 0 )
			alignedStackBounds = new ValuePair<>(
					RealViews.transform(
							alignedStackBounds.getA(),
							new Translation3D(0, 0, firstSliceIndex ) ),
					alignedStackBounds.getB() );

		final RealInterval realBounds2D = alignedStackBounds.getB();
		final RealInterval realBounds3D = Intervals.createMinMax(
				(long)Math.floor(realBounds2D.realMin(0)),
				(long)Math.floor(realBounds2D.realMin(1)),
				firstSliceIndex,
				(long)Math.ceil(realBounds2D.realMax(0)),
				(long)Math.ceil(realBounds2D.realMax(1)),
				lastSliceIndex);

		/*final ValuePair<RealRandomAccessible<UnsignedShortType>, RealInterval> transformedStackBounds =
				ViewISPIMStack.transform(
						alignedStackBounds.getA(),
						realBounds3D,
						stretchTransform);

		final RealInterval realBounds = transformedStackBounds.getB();
		final Interval bounds = Intervals.smallestContainingInterval(realBounds);*/

		return Views.interval( Views.raster( alignedStackBounds.getA() ), Intervals.smallestContainingInterval(realBounds3D) );
	}
}
