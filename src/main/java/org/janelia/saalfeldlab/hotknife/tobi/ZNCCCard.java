package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileViews;
import bdv.viewer.Source;
import java.util.Arrays;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.blk.copy.ArrayImgBlocks;
import net.imglib2.blk.view.ViewBlocks;
import net.imglib2.blk.view.ViewProps;
import net.imglib2.blk.zncc.ZNCCFloat;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.CellLoader;
import net.imglib2.cache.img.ReadOnlyCachedCellImgFactory;
import net.imglib2.cache.img.ReadOnlyCachedCellImgOptions;
import net.imglib2.cache.img.SingleCellArrayImg;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import net.miginfocom.swing.MigLayout;

import static net.imglib2.blk.copy.Extension.MIRROR_SINGLE;

public class ZNCCCard {

	private final JPanel panel;
	private final TransformedSurfaceStack<?, ?> stack1;
	private final TransformedSurfaceStack<?, ?> stack2;
	private final Bdv bdv;

	public ZNCCCard(final TransformedSurfaceStack<?, ?> stack1, final TransformedSurfaceStack<?, ?> stack2, final Bdv bdv) {
		this.stack1 = stack1;
		this.stack2 = stack2;
		this.bdv = bdv;
		panel = new JPanel(new MigLayout("gap 0, ins 5 5 5 0, fill", "[right][grow]", "center"));

		JButton updateZNCC = new JButton("ZNCC");
		panel.add(updateZNCC, "growx, wrap");
		updateZNCC.addActionListener(e -> updateZNCC());
	}

	private void updateZNCC() {
		final int level = 0;

		final Source<?> spimSource1 = stack1.getSourceAndConverter().getSpimSource();
		final RandomAccessibleInterval<?> source1 = spimSource1.getSource(0, level);
		System.out.println("source1 dims = " + Arrays.toString(source1.dimensionsAsLongArray()));
		final AffineTransform3D transform = new AffineTransform3D();
		spimSource1.getSourceTransform(0, level, transform);

		final Source<?> spimSource2 = stack2.getSourceAndConverter().getSpimSource();
		final RandomAccessibleInterval<?> source2 = spimSource2.getSource(0, level);
		System.out.println("source2 dims = " + Arrays.toString(source2.dimensionsAsLongArray()));

		final RandomAccessibleInterval<?> imgIunpadded = Views.hyperSlice(source1, 2, 0);
		final ThreadLocal<ViewBlocks<FloatType>> blocksImgI = ThreadLocal.withInitial( () -> new ViewBlocks<>(new ViewProps(
				Views.extendMirrorSingle(imgIunpadded)),
				new FloatType())
		);
		final RandomAccessibleInterval<?> imgJunpadded = Views.hyperSlice(source2, 2, 0);
		final ThreadLocal<ViewBlocks<FloatType>> blocksImgJ = ThreadLocal.withInitial( () -> new ViewBlocks<>(new ViewProps(
				Views.extendMirrorSingle(imgJunpadded)),
				new FloatType())
		);

		final int[] windowSize = { 11, 11 };
		final ThreadLocal<ZNCCFloat> tlzncc = ThreadLocal.withInitial( () -> new ZNCCFloat( windowSize, true ) );
		final CellLoader< FloatType > loader = new CellLoader< FloatType >()
		{
			@Override
			public void load( final SingleCellArrayImg< FloatType, ? > cell ) throws Exception
			{
				final int[] srcPos = Intervals.minAsIntArray( cell );
				final float[] dest = ( float[] ) cell.getStorageArray();

				final ZNCCFloat zncc = tlzncc.get();
				zncc.setTargetSize( Intervals.dimensionsAsIntArray( cell ) );

				final int[] sourceOffset = zncc.getSourceOffset();
				for ( int d = 0; d < srcPos.length; d++ )
					srcPos[ d ] += sourceOffset[ d ];
				final int[] size = zncc.getSourceSize();
				final float[][] src = zncc.getSourceBuffers();
				blocksImgI.get().copy( srcPos, src[ 0 ], size );
				blocksImgJ.get().copy( srcPos, src[ 1 ], size );
				zncc.compute( src, dest );
			}
		};

		final CachedCellImg< FloatType, ? > zncc = new ReadOnlyCachedCellImgFactory().create(
				Intervals.dimensionsAsLongArray( imgIunpadded ),
				new FloatType(),
				loader,
				ReadOnlyCachedCellImgOptions.options().cellDimensions( 64 ) );

		final BdvSource source3 = BdvFunctions.show(
				VolatileViews.wrapAsVolatile( zncc, new SharedQueue( 12 ) ), //Runtime.getRuntime().availableProcessors() ) ),
				"zncc",
				Bdv.options().addTo( bdv ).sourceTransform(transform) );
		source3.setColor( new ARGBType( 0x00ff00 ) );
		source3.setDisplayRange( 0, 1 );
	}

	public JPanel getPanel() {
		return panel;
	}
}
