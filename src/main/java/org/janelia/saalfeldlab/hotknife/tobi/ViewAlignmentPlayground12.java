package org.janelia.saalfeldlab.hotknife.tobi;

import bdv.ui.BdvDefaultCards;
import bdv.ui.CardPanel;
import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvStackSource;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;
import java.awt.Insets;
import java.io.IOException;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.miginfocom.swing.MigLayout;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;

import static bdv.BigDataViewer.createConverterToARGB;

// transform baking in a CellLoader
public class ViewAlignmentPlayground12 {

	public static void main(String[] args) throws IOException {
		System.setProperty("apple.laf.useScreenMenuBar", "true");

		final String n5Path = "/Users/pietzsch/Desktop/data/janelia/Z0720_07m_BR";
		final String passGroup = "/surface_align/pass02";

		final N5Reader n5 = new N5FSReader(n5Path);

		final String transformGroup = passGroup + "/" + "flat.Sec33.bot.face";
		final String faceGroup = "/flat/Sec33/bot/face";

		final SurfacePyramid<?, ?> pyramid = new N5SurfacePyramid<>(n5, faceGroup);
		final PositionField pf0 = new PositionField(n5, transformGroup);
		final int minLevel = pf0.getLevel();
		final int maxLevel = pyramid.getNumMipmapLevels() - 1;
		final int blockWidth = 256;

		final PositionFieldPyramid pfp0 = PositionFieldPyramid.createFullPyramid(pf0, blockWidth, minLevel, maxLevel);
		final SurfacePyramid<?, ?> sp0 = new RenderedSurfacePyramid<>(pyramid, pfp0, blockWidth);

		// set up transform to append to pfp0
		final double maxSlope=0.8;
		final double minSigma=100.0;
		final boolean active=true;
		final double sx0=3634.3391666666666;
		final double sy0=14456.360833333334;
		final double sx1=11067.172499999999;
		final double sy1=14679.345833333335;
		final GaussTransform transform = new GaussTransform(maxSlope, minSigma);
		transform.setLine(sx0, sy0, sx1, sy1);
		transform.setActive(active);

		final PositionFieldPyramid pfp1 = PositionFieldPyramid.createFullPyramid(pf0, blockWidth, minLevel, maxLevel);
		final SurfacePyramid<?, ?> sp1 = new TransformedSurfacePyramid<>(sp0, transform);


//		final BdvStackSource<?> source0 = BdvFunctions.show(sp0.getSourceAndConverter(), Bdv.options().is2D());
//		source0.setDisplayRange(0, 255);
//		source0.setDisplayRangeBounds(0, 255);
//		source0.setColor(new ARGBType(0xff0000));

		final DelegatingSourceAndConverter wrapper = new DelegatingSourceAndConverter(pyramid.getType(), pyramid.getVolatileType(), "wrapped");
		wrapper.setDelegate(sp0.getSourceAndConverter());
		final BdvStackSource<?> source0 = BdvFunctions.show(wrapper.get(), Bdv.options().is2D());
		source0.setDisplayRange(0, 255);
		source0.setDisplayRangeBounds(0, 255);
		source0.setColor(new ARGBType(0xff0000));

		final Bdv bdv = source0;
		final ViewerPanel viewer = bdv.getBdvHandle().getViewerPanel();

//		final BdvStackSource<?> source1 = BdvFunctions.show(sp1.getSourceAndConverter(), Bdv.options().addTo(bdv));
//		source1.setDisplayRange(0, 255);
//		source1.setDisplayRangeBounds(0, 255);
//		source1.setColor(new ARGBType(0x00ff00));







		JPanel panel = new JPanel(new MigLayout( "gap 0, ins 5 5 5 0, fill", "[right][grow]", "center" ));
		final ButtonPanel buttons = new ButtonPanel("Cancel", "Apply");
		panel.add(buttons, "sx2, gaptop 10px, wrap, bottom");

		buttons.onButton(0, () -> SwingUtilities.invokeLater(() -> {
			System.out.println("buttons 0");
			wrapper.setDelegate(sp0.getSourceAndConverter());
			viewer.requestRepaint();
		}));

		buttons.onButton(1, () -> SwingUtilities.invokeLater(() -> {
			System.out.println("buttons 1");
			wrapper.setDelegate(sp1.getSourceAndConverter());
			viewer.requestRepaint();
		}));

		final CardPanel cards = bdv.getBdvHandle().getCardPanel();
		cards.setCardExpanded(BdvDefaultCards.DEFAULT_SOURCEGROUPS_CARD, false);
		cards.addCard("Face Transforms", panel, true, new Insets(0, 0, 0, 0));
	}


	static class DelegatingSourceAndConverter<T extends NumericType<T>, V extends Volatile<T> & NumericType<V>> {

		private final SourceAndConverter<T> soc;
		private final DelegatingSource<V> vsource;
		private final DelegatingSource<T> source;

		public DelegatingSourceAndConverter(T type, V volatileType, String name) {
			vsource = new DelegatingSource<>(name);
			source = new DelegatingSource<>(name);
			final SourceAndConverter<V> vsoc = new SourceAndConverter<>(vsource, createConverterToARGB(volatileType));
			soc = new SourceAndConverter<>(source, createConverterToARGB(type), vsoc);
		}

		public void setDelegate(SourceAndConverter<T> soc) {
			source.setDelegate(soc.getSpimSource());
			vsource.setDelegate((Source<V>) soc.asVolatile().getSpimSource());
		}

		public void setName(String name) {
			source.setName(name);
			vsource.setName(name);
		}

		public SourceAndConverter<T> get() {
			return soc;
		}
	}

	static class DelegatingSource<T> implements Source<T> {

		private Source<T> delegate;

		private String name;

		DelegatingSource(final String name) {
			this.name = name;
		}

		public void setDelegate(Source<T> source) {
			delegate = source;
		}

		public void setName(String name) {
			this.name = name;
		}

		@Override
		public boolean isPresent(final int t) {
			return delegate.isPresent(t);
		}

		@Override
		public RandomAccessibleInterval<T> getSource(final int t, final int level) {
			return delegate.getSource(t, level);
		}

		@Override
		public boolean doBoundingBoxCulling() {
			return delegate.doBoundingBoxCulling();
		}

		@Override
		public RealRandomAccessible<T> getInterpolatedSource(final int t, final int level, final Interpolation method) {
			return delegate.getInterpolatedSource(t, level, method);
		}

		@Override
		public void getSourceTransform(final int t, final int level, final AffineTransform3D transform) {
			delegate.getSourceTransform(t, level, transform);
		}

		@Override
		public T getType() {
			return delegate.getType();
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public VoxelDimensions getVoxelDimensions() {
			return delegate.getVoxelDimensions();
		}

		@Override
		public int getNumMipmapLevels() {
			return delegate.getNumMipmapLevels();
		}
	}
}
