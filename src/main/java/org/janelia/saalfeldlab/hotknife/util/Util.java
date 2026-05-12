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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

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
import net.preibisch.mvrecon.Threads;

/**
 *
 *
 * @author Stephan Saalfeld &lt;saalfelds@janelia.hhmi.org&gt;
 */
public class Util {

	private Util() {}

	private static final int COPY_MAX_ATTEMPTS = 5;
	private static final long COPY_RETRY_BASE_MS = 1_000L;
	private static final long COPY_RETRY_MAX_MS = 60_000L;

	private static long backoffMs(final int attempt) {
		final long capped = Math.min(COPY_RETRY_BASE_MS << attempt, COPY_RETRY_MAX_MS);
		return capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
	}

	public static final <T extends Type<T>> void copy(
			final RandomAccessible<? extends T> source,
			final RandomAccessibleInterval<T> target) {

		Views.flatIterable(Views.interval(Views.pair(source, target), target)).forEach(
				pair -> pair.getB().set(pair.getA()));
	}

	public static final <T extends Type<T>> void copy(
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

		final ArrayList< Callable< Void > > tasks = new ArrayList< Callable< Void > >();

		final IterableInterval< ? extends T > sourceIterable = Views.flatIterable( Views.interval( source, target ) );
		final IterableInterval< T > targetIterable = Views.flatIterable( target );

		for ( final Pair<Long,Long> portion : portions )
		{
			tasks.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					Exception lastFailure = null;
					for ( int attempt = 0; attempt < COPY_MAX_ATTEMPTS; ++attempt )
					{
						try
						{
							final Cursor< ? extends T > cursorSource = sourceIterable.cursor();
							final Cursor< T > cursorTarget = targetIterable.cursor();

							cursorSource.jumpFwd( portion.getA() );
							cursorTarget.jumpFwd( portion.getA() );

							for ( long l = 0; l < portion.getB(); ++l )
								cursorTarget.next().set( cursorSource.next() );

							return null;
						}
						catch ( final Exception e )
						{
							lastFailure = e;
							if ( attempt < COPY_MAX_ATTEMPTS - 1 )
							{
								final long delayMs = backoffMs( attempt );
								System.err.println( "Util.copy portion start=" + portion.getA()
										+ " attempt " + ( attempt + 1 ) + "/" + COPY_MAX_ATTEMPTS
										+ " failed: " + e + " — retrying in " + delayMs + " ms" );
								try
								{
									Thread.sleep( delayMs );
								}
								catch ( final InterruptedException ie )
								{
									Thread.currentThread().interrupt();
									throw ie;
								}
							}
						}
					}
					throw lastFailure;
				}
			});
		}

		try
		{
			final List< Future< Void > > futures = service.invokeAll( tasks );
			for ( final Future< Void > f : futures )
				f.get();
		}
		catch ( final InterruptedException e )
		{
			Thread.currentThread().interrupt();
			throw new RuntimeException( "Util.copy interrupted", e );
		}
		catch ( final ExecutionException e )
		{
			throw new RuntimeException( "Util.copy task failed after retries", e.getCause() );
		}
	}

	public static final FloatProcessor materialize(final RandomAccessibleInterval<FloatType> source) {
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

	public static final ArrayList<Pair<Long,Long>> divideIntoPortions( final long imageSize )
	{
		return divideIntoPortions(imageSize, 64l*64l*64l );
	}

	public static final ArrayList<Pair<Long,Long>> divideIntoPortions( final long imageSize, final long defaultChunkLength )
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
	 *
	 * Removes optional leading <code>separator</code> and replaces all others by <code>replacement</code>.
	 *
	 * @param groupName
	 * @param separator
	 * @param replacement
	 * @return
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
}
