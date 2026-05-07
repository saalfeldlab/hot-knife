/*
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
package org.janelia.saalfeldlab.hotknife.util;

import ij.process.FloatProcessor;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;

import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Util {

	private Util() {}

	public static <T extends Type<T>> void copy(
			final RandomAccessible<? extends T> source,
			final RandomAccessibleInterval<T> target) {

		Views.flatIterable(Views.interval(Views.pair(source, target), target)).forEach(
				pair -> pair.getB().set(pair.getA()));
	}

	public static <T extends Type<T>> void copy(
			final RandomAccessible<? extends T> source,
			final RandomAccessibleInterval<T> target,
			final ExecutorService service,
			final boolean shuffle ) {

		final long numPixels = Views.iterable( target ).size();
		final ArrayList<Pair<Long,Long>> portions = divideIntoPortions( numPixels );

		System.out.println( "Portions: " + portions.size() );

		// maximize the probability to fetch different blocks of the N%
		if ( shuffle )
			Collections.shuffle( portions );

		final ArrayList< Callable< Void > > tasks = new ArrayList<>();

		final IterableInterval< ? extends T > sourceIterable = Views.flatIterable( Views.interval( source, target ) );
		final IterableInterval< T > targetIterable = Views.flatIterable( target );

		for ( final Pair<Long,Long> portion : portions )
		{
			tasks.add(() -> {
                final Cursor< ? extends T > cursorSource = sourceIterable.cursor();
                final Cursor< T > cursorTarget = targetIterable.cursor();

                cursorSource.jumpFwd( portion.getA() );
                cursorTarget.jumpFwd( portion.getA() );

                for ( long l = 0; l < portion.getB(); ++l )
                    cursorTarget.next().set( cursorSource.next() );

                return null;
            });
		}

		try
		{
			// invokeAll() returns when all tasks are complete
			service.invokeAll( tasks );
		}
		catch ( final InterruptedException e )
		{
			IOFunctions.println( "Failed to copy: " + e );
			e.printStackTrace();
		}
	}

	public static FloatProcessor materialize(final RandomAccessibleInterval<FloatType> source) {
		final FloatProcessor target = new FloatProcessor((int) source.dimension(0), (int) source.dimension(1));
		Util.copy(
				Views.zeroMin(source),
				ArrayImgs.floats(
						(float[]) target.getPixels(),
						target.getWidth(),
						target.getHeight()));
		return target;
	}

	static public void scaleArray(
			final double[] array,
			final double scale) {

		Arrays.setAll(
				array,
				i -> array[i] * scale);
	}

	public static ArrayList<Pair<Long,Long>> divideIntoPortions( final long imageSize )
	{
		return divideIntoPortions(imageSize, 64L*64L*64L );
	}

	public static ArrayList<Pair<Long,Long>> divideIntoPortions( final long imageSize, final long defaultChunkLength )
	{
		int numPortions;

		if ( imageSize <= Threads.numThreads() )
			numPortions = (int)imageSize;
		else
			numPortions = Math.max( Threads.numThreads(), (int)( imageSize / defaultChunkLength ) );

		final ArrayList<Pair<Long,Long>> portions = new ArrayList<>();

		if ( imageSize == 0 )
			return portions;

		long threadChunkSize = imageSize / numPortions;

		while ( threadChunkSize == 0 )
		{
			--numPortions;
			threadChunkSize = imageSize / numPortions;
		}

		long threadChunkMod = imageSize % numPortions;

		for ( int portionID = 0; portionID < numPortions; ++portionID )
		{
			// move to the starting position of the current thread
			final long startPosition = portionID * threadChunkSize;

			// the last thread may has to run longer if the number of pixels cannot be divided by the number of threads
			final long loopSize;
			if ( portionID == numPortions - 1 )
				loopSize = threadChunkSize + threadChunkMod;
			else
				loopSize = threadChunkSize;
			
			portions.add( new ValuePair<>( startPosition, loopSize ) );
		}
		
		return portions;
	}

	/**
	 * Flatten a group name.
	 * Removes optional leading <code>separator</code> and replaces all others by <code>replacement</code>.
	 */
	public static String flattenGroupName(final String groupName, final String separator, final String replacement) {

		return groupName.replaceAll("^" + separator, "").replaceAll(separator, replacement);
	}

	public static String flattenGroupName(final String groupName) {

		return flattenGroupName(groupName, "/", ".");
	}

	public static String getAttributesJsonPath(final String groupName,
											   final String dataSetName) {
		return groupName + dataSetName + "/attributes.json";
	}

	public static <T> T readRequiredAttribute(final N5Reader n5Reader,
											  final String groupName,
											  final String key,
											  final Class<T> clazz) throws IOException {
		T value;
        value = n5Reader.getAttribute(groupName, key, clazz);
        if (value == null) {
			throw new IOException("required " + key + " attribute is missing from " +
								  getAttributesJsonPath(n5Reader.getURI().getPath(), groupName));
		}
		return value;
	}

    public static void checkDatasetExistence(final N5Reader n5Reader,
                                             final String datasetPath,
                                             final boolean shouldExist) throws IOException {
        final boolean exists = n5Reader.exists(datasetPath);
        if (shouldExist && ! exists) {
            throw new IOException("dataset " + datasetPath + " does not exist in " + n5Reader.getURI());
        } else if(! shouldExist && exists) {
            throw new IOException("dataset " + datasetPath + " already exists in " + n5Reader.getURI());
        }
    }

    public static LocalDateTime getEasternTime() {
        return java.time.LocalDateTime.now(EASTERN_TIME_ZONE);
    }

    public static String getEasternTimeString() {
        return getEasternTime()
                .truncatedTo(ChronoUnit.SECONDS)
                .toString()
                .replace("T", "_")
                .replace(":", "")
                .replace("-", "");
    }

    public static void logMessage(final String clazz,
                                  final String message) {
        System.out.println(getEasternTimeString() + " " + clazz + ": " + message);
    }

    public static final ZoneId EASTERN_TIME_ZONE = ZoneId.of("America/New_York");

    public static String convertAttributesToString(final DatasetAttributes attributes) {

        final int[] blockSize = attributes.getBlockSize();
        final String blockSizeString = blockSize == null ? "null" : Arrays.toString(blockSize);

        final Compression compression = attributes.getCompression();
        final String compressionString = compression == null ? "null" : compression.getClass().getSimpleName();

        final DataType dataType = attributes.getDataType();
        final String dataTypeString = dataType == null ? "null" : dataType.getClass().getSimpleName();

        final long[] dimensions = attributes.getDimensions();
        final String dimensionsString = dimensions == null ? "null" : Arrays.toString(dimensions);

        return "{ blockSize=" + blockSizeString + ", compression=" + compressionString +
               ", dataType=" + dataTypeString + ", dimensions=" + dimensionsString + " }";
    }
}
