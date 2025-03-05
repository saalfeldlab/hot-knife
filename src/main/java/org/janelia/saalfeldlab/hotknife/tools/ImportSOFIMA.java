package org.janelia.saalfeldlab.hotknife.tools;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.apache.hadoop.fs.Path;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class ImportSOFIMA implements Callable<Void>{

	private static int[] blockSize = new int[] {32, 32};

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/flyem/data/tmp/Z0115-22.n5")
	private String n5Path = null;

	@Option(names = {"-i", "--n5GroupIn"}, required = true, description = "N5 group to load, e.g. /pass01")
	private String groupIn = "/";

	@Option(names = {"-o", "--n5GroupOut"}, required = true, description = "N5 group to save, e.g. /pass01-sofima")
	private String groupOut;

	@Option(names = {"-s", "--sofimaField"}, required = true, description = "The SOFIMA transformation field, e.g. /nrs/flyem/data/sofima/3.invmap.zarr")
	private String sofimaField;

	@Option(names = "--z", required = true, description = "surface slice index to apply it to")
	private int z;

	@Override
	public final Void call()// throws IOException, InterruptedException, ExecutionException
	{
		System.out.println( n5Path );
		System.out.println( sofimaField );

		final N5Reader n5 = new N5Factory().openReader( StorageFormat.N5, n5Path );
		final N5Reader zarr = new N5Factory().openReader( StorageFormat.ZARR, sofimaField );

		final String[] datasetNames = n5.getAttribute(groupIn, "datasets", String[].class);
		final String[] transformDatasetNames = n5.getAttribute(groupIn, "transforms", String[].class);
		final double[] boundsMin = n5.getAttribute(groupIn, "boundsMin", double[].class);
		final double[] boundsMax = n5.getAttribute(groupIn, "boundsMax", double[].class);


		System.out.println( Arrays.toString( boundsMin ) + " >> " + Arrays.toString( boundsMax ));

		System.out.println( "dataset: " + datasetNames[ z ] );
		System.out.println( "transformDatasetName: " + transformDatasetNames[ z ] + ", loading " + groupIn + Path.SEPARATOR + transformDatasetNames[ z ]);

		final RandomAccessibleInterval< FloatType > pass = N5Utils.open( n5, groupIn + Path.SEPARATOR + transformDatasetNames[ z ] );
		final RandomAccessibleInterval< DoubleType > sofima = N5Utils.open( zarr, "/" );

		System.out.println( pass + ": " + Util.printInterval( pass ) );
		System.out.println( sofima + ": " + Util.printInterval( sofima ) );

		n5.close();
		zarr.close();

		return null;
	}

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {

		CommandLine.call(new ImportSOFIMA(), args);
	}

}
