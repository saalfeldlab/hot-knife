package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.hotknife.util.Show;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.util.RandomAccessibleIntervalMipmapSource;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import picocli.CommandLine.Option;

public class Cost implements Callable<Void>
{
	final FinalVoxelDimensions voxelDimensions = new FinalVoxelDimensions("px", new double[]{1, 1, 1});

	private boolean useVolatile = true;

	@Option(names = {"--i"}, required = false, description = "N5 file with min face, e.g. --i /nrs/flyem/render/n5/Z1217_19m/Sec04/stacks")
	private String rawN5 = "/nrs/flyem/tmp/VNC.n5";

	@Option(names = {"--d"}, required = false, description = "N5 dataset name, e.g. --d /v1_1_affine_filtered_1_26365___20191217_153959")
	private String datasetName = "/zcorr/Sec22___20200106_083252";

	public static final void main(final String... args) throws IOException, InterruptedException, ExecutionException
	{
		//CommandLine.call(new Cost(), args);
		new Cost().call();
	}

	@Override
	public final Void call() throws IOException, InterruptedException, ExecutionException
	{
		final N5FSReader n5 = new N5FSReader(rawN5);

		final int numScales = n5.list(datasetName).length;

		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<UnsignedByteType>[] mipmaps = new RandomAccessibleInterval[numScales];

		final double[][] scales = new double[numScales][];

		for (int s = 0; s < numScales; ++s)
		{
			final int scale = 1 << s;
			scales[s] = new double[]{scale, scale, scale};

			mipmaps[s] = N5Utils.openVolatile( n5, datasetName + "/s" + s);
		}

		final int numProc = Runtime.getRuntime().availableProcessors();
		final SharedQueue queue = new SharedQueue(Math.min(8, Math.max(1, numProc / 2)));

		BdvStackSource<?> bdv = null;

		final RandomAccessibleIntervalMipmapSource<?> mipmapSource =
				new RandomAccessibleIntervalMipmapSource<>(
						mipmaps,
						new UnsignedByteType(),
						scales,
						voxelDimensions,
						datasetName);

		final Source<?> volatileMipmapSource;

		if (useVolatile)
			volatileMipmapSource = mipmapSource.asVolatile(queue);
		else
			volatileMipmapSource = mipmapSource;

		bdv = Show.mipmapSource(volatileMipmapSource, bdv, BdvOptions.options().screenScales(new double[] {0.5}).numRenderingThreads(10));

		return null;
	}
}
