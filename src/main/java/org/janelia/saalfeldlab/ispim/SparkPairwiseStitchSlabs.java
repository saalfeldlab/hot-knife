package org.janelia.saalfeldlab.ispim;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;

import loci.formats.FormatException;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public class SparkPairwiseStitchSlabs implements Callable<Void>, Serializable {

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--positionFile", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6-backup.n5/m24o.edited.pos.json")
	private String positionFile = null;

	@Option(names = "--idA", required = true, description = "Stack key A, e.g. Pos012")
	private String idA = null;

	@Option(names = "--idB", required = true, description = "Stack key B, e.g. Pos013")
	private String idB = null;

	@Option(names = "--channelA", required = true, description = "Channel A key, e.g. Ch488+561+647nm")
	private String channelA = null;

	@Option(names = "--channelB", required = true, description = "Channel B key, e.g. Ch405nm")
	private String channelB = null;

	@Option(names = "--camA", required = true, description = "CamA key, e.g. cam1")
	private String camA = null;

	@Option(names = "--camB", required = true, description = "CamB key, e.g. cam1")
	private String camB = null;

	@Option(names = {"-b", "--blocksize"}, required = false, description = "blocksize in z for point extraction (default: 20)")
	private int blocksize = 50;

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		new CommandLine(new SparkPairwiseStitchSlabs()).execute(args);
	}

	public static void align(
			final String n5Path,
			final String idA,
			final String idB,
			final String channelA,
			final String channelB,
			final String camA,
			final String camB,
			final int[] blockSize,
			final boolean doICP ) throws IOException, FormatException, NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		
	}

	public static HashMap< String, MetaData > readPositionMetaData( final String positionFile ) throws FileNotFoundException
	{
		final GsonBuilder gsonBuilder = new GsonBuilder();
		final Gson gson = gsonBuilder.create();

		final JsonReader reader = new JsonReader( new FileReader( positionFile ) );

		List< MetaData > metaData = Arrays.asList( gson.fromJson( reader, MetaData[].class ) );

		HashMap< String, MetaData > metaDataMap = new HashMap<>();

		for ( final MetaData m : metaData )
		{
			final int index = m.file.indexOf( "pos0" );
			String pos = "P" + m.file.substring( index + 1, index + 6 );
			metaDataMap.put( pos, m );
		}

		return metaDataMap;
	}

	public static class MetaData
	{
		String type = "", file = "";
		int[] position, size;
		double[] pixelResolution;
		int index;
	}

	@Override
	public Void call() throws IOException
	{
		System.out.println( idA + " <> " + idB );

		final HashMap< String, MetaData > meta = readPositionMetaData( positionFile );

		return null;
	}

}
