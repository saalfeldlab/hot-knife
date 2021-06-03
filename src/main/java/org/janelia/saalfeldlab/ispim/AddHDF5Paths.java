package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;

import com.google.common.reflect.TypeToken;
import com.google.gson.GsonBuilder;

import net.imglib2.RandomAccessible;
import net.imglib2.img.list.ListImg;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.realtransform.AffineTransform2D;
import net.imglib2.view.Views;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class AddHDF5Paths implements Callable<Void> {

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		new CommandLine(new AddHDF5Paths()).execute(args);
	}
	@Override
	public Void call() throws Exception {

		final N5FSReader n5 = new N5FSReader(
				n5Path,
				new GsonBuilder().registerTypeAdapter(
						AffineTransform2D.class,
						new AffineTransform2DAdapter()));

		// the cam transforms in the base json encode which cams exist for each channel
		final HashMap<String, HashMap<String, AffineTransform2D>> camTransforms = n5.getAttribute(
				"/",
				"camTransforms",
				new TypeToken<HashMap<String, HashMap<String, AffineTransform2D>>>() {}.getType());

		// the list of stacks in the base json define the datasets that exist (e.g. Pos001)
		final ArrayList<String> ids = n5.getAttribute(
				"/",
				"stacks",
				new TypeToken<ArrayList<String>>() {}.getType());

		final HashMap<String, HashMap<String, List<Slice>>> stacks = new HashMap<>();

		for (final Entry<String, HashMap<String, AffineTransform2D>> channel : camTransforms.entrySet()) {
			final HashMap<String, List<Slice>> channelStacks = new HashMap<>();
			stacks.put(channel.getKey(), channelStacks);

			/* add all camera stacks that exist */
			for (final String camKey : channel.getValue().keySet()) {
				final String groupName = ids.get( 0 ) + "/" + channel.getKey() + "/" + camKey;
				if (n5.exists(groupName)) {
					final ArrayList<Slice> stack = n5.getAttribute(
							groupName,
							"slices",
							new TypeToken<ArrayList<Slice>>(){}.getType());
					channelStacks.put(
							camKey,
							stack);
				}
			}
		}

		for ( final String id : ids )
		{
			System.out.println( "ID: " + id);
			addHDF5Path(n5Path, id, stacks);
		}

		// TODO Auto-generated method stub
		return null;
	}

	public static void addHDF5Path( final String n5Path, final String id, final HashMap<String, ? extends HashMap<String, ?>> stacks ) throws IOException
	{
		N5FSWriter n5 = new N5FSWriter( n5Path );

		for ( final String channel : stacks.keySet() )
		{
			System.out.println( channel );
			
			for ( final String cam : stacks.get( channel ).keySet() )
			{
				System.out.print( " " + cam + ": " );

				final String groupName = id + "/" + channel + "/" + cam;
				String hdfdataset = null;

				if ( channel.equals( "Ch405nm") && cam.equals( "cam1") )
					hdfdataset = "t00000/s00/0/cells";
				else if ( channel.equals( "Ch488+561+647nm") && cam.equals( "cam1") )
					hdfdataset = "t00000/s01/0/cells";
				else if ( channel.equals( "Ch488+561+647nm") && cam.equals( "cam2") )
					hdfdataset = "t00000/s02/0/cells";
				else if ( channel.equals( "Ch488+561+647nm") && cam.equals( "cam3") )
					hdfdataset = "t00000/s03/0/cells";
				else if ( channel.equals( "Ch488+561+647nm") && cam.equals( "cam4") )
					hdfdataset = "t00000/s04/0/cells";
				else if ( channel.equals( "Ch515+594nm") && cam.equals( "cam1") )
					hdfdataset = "t00000/s05/0/cells";
				else if ( channel.equals( "Ch515+594nm") && cam.equals( "cam2") )
					hdfdataset = "t00000/s06/0/cells";
				else if ( channel.equals( "Ch515+594nm") && cam.equals( "cam3") )
					hdfdataset = "t00000/s07/0/cells";
				else if ( channel.equals( "Ch515+594nm") && cam.equals( "cam4") )
					hdfdataset = "t00000/s08/0/cells";

				System.out.println( hdfdataset );

				if (n5.exists(groupName))
				{
					n5.setAttribute( groupName, "hdf5file", "/nrs/saalfeld/from_mdas/h5write/" + id + ".gz.h5");
					n5.setAttribute( groupName, "hdf5dataset", hdfdataset );
				}
			}
		}
	}

}
