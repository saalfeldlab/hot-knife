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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;

import ij.process.FloatProcessor;

import org.janelia.saalfeldlab.n5.N5FSReader;

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

	public static final <T extends Type<T>> void copy(
			final RandomAccessible<? extends T> source,
			final RandomAccessibleInterval<T> target) {

		Views.flatIterable(Views.interval(Views.pair(source, target), target)).forEach(
				pair -> pair.getB().set(pair.getA()));
	}

	public static final <T extends Type<T>> void copy(
			final RandomAccessible<? extends T> source,
			final RandomAccessibleInterval<T> target,
			final ExecutorService service ) {

		final long numPixels = Views.iterable( target ).size();
		final ArrayList<Pair<Long,Long>> portions = divideIntoPortions( numPixels );

		// maximize the probability to fetch different blocks of the N%
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
					final Cursor< ? extends T > cursorSource = sourceIterable.cursor();
					final Cursor< T > cursorTarget = targetIterable.cursor();

					cursorSource.jumpFwd( portion.getA() );
					cursorTarget.jumpFwd( portion.getA() );

					for ( long l = 0; l < portion.getB(); ++l )
						cursorTarget.next().set( cursorSource.next() );

					return null;
				}
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
			return;
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

	public static <T> T readRequiredAttribute(final N5FSReader n5Reader,
											  final String groupName,
											  final String key,
											  final Class<T> clazz) throws IOException {
		T value;
		try {
			value = n5Reader.getAttribute(groupName, key, clazz);
		} catch (IOException e) {
			throw new IOException("failed to read from " + getAttributesJsonPath(n5Reader.getBasePath(), groupName),
								  e);
		}
		if (value == null) {
			throw new IOException("required " + key + " attribute is missing from " +
								  getAttributesJsonPath(n5Reader.getBasePath(), groupName));
		}
		return value;
	}
}
