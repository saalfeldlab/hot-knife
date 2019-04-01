package org.janelia.saalfeldlab.hotknife.ops;

import java.util.function.Consumer;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.type.NativeType;

public class ConsumerCellLoader<T extends NativeType<T>> implements CellLoader<T> {

	private final Consumer<RandomAccessibleInterval<T>> op;

	public ConsumerCellLoader(final Consumer<RandomAccessibleInterval<T>> op) {

		this.op = op;
	}

	@Override
	public void load(final SingleCellArrayImg<T, ?> cell) {

		op.accept(cell);
	}
}