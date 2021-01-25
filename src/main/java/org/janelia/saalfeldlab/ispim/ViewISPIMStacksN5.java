package org.janelia.saalfeldlab.ispim;

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import javax.swing.ActionMap;
import javax.swing.InputMap;

import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.ispim.bdv.BDVFlyThrough;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.scijava.ui.behaviour.KeyStrokeAdder;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.io.InputTriggerDescription;
import org.scijava.ui.behaviour.util.AbstractNamedAction;

import bdv.util.Bdv;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Util;

public class ViewISPIMStacksN5
{
	public static void setupRecordMovie( final BdvStackSource<?> bdvSource )
	{
		final ActionMap ksActionMap = new ActionMap();
		final InputMap ksInputMap = new InputMap();

		// default input trigger config, disables "control button1" drag in bdv
		// (collides with default of "move annotation")
		final InputTriggerConfig config = new InputTriggerConfig(
				Arrays.asList(
						new InputTriggerDescription[]{
								new InputTriggerDescription(
										new String[]{"not mapped"}, "drag rotate slow", "bdv")}));

		final KeyStrokeAdder ksKeyStrokeAdder = config.keyStrokeAdder(ksInputMap, "persistence");

		new AbstractNamedAction( "Screenshot" )
		{
			private static final long serialVersionUID = 3640052275162419689L;

			@Override
			public void actionPerformed(ActionEvent e)
			{
				new Thread( ()-> BDVFlyThrough.renderScreenshot( bdvSource.getBdvHandle().getViewerPanel() ) ).start();
			}

			public void register() {
				put(ksActionMap);
				ksKeyStrokeAdder.put(name(), "ctrl C" );
			}
		}.register();

		new AbstractNamedAction( "Record movie" )
		{
			private static final long serialVersionUID = 3640052275162419689L;

			@Override
			public void actionPerformed(ActionEvent e)
			{
				new Thread( ()-> BDVFlyThrough.record( bdvSource.getBdvHandle().getViewerPanel() ) ).start();
			}

			public void register() {
				put(ksActionMap);
				ksKeyStrokeAdder.put(name(), "ctrl R" );
			}
		}.register();

		new AbstractNamedAction( "Jump to last Viewer Transform" )
		{
			private static final long serialVersionUID = 3620052275162419689L;

			@Override
			public void actionPerformed(ActionEvent e)
			{
				BDVFlyThrough.jumpToLastViewerTransform( bdvSource.getBdvHandle().getViewerPanel() );
			}

			public void register() {
				put(ksActionMap);
				ksKeyStrokeAdder.put(name(), "ctrl J" );
			}
		}.register();

		new AbstractNamedAction( "Load Viewer Transforms" )
		{
			private static final long serialVersionUID = 3620052275162419689L;

			@Override
			public void actionPerformed(ActionEvent e)
			{
				try {
					BDVFlyThrough.loadViewerTransforms( new File( "viewertransforms.json" ) );
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			}

			public void register() {
				put(ksActionMap);
				ksKeyStrokeAdder.put(name(), "ctrl L" );
			}
		}.register();

		new AbstractNamedAction( "Save Viewer Transforms" )
		{
			private static final long serialVersionUID = 3620052275162419689L;

			@Override
			public void actionPerformed(ActionEvent e)
			{
				try {
					BDVFlyThrough.saveViewerTransforms( new File( "viewertransforms.json" ) );
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
			}

			public void register() {
				put(ksActionMap);
				ksKeyStrokeAdder.put(name(), "ctrl S" );
			}
		}.register();

		new AbstractNamedAction( "Delete Last Viewer Transform" )
		{
			private static final long serialVersionUID = 3620052275162419689L;

			@Override
			public void actionPerformed(ActionEvent e)
			{
				BDVFlyThrough.deleteLastViewerTransform();
			}

			public void register() {
				put(ksActionMap);
				ksKeyStrokeAdder.put(name(), "ctrl D" );
			}
		}.register();

		new AbstractNamedAction( "Add Current Viewer Transform" )
		{
			private static final long serialVersionUID = 3620052275162419689L;

			@Override
			public void actionPerformed(ActionEvent e)
			{
				BDVFlyThrough.addCurrentViewerTransform( bdvSource.getBdvHandle().getViewerPanel() );
			}

			public void register() {
				put(ksActionMap);
				ksKeyStrokeAdder.put(name(), "ctrl A" );
			}
		}.register();

		new AbstractNamedAction( "Clear All Viewer Transforms" )
		{
			private static final long serialVersionUID = 3620052275162419689L;

			@Override
			public void actionPerformed(ActionEvent e)
			{
				BDVFlyThrough.clearAllViewerTransform();
			}

			public void register() {
				put(ksActionMap);
				ksKeyStrokeAdder.put(name(), "ctrl X" );
			}
		}.register();

		bdvSource.getBdvHandle().getKeybindings().addActionMap("persistence", ksActionMap);
		bdvSource.getBdvHandle().getKeybindings().addInputMap("persistence", ksInputMap);
	}

	public static BdvStackSource<?> run(
			final String n5Path,
			final String n5Group,
			BdvStackSource<?> bdv,
			final VoxelDimensions voxelDimensions,
			final AffineTransform3D transformIn,
			final boolean useVolatile) throws IOException {

		final N5Reader n5 = new N5FSReader(n5Path);
		final String group = n5Group;

		AffineTransform3D transform = transformIn.copy();
		if ( n5.listAttributes( group + "/s0" ).containsKey( "min" ) )
		{
			double[] min = n5.getAttribute(group + "/s0", "min", double[].class );
			System.out.println( Util.printCoordinates( min ));
	
			AffineTransform3D minTransform = new AffineTransform3D();
			minTransform.translate( min );
			transform = transform.concatenate( minTransform );
		}

		final SharedQueue queue = new SharedQueue(Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));

		final int numScales = n5.list(group).length;

		System.out.println(numScales);

		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = (RandomAccessibleInterval<UnsignedByteType>[])new RandomAccessibleInterval[numScales];
		final double[][] scales = new double[numScales][];

		for (int s = 0; s < numScales; ++s) {

			final int scale = 1 << s;

			final RandomAccessibleInterval<UnsignedByteType> source = N5Utils.openVolatile(n5, group + "/s" + s);

			mipmaps[s] = source;
			scales[s] = new double[]{scale, scale, scale};
		}

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
				new RandomAccessibleIntervalMipmapSource<>(
						mipmaps,
						new UnsignedByteType(),
						scales,
						voxelDimensions,
						transform,
						group);

		final BdvOptions bdvOptions = Bdv.options()./*screenScales(new double[] {1, 0.5}).*/numRenderingThreads(Math.max(3, Runtime.getRuntime().availableProcessors() / 5)).addTo( bdv );
		//final BdvOptions bdvOptions = Bdv.options().numRenderingThreads(Math.max(3, Runtime.getRuntime().availableProcessors() / 5));

		final Source<?> volatileMipmapSource;
		if (useVolatile)
			volatileMipmapSource = mipmapSource.asVolatile(queue);
		else
			volatileMipmapSource = mipmapSource;

		bdv = Show.mipmapSource(volatileMipmapSource, bdv, bdvOptions);

		//bdv.getBdvHandle().getViewerPanel().getTopLevelAncestor().setSize(1280 - 32, 720 - 48 - 16);

		return bdv;
	}

	public static void main( String[] args ) throws IOException
	{
		final String n5Path = "/nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5";

		BdvStackSource< ? > bdv = null;
		final AffineTransform3D t = new AffineTransform3D();
		t.scale(0.2, 0.2, 0.85);

		final boolean useVolatile = true;

		bdv = run(n5Path, "maxfusion4_Ch488+561+647nm_cam1__reSlice"/*"maxfusion2_Ch488+561+647nm_cam1"*/, bdv, null, t, useVolatile );
		bdv.setDisplayRange( 100, 800 );
		bdv.setColor( new ARGBType( ARGBType.rgba(0, 255, 0, 0)));

		bdv = run(n5Path, "maxfusion2_Ch515+594nm_cam1", bdv, null, t, useVolatile );
		bdv.setDisplayRange( 100, 300 );
		bdv.setColor( new ARGBType( ARGBType.rgba(255, 0, 255, 0)));

		bdv = run(n5Path, "maxfusion3_Ch405nm_cam1", bdv, null, t, useVolatile );
		bdv.setDisplayRange( 100, 1000 );
		bdv.setColor( new ARGBType( ARGBType.rgba(0, 0, 255, 0)));

		setupRecordMovie( bdv );
	}
}
