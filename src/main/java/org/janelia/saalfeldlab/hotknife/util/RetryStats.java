package org.janelia.saalfeldlab.hotknife.util;

import java.io.Serializable;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Statistics collected during a retry operation.
 * Tracks retry counts and wait times for analysis.
 */
public class RetryStats implements Serializable {
	private static final long serialVersionUID = 1L;

	private final String operationDescription;
	private final int retryCount;           // Number of retries performed (not including initial attempt)
	private final long totalWaitTimeMs;     // Sum of all wait times (initial jitter + retry delays)
	private final long initialJitterMs;     // Initial jitter delay before first attempt

	public RetryStats(
			final String operationDescription,
			final int retryCount,
			final long totalWaitTimeMs,
			final long initialJitterMs) {
		this.operationDescription = operationDescription;
		this.retryCount = retryCount;
		this.totalWaitTimeMs = totalWaitTimeMs;
		this.initialJitterMs = initialJitterMs;
	}

	public String getOperationDescription() { return operationDescription; }
	public int getRetryCount() { return retryCount; }
	public long getTotalWaitTimeMs() { return totalWaitTimeMs; }
	public long getInitialJitterMs() { return initialJitterMs; }

	@Override
	public String toString() {
		return String.format("RetryStats[op=%s, retries=%d, totalWait=%dms, jitter=%dms]",
			operationDescription, retryCount, totalWaitTimeMs, initialJitterMs);
	}

	/**
	 * Report retry statistics with dynamic histogram bucketing based on actual data range.
	 * Computes separate histograms for retry counts and wait times.
	 *
	 * @param stats List of RetryStats collected from all operations
	 */
	public static void reportRetryStatisticsWithDynamicBuckets(List<RetryStats> stats) {
		if (stats == null || stats.isEmpty()) {
			System.out.println("No statistics available.");
			return;
		}

		final int totalOps = stats.size();
		System.out.println("Total operations: " + totalOps);

		// Compute retry count histogram (discrete values)
		Map<Integer, Long> retryHistogram = stats.stream()
			.collect(Collectors.groupingBy(RetryStats::getRetryCount, Collectors.counting()));

		System.out.println("\nRetry Count Distribution:");
		retryHistogram.entrySet().stream()
			.sorted(Map.Entry.comparingByKey())
			.forEach(entry -> {
				int retries = entry.getKey();
				long count = entry.getValue();
				double percentage = (count * 100.0) / totalOps;
				System.out.println(String.format("  %d retries: %d operations (%.1f%%)",
					retries, count, percentage));
			});

		// Compute wait time statistics
		LongSummaryStatistics waitTimeStats = stats.stream()
			.collect(Collectors.summarizingLong(RetryStats::getTotalWaitTimeMs));

		long minWait = waitTimeStats.getMin();
		long maxWait = waitTimeStats.getMax();
		double avgWait = waitTimeStats.getAverage();
		long medianWait = calculateMedian(stats.stream()
			.map(RetryStats::getTotalWaitTimeMs)
			.sorted()
			.collect(Collectors.toList()));

		System.out.println(String.format("\nWait Time Summary (ms): min=%d, max=%d, avg=%.1f, median=%d",
			minWait, maxWait, avgWait, medianWait));

		// Dynamic bucketing for wait times
		if (maxWait > minWait) {
			// Determine number of buckets based on data size (5-10 buckets)
			int numBuckets = Math.min(10, Math.max(5, totalOps / 20));
			long bucketSize = (maxWait - minWait) / numBuckets + 1;

			Map<Long, Long> waitTimeHistogram = stats.stream()
				.collect(Collectors.groupingBy(
					s -> getDynamicWaitTimeBucket(s.getTotalWaitTimeMs(), minWait, bucketSize),
					Collectors.counting()));

			System.out.println("\nWait Time Distribution (dynamic buckets):");
			waitTimeHistogram.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(entry -> {
					long bucketStart = entry.getKey();
					long bucketEnd = bucketStart + bucketSize - 1;
					long count = entry.getValue();
					double percentage = (count * 100.0) / totalOps;

					// Format bucket range appropriately based on magnitude
					String rangeStr;
					if (bucketEnd < 10000) {
						rangeStr = String.format("%d-%dms", bucketStart, bucketEnd);
					} else {
						rangeStr = String.format("%.1f-%.1fs",
							bucketStart / 1000.0, bucketEnd / 1000.0);
					}

					System.out.println(String.format("  %s: %d operations (%.1f%%)",
						rangeStr, count, percentage));
				});
		} else {
			System.out.println("\nAll operations had identical wait times: " + minWait + "ms");
		}

		// Report initial jitter statistics
		LongSummaryStatistics jitterStats = stats.stream()
			.collect(Collectors.summarizingLong(RetryStats::getInitialJitterMs));

		System.out.println(String.format("\nInitial Jitter (ms): min=%d, max=%d, avg=%.1f",
			jitterStats.getMin(), jitterStats.getMax(), jitterStats.getAverage()));
	}

	/**
	 * Calculate bucket index for dynamic histogram bucketing.
	 *
	 * @param value The wait time value
	 * @param minValue The minimum wait time across all operations
	 * @param bucketSize The size of each bucket
	 * @return The bucket start value
	 */
	private static long getDynamicWaitTimeBucket(long value, long minValue, long bucketSize) {
		return ((value - minValue) / bucketSize) * bucketSize + minValue;
	}

	/**
	 * Calculate median from a sorted list of values.
	 *
	 * @param sortedValues List of values sorted in ascending order
	 * @return The median value
	 */
	private static long calculateMedian(List<Long> sortedValues) {
		if (sortedValues.isEmpty()) {
			return 0;
		}
		int size = sortedValues.size();
		if (size % 2 == 0) {
			return (sortedValues.get(size / 2 - 1) + sortedValues.get(size / 2)) / 2;
		} else {
			return sortedValues.get(size / 2);
		}
	}
}
