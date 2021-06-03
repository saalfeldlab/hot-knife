package org.janelia.saalfeldlab.ispim.render;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;

import org.janelia.saalfeldlab.ispim.GlobalOptimize.Description;
import org.janelia.saalfeldlab.ispim.SparkPairwiseStitchSlabs;
import org.janelia.saalfeldlab.ispim.SparkPairwiseStitchSlabs.MetaData;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.ColorProcessor;
import loci.formats.FormatException;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.InterpolatedAffineModel3D;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import net.imglib2.RandomAccess;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;

public class Render3D {

	public static double s = 0.03;

	public static ArrayList<Line> drawParallel(
			double[] p0a, double[] p1a, double[] p2a, double[] p3a,
			double[] p0b, double[] p1b, double[] p2b, double[] p3b )
	{
		ArrayList<Line> line = new ArrayList<>();

		line.add(new Line(new Vertex(p0a[0], p0a[1], p0a[2]), new Vertex(p1a[0], p1a[1], p1a[2]), Color.BLACK));
		line.add(new Line(new Vertex(p1a[0], p1a[1], p1a[2]), new Vertex(p2a[0], p2a[1], p2a[2]), Color.BLACK));
		line.add(new Line(new Vertex(p2a[0], p2a[1], p2a[2]), new Vertex(p3a[0], p3a[1], p3a[2]), Color.BLACK));
		line.add(new Line(new Vertex(p3a[0], p3a[1], p3a[2]), new Vertex(p0a[0], p0a[1], p0a[2]), Color.BLACK));

		line.add(new Line(new Vertex(p0a[0], p0a[1], p0a[2]), new Vertex(p0b[0], p0b[1], p0b[2]), Color.BLACK));
		line.add(new Line(new Vertex(p1a[0], p1a[1], p1a[2]), new Vertex(p1b[0], p1b[1], p1b[2]), Color.BLACK));
		line.add(new Line(new Vertex(p2a[0], p2a[1], p2a[2]), new Vertex(p2b[0], p2b[1], p2b[2]), Color.LIGHT_GRAY));
		line.add(new Line(new Vertex(p3a[0], p3a[1], p3a[2]), new Vertex(p3b[0], p3b[1], p3b[2]), Color.BLACK));

		line.add(new Line(new Vertex(p0b[0], p0b[1], p0b[2]), new Vertex(p1b[0], p1b[1], p1b[2]), Color.BLACK));
		line.add(new Line(new Vertex(p1b[0], p1b[1], p1b[2]), new Vertex(p2b[0], p2b[1], p2b[2]), Color.LIGHT_GRAY));
		line.add(new Line(new Vertex(p2b[0], p2b[1], p2b[2]), new Vertex(p3b[0], p3b[1], p3b[2]), Color.LIGHT_GRAY));
		line.add(new Line(new Vertex(p3b[0], p3b[1], p3b[2]), new Vertex(p0b[0], p0b[1], p0b[2]), Color.BLACK));

		return line;
	}

	public static class MyTileConfig extends TileConfiguration
	{
		private static final long serialVersionUID = 7076852945147946374L;

		ErrorStatistic observer;
		JPanel renderPanel;
		HashMap< String, Parallelogram> pars;
		HashMap< String, Tile< ? > > idToTile;
		int i = 0;

		public MyTileConfig( final HashMap< Description, ? extends Tile< ? > > descToTile ) throws IOException, FormatException, HeadlessException, AWTException
		{
			pars = loadAlignmnts();
			renderPanel = setup( pars );
			idToTile = new HashMap<>();

			for ( final Description desc : descToTile.keySet() )
				idToTile.put( desc.id, descToTile.get( desc ) );
		}

		public void setObserver( final ErrorStatistic observer ) { this.observer = observer; }
		public void setModelString( String s ) { modelString = s; }
		public void setErrorString( String s ) { errorString = s; }
		public void setIterationString( String s ) { iterationString = s; }

		public void drawState()
		{
			System.out.println( "update rendering...." );

			if ( observer != null )
				setErrorString( "Error: " + String.format("%.2f", observer.mean) + " px" );

			setIterationString( "Iteration: " + i );

			i++;

			for ( final String id : idToTile.keySet() )
			{
				InterpolatedAffineModel3D<?, ?> model = ((InterpolatedAffineModel3D<?, ?>)idToTile.get( id ).getModel());
				pars.get( id ).t = TransformationTools.getAffineTransform( model.createAffineModel3D() );
			}

			renderPanel.repaint();
			SimpleMultiThreading.threadWait( 50 );

			try {
				BufferedImage awtImage = new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
				ImagePlus imp = new ImagePlus( "render", new ColorProcessor( awtImage ) );
				new FileSaver( imp ).saveAsPng( "globalOpt_"+i +".png" );

				SimpleMultiThreading.threadWait( 50 );
			} catch (HeadlessException | AWTException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		@Override
		protected void updateErrors()
		{
			super.updateErrors();
			drawState();
		}
	}

	public static class Parallelogram
	{
		double[] p0a, p1a, p2a, p3a, p0b, p1b, p2b, p3b;
		AffineTransform3D t;
	}

	public static HashMap<String, Parallelogram> loadAlignmnts() throws IOException, FormatException
	{
		HashMap<String,Parallelogram> line = new HashMap<>();

		final HashMap< String, MetaData > meta =
				SparkPairwiseStitchSlabs.readPositionMetaData( "/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5/m24o.edited.pos.json" );

		final String channel = "Ch488+561+647nm";
		final String cam = "cam1";

		final ArrayList<String> ids = new ArrayList<>( meta.keySet() );
		Collections.sort( ids );

		for ( final String id : ids )
		{
			System.out.println( id );

			Parallelogram p = new Parallelogram();

			N5Data n5data = SparkPaiwiseAlignChannelsGeo.openN5( "/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5", id );
	
			final RandomAccess<AffineTransform2D> alignmentAccess = n5data.alignments.get( channel ).randomAccess();

			alignmentAccess.setPosition(0, 0);
			AffineTransform2D combinedTransform = n5data.camTransforms.get( channel ).get( cam ).inverse().copy();
			combinedTransform.preConcatenate(alignmentAccess.get());

			p.p0a = new double[] { 0, 0, 0 };
			p.p1a = new double[] { 2047, 0, 0 };
			p.p2a = new double[] { 2047, 2047, 0 };
			p.p3a = new double[] { 0, 2047, 0 };

			combinedTransform.apply( p.p0a, p.p0a );
			combinedTransform.apply( p.p1a, p.p1a );
			combinedTransform.apply( p.p2a, p.p2a );
			combinedTransform.apply( p.p3a, p.p3a );

			alignmentAccess.setPosition(n5data.lastSliceIndex, 0);
			combinedTransform = n5data.camTransforms.get( channel ).get( cam ).inverse().copy();
			combinedTransform.preConcatenate(alignmentAccess.get());

			p.p0b = new double[] { 0, 0, n5data.lastSliceIndex };
			p.p1b = new double[] { 2047, 0, n5data.lastSliceIndex };
			p.p2b = new double[] { 2047, 2047, n5data.lastSliceIndex };
			p.p3b = new double[] { 0, 2047, n5data.lastSliceIndex };

			combinedTransform.apply( p.p0b, p.p0b );
			combinedTransform.apply( p.p1b, p.p1b );
			combinedTransform.apply( p.p2b, p.p2b );
			combinedTransform.apply( p.p3b, p.p3b );

			p.t = n5data.affine3D.get( channel ).copy();

			line.put( id, p );

			//if ( line.size() == 5)
			//	return line;
		}

		System.out.println( "done" );

		return line;
	}

	public static int maxP = Integer.MAX_VALUE;
	public static String modelString = null;
	public static String errorString = null;
	public static String iterationString = null;

	public static JPanel setup( HashMap<String, Parallelogram> pars ) throws HeadlessException, AWTException
	{
		JFrame frame = new JFrame();
		Container pane = frame.getContentPane();
		pane.setLayout(new BorderLayout());

		// slider to control horizontal rotation
		JSlider headingSlider = new JSlider(-180, 180, 0);
		pane.add(headingSlider, BorderLayout.SOUTH);

		// slider to control vertical rotation
		JSlider pitchSlider = new JSlider(SwingConstants.VERTICAL, -90, 90, 0);
		pane.add(pitchSlider, BorderLayout.EAST);

		// slider to control horizontal rotation
		JSlider moveX = new JSlider(-2000, 2000, 0);
		pane.add(moveX, BorderLayout.NORTH);

		JSlider moveY = new JSlider(SwingConstants.VERTICAL, -2000, 2000, 0);
		pane.add(moveY, BorderLayout.WEST);

		//moveX.setValue( 400 );
		//moveY.setValue( 400 );
		moveX.setValue( 1000 );
		moveY.setValue( 1000 );

		//headingSlider.setValue( 2 /*-8 */);
		//pitchSlider.setValue( -35 /*21*/ );
		headingSlider.setValue( -8);
		pitchSlider.setValue( 21 );

		// panel to display render results
		JPanel renderPanel = new JPanel() {
			public void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g;
				g2.setColor(Color.WHITE);
				g2.fillRect(0, 0, getWidth(), getHeight());

				double heading = Math.toRadians(headingSlider.getValue());
				Matrix3 headingTransform = new Matrix3(new double[] { Math.cos(heading), 0, -Math.sin(heading), 0, 1, 0,
						Math.sin(heading), 0, Math.cos(heading) });
				double pitch = Math.toRadians(pitchSlider.getValue());
				Matrix3 pitchTransform = new Matrix3(new double[] { 1, 0, 0, 0, Math.cos(pitch), Math.sin(pitch), 0,
						-Math.sin(pitch), Math.cos(pitch) });
				Matrix3 transform = headingTransform.multiply(pitchTransform);

				List<Line> line = new ArrayList<>();

				int i = 0;

				//System.out.println( moveX.getValue() + ", " + moveY.getValue() + "," + headingSlider.getValue() + ","+ pitchSlider.getValue() );

				for ( final Parallelogram p : pars.values() )
				{
					double[] p0a = new double[3];
					double[] p1a = new double[3];
					double[] p2a = new double[3];
					double[] p3a = new double[3];
					double[] p0b = new double[3];
					double[] p1b = new double[3];
					double[] p2b = new double[3];
					double[] p3b = new double[3];

					AffineTransform3D scale = new AffineTransform3D();
					scale.scale( 0.2 * s * 10, 0.2 * s * 10, 0.85 * s * 10 );

					AffineTransform3D translation = new AffineTransform3D();
					translation.translate( moveX.getValue(), moveY.getValue(), 0 );

					AffineTransform3D t = p.t.copy().preConcatenate( scale ).preConcatenate( translation );

					t.apply(p.p0a, p0a);
					t.apply(p.p1a, p1a);
					t.apply(p.p2a, p2a);
					t.apply(p.p3a, p3a);

					t.apply(p.p0b, p0b);
					t.apply(p.p1b, p1b);
					t.apply(p.p2b, p2b);
					t.apply(p.p3b, p3b);

					line.addAll( drawParallel(p0a, p1a, p2a, p3a, p0b, p1b, p2b, p3b) );
					if (++i == maxP )
						break;
				}

				for (Line t : line) {
					g2.setColor(t.color);
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
					/*g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY );
					g2.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
					g2.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
					g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE );*/
					g2.setStroke( new BasicStroke( 1.5f ));

					Vertex v1 = transform.transform(t.v1);
					Vertex v2 = transform.transform(t.v2);

					Path2D path = new Path2D.Double();
					path.moveTo(v1.x, v1.y);
					path.lineTo(v2.x, v2.y);
					path.closePath();
					
					g2.draw(path);
				}

				if ( modelString != null )
				{
					g2.setFont( new Font( "Times", Font.BOLD, 48 ) );
					g2.drawString( modelString, 200, 1900 );
				}

				if ( errorString != null )
				{
					g2.setFont( new Font( "Times", Font.PLAIN, 30 ) );
					g2.drawString( errorString, 200, 1940 );
				}

				if ( iterationString != null )
				{
					g2.setFont( new Font( "Times", Font.PLAIN, 30 ) );
					g2.drawString( iterationString, 200, 1970 );
				}
			}
		};
		pane.add(renderPanel, BorderLayout.CENTER);

		headingSlider.addChangeListener(e -> renderPanel.repaint());
		pitchSlider.addChangeListener(e -> renderPanel.repaint());
		moveX.addChangeListener(e -> renderPanel.repaint());
		moveY.addChangeListener(e -> renderPanel.repaint());

		frame.setSize(2400, 2400);
		frame.setVisible(true);

		return renderPanel;
	}

	public static void main(String[] args) throws IOException, FormatException, HeadlessException, AWTException {

		//List<Line> line = loadMetaData();
		HashMap<String, Parallelogram> pars = loadAlignmnts();

		JPanel renderPanel = setup( pars );

		SimpleMultiThreading.threadWait( 300000 );

		for ( maxP = 1; maxP < 55; ++maxP )
		{
			System.out.println( maxP );
			renderPanel.repaint();

			BufferedImage awtImage = new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));

			/*BufferedImage awtImage = new BufferedImage(renderPanel.getWidth(), renderPanel.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics g = awtImage.getGraphics();
			((Graphics2D)g).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON );
			((Graphics2D)g).setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY );
			renderPanel.printAll(g);*/

			ImagePlus imp = new ImagePlus( "render", new ColorProcessor( awtImage ) );
			new FileSaver( imp ).saveAsPng( "i_"+maxP +".png" );

			SimpleMultiThreading.threadWait( 250 );
		}

	}

	public static Color getShade(Color color, double shade) {
		double redLinear = Math.pow(color.getRed(), 2.4) * shade;
		double greenLinear = Math.pow(color.getGreen(), 2.4) * shade;
		double blueLinear = Math.pow(color.getBlue(), 2.4) * shade;

		int red = (int) Math.pow(redLinear, 1 / 2.4);
		int green = (int) Math.pow(greenLinear, 1 / 2.4);
		int blue = (int) Math.pow(blueLinear, 1 / 2.4);

		return new Color(red, green, blue);
	}

	public static List<Triangle> inflate(List<Triangle> tris) {
		List<Triangle> result = new ArrayList<>();
		for (Triangle t : tris) {
			Vertex m1 = new Vertex((t.v1.x + t.v2.x) / 2, (t.v1.y + t.v2.y) / 2, (t.v1.z + t.v2.z) / 2);
			Vertex m2 = new Vertex((t.v2.x + t.v3.x) / 2, (t.v2.y + t.v3.y) / 2, (t.v2.z + t.v3.z) / 2);
			Vertex m3 = new Vertex((t.v1.x + t.v3.x) / 2, (t.v1.y + t.v3.y) / 2, (t.v1.z + t.v3.z) / 2);
			result.add(new Triangle(t.v1, m1, m3, t.color));
			result.add(new Triangle(t.v2, m1, m2, t.color));
			result.add(new Triangle(t.v3, m2, m3, t.color));
			result.add(new Triangle(m1, m2, m3, t.color));
		}
		for (Triangle t : result) {
			for (Vertex v : new Vertex[] { t.v1, t.v2, t.v3 }) {
				double l = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z) / Math.sqrt(30000);
				v.x /= l;
				v.y /= l;
				v.z /= l;
			}
		}
		return result;
	}
}

class Vertex {
	double x;
	double y;
	double z;

	Vertex(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}
}

class Triangle {
	Vertex v1;
	Vertex v2;
	Vertex v3;
	Color color;

	Triangle(Vertex v1, Vertex v2, Vertex v3, Color color) {
		this.v1 = v1;
		this.v2 = v2;
		this.v3 = v3;
		this.color = color;
	}
}

class Line {
	Vertex v1;
	Vertex v2;
	Color color;

	Line(Vertex v1, Vertex v2, Color color) {
		this.v1 = v1;
		this.v2 = v2;
		this.color = color;
	}
}

class Matrix3 {
	double[] values;

	Matrix3(double[] values) {
		this.values = values;
	}

	Matrix3 multiply(Matrix3 other) {
		double[] result = new double[9];
		for (int row = 0; row < 3; row++) {
			for (int col = 0; col < 3; col++) {
				for (int i = 0; i < 3; i++) {
					result[row * 3 + col] += this.values[row * 3 + i] * other.values[i * 3 + col];
				}
			}
		}
		return new Matrix3(result);
	}

	Vertex transform(Vertex in) {
		return new Vertex(in.x * values[0] + in.y * values[3] + in.z * values[6],
				in.x * values[1] + in.y * values[4] + in.z * values[7],
				in.x * values[2] + in.y * values[5] + in.z * values[8]);
	}
}