package org.janelia.saalfeldlab.hotknife;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.janelia.saalfeldlab.hotknife.util.Grid;
import org.junit.Assert;
import org.junit.Test;

import static org.janelia.saalfeldlab.hotknife.SparkExportAlignedSlabSeries.printBlock;


public class SparkExportAlignedSlabSeriesTest {

	@Test
	public void testIsBlockIncluded() {

		final Object[][] testData = {
				// dataset, botOffset, topOffset
				{"/flat/Sec40/raw", 2343L, 20L}, {"/flat/Sec39/raw", 2695L, 20L}, {"/flat/Sec38/raw", 2727L, 20L},
				{"/flat/Sec37/raw", 2776L, 20L}, {"/flat/Sec36/raw", 2752L, 20L}, {"/flat/Sec35/raw", 2794L, 20L},
				{"/flat/Sec34/raw", 2724L, 20L}, {"/flat/Sec33/raw", 2827L, 20L}, {"/flat/Sec32/raw", 2595L, 20L},
				{"/flat/Sec31/raw", 2678L, 20L}, {"/flat/Sec30/raw", 2724L, 20L}, {"/flat/Sec29/raw", 2688L, 20L},
				{"/flat/Sec28/raw", 2748L, 20L}, {"/flat/Sec27/raw", 2721L, 20L}, {"/flat/Sec26/raw", 2678L, 20L},
				{"/flat/Sec25/raw", 2631L, 20L}, {"/flat/Sec24/raw", 2682L, 20L}, {"/flat/Sec23/raw", 2702L, 20L},
				{"/flat/Sec22/raw", 2735L, 20L}, {"/flat/Sec21/raw", 2693L, 20L}, {"/flat/Sec20/raw", 2769L, 20L},
				{"/flat/Sec19/raw", 2693L, 20L}, {"/flat/Sec18/raw", 2705L, 20L}, {"/flat/Sec17/raw", 2729L, 20L},
				{"/flat/Sec16/raw", 2678L, 20L}, {"/flat/Sec15/raw", 2709L, 20L}, {"/flat/Sec14/raw", 2705L, 20L},
				{"/flat/Sec13/raw", 2744L, 20L}, {"/flat/Sec12/raw", 2596L, 20L}, {"/flat/Sec11/raw", 2640L, 20L},
				{"/flat/Sec10/raw", 2752L, 20L}, {"/flat/Sec09/raw", 2671L, 20L}, {"/flat/Sec08/raw", 2498L, 20L},
				{"/flat/Sec07/raw", 2744L, 20L}, {"/flat/Sec06/raw", 1435L, 20L}
		};

		final List<String> datasetNames =
				Arrays.stream(testData).map(record -> (String) record[0]).collect(Collectors.toList());
		final List<Long> botOffsets =
				Arrays.stream(testData).map(record -> (Long) record[1]).collect(Collectors.toList());
		final List<Long> topOffsets =
				Arrays.stream(testData).map(record -> (Long) record[2]).collect(Collectors.toList());

		final long minZForRun = 44032; // 0;
		final long maxZForRun = 46335; // 92415;
		final boolean explainPlan = true;

		final long[] dimensions = new long[]{59817, 73351, 92316};

		final int[] outBlockSize = new int[]{128, 128, 128};
		final int[] gridBlockSize = new int[]{outBlockSize[0] * 8, outBlockSize[1] * 8, outBlockSize[2]};

		final List<long[][]> gridFull = Grid.create(dimensions, gridBlockSize, outBlockSize);

		System.out.println("first grid block is " + printBlock(gridFull.get(0)));
		System.out.println("middle grid block is " + printBlock(gridFull.get(gridFull.size() / 2)));
		System.out.println("last grid block is " + printBlock(gridFull.get(gridFull.size() - 1)));

		final List<long[][]> includedGridBlocks = new ArrayList<>();
		for (final long[][] gridBlock : gridFull) {
			final long[] gridPosition = gridBlock[2];
			if ((gridPosition[2] == 361) && (includedGridBlocks.size() < 3)) {
				includedGridBlocks.add(gridBlock);
			}
		}

		for (final long[][] gridBlock : includedGridBlocks) {
			Assert.assertTrue("incorrect result for included block " + printBlock(gridBlock),
							  SparkExportAlignedSlabSeries.isBlockIncluded(datasetNames,
																		   topOffsets,
																		   botOffsets,
																		   gridBlock,
																		   minZForRun,
																		   maxZForRun,
																		   null,
																		   explainPlan));
		}

	}
}
