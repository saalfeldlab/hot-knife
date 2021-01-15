package org.janelia.saalfeldlab.ispim;

import java.io.IOException;
import java.io.Serializable;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import loci.formats.FormatException;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import picocli.CommandLine.Option;

public class SparkStitchSlabsPairwise implements Callable<Void>, Serializable {

	@Option(names = "--n5Path", required = true, description = "N5 path, e.g. /nrs/saalfeld/from_mdas/mar24_bis25_s5_r6.n5")
	private String n5Path = null;

	@Option(names = "--id", required = true, description = "Stack key, e.g. Pos012")
	private String id = null;

	@Option(names = "--channelA", required = true, description = "Channel A key, e.g. Ch488+561+647nm")
	private String channelA = null;

	@Option(names = "--channelB", required = true, description = "Channel B key, e.g. Ch405nm")
	private String channelB = null;

	@Option(names = "--camA", required = true, description = "CamA key, e.g. cam1")
	private String camA = null;

	@Option(names = "--camB", required = true, description = "CamB key, e.g. cam1")
	private String camB = null;

	@Option(names = {"-b", "--blocksize"}, required = false, description = "blocksize in z for point extraction (default: 20)")
	private int blocksize = 20;

	@Override
	public Void call() throws IOException
	{
		System.out.println( id );

		return null;
	}

}
