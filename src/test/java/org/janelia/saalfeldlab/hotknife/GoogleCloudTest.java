package org.janelia.saalfeldlab.hotknife;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;

public class GoogleCloudTest {

	public static void main(final String... args) throws IOException, InterruptedException, ExecutionException {
		
		final String n5PathInput = "gs://janelia-spark-test/FlyMSEM/wafer_60/slabs";
		final String n5DatasetInput = "/w60_s296_r00_d00_align_avgshd_ic_small_block";
//		final String factors = "2,2,1";

		final N5Reader n5Input = new N5Factory().openReader( StorageFormat.N5, n5PathInput );//new N5FSReader(options.n5PathInput);


		final String fullScaleInputDataset = n5DatasetInput + "/s0";

		if (! n5Input.exists(fullScaleInputDataset)) {
			throw new IllegalArgumentException("Full scale input dataset does not exist: " + n5PathInput + n5DatasetInput);
		}

		final int[] blockSize = n5Input.getAttribute(fullScaleInputDataset, "blockSize", int[].class);
		final long[] dimensions = n5Input.getAttribute(fullScaleInputDataset, "dimensions", long[].class);

		if (blockSize == null || dimensions == null) {
			throw new IllegalArgumentException("Block size or dimensions not found in dataset: " + n5PathInput + fullScaleInputDataset);
		}

//		final int[] gridBlockSize = new int[] { blockSize[0] * 8, blockSize[1] * 8, blockSize[2] };
//		final List<long[][]> grid = Grid.create(dimensions, gridBlockSize, blockSize);

		final N5Writer n5Output = new N5Factory().openWriter( StorageFormat.N5, n5PathInput ); //new N5FSWriter(options.n5PathInput);
		final String outputDataset = n5DatasetInput + "_norm-layer";
		final String fullScaleOutputDataset = outputDataset + "/s0";

		if (n5Output.exists(fullScaleOutputDataset)) {
			final String fullPath = n5PathInput + fullScaleOutputDataset;
			throw new IllegalArgumentException("Normalized data set exists: " + fullPath);
		}

		// TODO: fix com.google.api.client.http.HttpResponseException: 401 Unauthorized
		n5Output.createDataset(fullScaleOutputDataset, dimensions, blockSize, DataType.UINT8, new GzipCompression());

		n5Output.close();
		n5Input.close();
	}

}
