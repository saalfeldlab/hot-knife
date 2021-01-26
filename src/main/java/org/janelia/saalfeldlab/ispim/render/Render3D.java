package org.janelia.saalfeldlab.ispim.render;

import javax.swing.*;

import org.janelia.saalfeldlab.ispim.SparkPairwiseStitchSlabs;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo;
import org.janelia.saalfeldlab.ispim.ViewISPIMStack;

import loci.formats.FormatException;

import org.janelia.saalfeldlab.ispim.SparkPairwiseStitchSlabs.MetaData;
import org.janelia.saalfeldlab.ispim.SparkPaiwiseAlignChannelsGeo.N5Data;

import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.ColorProcessor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RealInterval;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.realtransform.AffineTransform3D;

import java.awt.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;

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

		line.add(new Line(new Vertex(p0a[0], p0a[1], p0a[2]), new Vertex(p0b[0], p0b[1], p0b[2]), Color.GRAY));
		line.add(new Line(new Vertex(p1a[0], p1a[1], p1a[2]), new Vertex(p1b[0], p1b[1], p1b[2]), Color.GRAY));
		line.add(new Line(new Vertex(p2a[0], p2a[1], p2a[2]), new Vertex(p2b[0], p2b[1], p2b[2]), Color.GRAY));
		line.add(new Line(new Vertex(p3a[0], p3a[1], p3a[2]), new Vertex(p3b[0], p3b[1], p3b[2]), Color.GRAY));

		line.add(new Line(new Vertex(p0b[0], p0b[1], p0b[2]), new Vertex(p1b[0], p1b[1], p1b[2]), Color.BLACK));
		line.add(new Line(new Vertex(p1b[0], p1b[1], p1b[2]), new Vertex(p2b[0], p2b[1], p2b[2]), Color.BLACK));
		line.add(new Line(new Vertex(p2b[0], p2b[1], p2b[2]), new Vertex(p3b[0], p3b[1], p3b[2]), Color.BLACK));
		line.add(new Line(new Vertex(p3b[0], p3b[1], p3b[2]), new Vertex(p0b[0], p0b[1], p0b[2]), Color.BLACK));

		return line;
	}

	public static class Parallelogram
	{
		double[] p0a, p1a, p2a, p3a, p0b, p1b, p2b, p3b;
		AffineTransform3D t;
	}

	public static List<Parallelogram> loadAlignmnts() throws IOException, FormatException
	{
		List<Parallelogram> line = new ArrayList<>();

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

			line.add( p );

			//if ( line.size() == 10)
			//return line;
		}

		System.out.println( "done" );

		return line;
	}

	public static List<Line> loadMetaData() throws IOException
	{
		List<Line> line = new ArrayList<>();

		final HashMap< String, MetaData > meta =
				SparkPairwiseStitchSlabs.readPositionMetaData( "/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5/m24o.edited.pos.json" );

		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;

		for ( final String id : meta.keySet() )
		{
			final MetaData m = meta.get( id );

			double tx = m.position[ 0 ] - m.position[ 2 ] * 13; // it is z along the sheared volume
			double ty = m.position[ 1 ];
			double tz = m.position[ 2 ];

			minX = Math.min( minX, tx );
			minY = Math.min( minY, ty );
			minZ = Math.min( minZ, tz );
		}

		minX -= 1500;
		minY -= 2000;
		//minZ -= 1000;
		System.out.println( minX + "," + minY + ", " + minZ );

		for ( final String id : meta.keySet() )
		{
			System.out.println( id );

			final MetaData m = meta.get( id );

			N5Data n5 = SparkPaiwiseAlignChannelsGeo.openN5( "/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5", id );

			double tx = m.position[ 0 ] - m.position[ 2 ] * 13; // it is z along the sheared volume
			double ty = m.position[ 1 ];
			double tz = m.position[ 2 ];
			
			tx -= minX; ty -= minY; tz -= minZ;
			//System.out.println( tx + "," + ty + ", " + tz);
			double scaleZ = 0.85/0.2;

			double[] p0a = new double[] { (tx+0)*s, (ty+0)*s, tz*s };
			double[] p1a = new double[] { (tx+2048)*s, (ty+0)*s, tz*s };
			double[] p2a = new double[] { (tx+2048)*s, (ty+2048)*s, tz*s };
			double[] p3a = new double[] { (tx+0)*s, (ty+2048)*s, tz*s };

			double[] p0b = new double[] { (n5.lastSliceIndex*13+tx+0)*s, (ty+0)*s, (tz+n5.lastSliceIndex*scaleZ)*s };
			double[] p1b = new double[] { (n5.lastSliceIndex*13+tx+2048)*s, (ty+0)*s, (tz+n5.lastSliceIndex*scaleZ)*s };
			double[] p2b = new double[] { (n5.lastSliceIndex*13+tx+2048)*s, (ty+2048)*s, (tz+n5.lastSliceIndex*scaleZ)*s };
			double[] p3b = new double[] { (n5.lastSliceIndex*13+tx+0)*s, (ty+2048)*s, (tz+n5.lastSliceIndex*scaleZ)*s };

			line.addAll( drawParallel(p0a, p1a, p2a, p3a, p0b, p1b, p2b, p3b) );
		}

		System.out.println( "done" );

		return line;
	}

	public static int maxP = Integer.MAX_VALUE;

	public static void main(String[] args) throws IOException, FormatException, HeadlessException, AWTException {
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
		JSlider moveX = new JSlider(-1000, 1000, 0);
		pane.add(moveX, BorderLayout.NORTH);

		JSlider moveY = new JSlider(SwingConstants.VERTICAL, -1000, 1000, 0);
		pane.add(moveY, BorderLayout.WEST);

		moveX.setValue( 400 );
		moveY.setValue( 400 );
		headingSlider.setValue( -8 /*-34*/ );
		pitchSlider.setValue( 21 /*25*/ );

		//List<Line> line = loadMetaData();
		List<Parallelogram> pars = loadAlignmnts();

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

				for ( final Parallelogram p : pars )
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
					scale.scale( 0.2 * s * 5, 0.2 * s * 5, 0.85 * s * 5 );

					AffineTransform3D translation = new AffineTransform3D();
					translation.translate( moveX.getValue(), moveY.getValue(), 0 );

					System.out.println( moveX.getValue() + ", " + moveY.getValue() + "," + headingSlider.getValue() + ","+ pitchSlider.getValue() );

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
					Vertex v1 = transform.transform(t.v1);
					Vertex v2 = transform.transform(t.v2);

					Path2D path = new Path2D.Double();
					path.moveTo(v1.x, v1.y);
					path.lineTo(v2.x, v2.y);
					path.closePath();
					g2.draw(path);
				}
			}
		};
		pane.add(renderPanel, BorderLayout.CENTER);

		headingSlider.addChangeListener(e -> renderPanel.repaint());
		pitchSlider.addChangeListener(e -> renderPanel.repaint());
		moveX.addChangeListener(e -> renderPanel.repaint());
		moveY.addChangeListener(e -> renderPanel.repaint());

		frame.setSize(1400, 800);
		frame.setVisible(true);

		for ( maxP = 1; maxP < 55; ++maxP )
		{
			System.out.println( maxP );
			renderPanel.repaint();

			BufferedImage awtImage = new Robot().createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));

			//ImageIO.write(image, "png", new File("/screenshot.png"));
			//BufferedImage awtImage = new BufferedImage(renderPanel.getWidth(), renderPanel.getHeight(), BufferedImage.TYPE_INT_ARGB);
			//Graphics g = awtImage.getGraphics();
			//renderPanel.printAll(g);

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