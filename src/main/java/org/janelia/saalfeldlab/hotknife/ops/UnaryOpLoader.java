package org.janelia.saalfeldlab.hotknife.ops;

import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Supplier;

import net.imagej.ops.Op;
import net.imagej.ops.OpService;
import net.imagej.ops.special.computer.Computers;
import net.imagej.ops.special.computer.UnaryComputerOp;
import net.imglib2.RandomAccessible;
import net.imglib2.cache.CacheLoader;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.basictypeaccess.array.ArrayDataAccess;
import net.imglib2.img.basictypeaccess.volatiles.VolatileAccess;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.img.cell.LazyCellImg;
import net.imglib2.type.NativeType;
import net.imglib2.util.Fraction;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

public class UnaryOpLoader<T, S extends NativeType<S>, A extends VolatileAccess & ArrayDataAccess<A>, R extends RandomAccessible<T>> implements CacheLoader<Long, Cell<A>> {

	private final R source;

	private final Supplier<S> targetTypeSupplier;

	private final Function<Integer, A> accessSupplier;

	private final UnaryComputerOp<RandomAccessible<T>,IntervalView<S>> op;

	private final CellGrid grid;

	/**
	 *
	 * @param grid
	 * @param source
	 * @param targetTypeSupplier e.g. UnsignedByteType::new
	 * @param accessSupplier e.g. a -> new VolatileByteArray(a, true)
	 * @param opService
	 * @param opClass
	 * @param args
	 */
	public UnaryOpLoader(
			final CellGrid grid,
			final R source,
			final Supplier<S> targetTypeSupplier,
			final Function<Integer, A> accessSupplier,
			final OpService opService,
			final Class<? extends Op> opClass,
			final Object[] args) {

		this.source = source;
		this.grid = grid;
		this.targetTypeSupplier = targetTypeSupplier;
		this.accessSupplier = accessSupplier;

		final int[] outputDimensions = new int[source.numDimensions()];
		Arrays.fill(outputDimensions, 1);

		final Img<S> output = new ArrayImgFactory<S>().create(outputDimensions, targetTypeSupplier.get());

		op = Computers.unary(
				opService,
				opClass,
				Views.interval(output, output),
				source,
				args);
	}

	@Override
	public Cell<A> get(final Long key) throws Exception {

		final long index = key;
		final int n = source.numDimensions();
		final long[] cellMin = new long[n];
		final int[] cellDims = new int[n];
		grid.getCellDimensions(index, cellMin, cellDims);

		final S targetType = targetTypeSupplier.get();
		final Fraction entitiesPerPixel = targetType.getEntitiesPerPixel();

		final A access = accessSupplier.apply((int)Math.ceil(Intervals.numElements(cellDims) * entitiesPerPixel.getRatio()));
		final ArrayImg<S, A> img = new ArrayImg<>(access, Util.int2long(cellDims), entitiesPerPixel);
		LazyCellImg.linkType(targetType, img);
		final IntervalView<S> output = Views.translate(img, cellMin);

		op.compute(source, output);

		return new Cell<>( cellDims, cellMin, access);
	}
}