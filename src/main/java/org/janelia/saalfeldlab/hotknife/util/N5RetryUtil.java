package org.janelia.saalfeldlab.hotknife.util;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark;
import org.janelia.saalfeldlab.n5.spark.supplier.N5WriterSupplier;

/**
 * Utility class for retry logic with exponential backoff and jitter.
 * Specifically designed for handling GCS rate limits and other transient failures.
 */
public class N5RetryUtil {

    public static class RetryParameters {

        /** Maximum number of retry attempts (beyond initial attempt). */
        private final int maxRetries;

        /** Initial delay in milliseconds before first retry. */
        private final long delayMs;

        /** Exponential backoff multiplier for delays. */
        private final double backoff;

        /** Maximum random delay in milliseconds before first attempt. */
        private final long startupJitterMs;

        public RetryParameters() {
            this(3, 2000, 2.0, 10_000);
        }

        public RetryParameters(final int maxRetries,
                               final long delayMs,
                               final double backoff,
                               final long startupJitterMs) {

            this.maxRetries = maxRetries;
            this.delayMs = delayMs;
            this.backoff = backoff;
            this.startupJitterMs = startupJitterMs;
        }

        @Override
        public String toString() {
            return "{maxRetries=" + maxRetries + ", delayMs=" + delayMs + ", backoff=" + backoff + ", startupJitterMs=" + startupJitterMs + '}';
        }
    }

    public static class RetryResultAndStats<T> {

        private final T result;
        private final RetryStats stats;

        public RetryResultAndStats(final T result,
                                   final RetryStats stats) {
            this.result = result;
            this.stats = stats;
        }

        public T getResult() {
            return result;
        }

        public RetryStats getStats() {
            return stats;
        }
    }

	/**
	 * Functional interface for operations that can throw exceptions.
	 * Extends Serializable to support Spark's distributed execution.
	 */
	@FunctionalInterface
	public interface RunnableWithException extends Serializable {
		void run() throws Exception;
	}


	/**
	 * Execute an operation with exponential backoff retry logic.
	 * Specifically handles GCS rate limit errors with delays, and retries other errors without delay.
	 *
	 * @param  operation             the operation to execute.
	 * @param  parameters            retry parameters.
	 * @param  operationDescription  description of operation for logging.
	 * @return the result and retry statistics
	 * @throws Exception if all retries are exhausted
	 */
	public static <T> RetryResultAndStats<T> executeWithRetry(
			final Supplier<T> operation,
			final RetryParameters parameters,
			final String operationDescription) throws Exception {

		// Track statistics
		long initialJitterMs = 0;
		long totalWaitTimeMs = 0;

		// Add initial random delay to space out task execution (0 to startupJitterMs)
		if (parameters.startupJitterMs > 0)
		{
			initialJitterMs = (long)(Math.random() * parameters.startupJitterMs);
			logMessage("executeWithRetry: Initial jitter for " + operationDescription +
					   ", delaying first attempt by " + initialJitterMs +
                       "ms (max: " + parameters.startupJitterMs + "ms)");
			Thread.sleep(initialJitterMs);
			totalWaitTimeMs += initialJitterMs;
		}
		else
		{
			logMessage("executeWithRetry: NO initial jitter for " + operationDescription);
		}

		Exception lastException = null;
		long delayMs = parameters.delayMs;
		int actualRetries = 0;

		for (int attempt = 0; attempt <= parameters.maxRetries; attempt++) {
			final String context = operationDescription + " (attempt " + (attempt+1) + "/" + (parameters.maxRetries+1) + ")";
			try {
				final T result = operation.get();
				final RetryStats stats = new RetryStats(
					operationDescription,
					actualRetries,
					totalWaitTimeMs,
					initialJitterMs);
				return new RetryResultAndStats<>(result, stats);
			} catch (final Exception e) {
				lastException = e;

				// Check if it's a GCS rate limit error
				final boolean isRateLimitError = e.getMessage() != null &&
					(e.getMessage().contains("GCS429") ||
					 e.getMessage().contains("rate limit") ||
					 e.getMessage().contains("StorageException"));

				if (isRateLimitError && attempt < parameters.maxRetries) {
					// Add jitter: randomize delay between 50% and 150% of calculated value
					// This prevents thundering herd where all workers retry at similar intervals
					long jitteredDelay = (long)(delayMs * (0.5 + Math.random()));
					logMessage("executeWithRetry: GCS rate limit hit for " + context +
							   ", retrying in " + jitteredDelay + "ms (jittered from " + delayMs + "ms)");
					Thread.sleep(jitteredDelay);
					totalWaitTimeMs += jitteredDelay;
					delayMs = (long)(delayMs * parameters.backoff);
					actualRetries++;
				} else if (attempt < parameters.maxRetries) {
					// For non-rate-limit errors, retry without delay
					logMessage("executeWithRetry: Error in  " + context + ", exception: " + e.getMessage());
					actualRetries++;
				}
			}
		}

		// All retries exhausted - fail fast
		throw new RuntimeException(
			"Failed " + operationDescription + "after " + (parameters.maxRetries+1) + " attempts",
			lastException);
	}


	/**
	 * Execute a void operation with exponential backoff retry logic.
	 *
	 * @param  operation   The operation to execute.
	 * @param  parameters  retry parameters.
	 * @throws Exception if all retries are exhausted
	 */
	public static RetryStats executeWithRetryVoid(
			final RunnableWithException operation,
			final RetryParameters parameters,
			final String operationDescription) throws Exception {

		final RetryResultAndStats<Void> result = executeWithRetry(
				() -> {
					try {
						operation.run();
						return null;
					} catch (Exception e) {
						throw new RuntimeException(e);
					}
				},
				parameters,
				operationDescription);

		return result.stats;
	}

    public static List<String> downsampleWithRetry(final JavaSparkContext sparkContext,
                                                   final N5WriterSupplier n5Supplier,
                                                   final String datasetPath,
                                                   final int[] outputBlockSize,
                                                   final String outputGroupPath,
                                                   final int[] downsamplingStepFactors)
            throws IOException {

        return downsampleWithRetry(sparkContext,
                                   n5Supplier,
                                   datasetPath,
                                   outputBlockSize,
                                   outputGroupPath,
                                   downsamplingStepFactors,
                                   9,
                                   new RetryParameters());
    }

    /**
     * Downsamples an N5 dataset iteratively until only a single block remains, writing each
     * scale level to the output group as s1, s2, s3, etc. Downsampling will continue beyond
     * a single block if necessary to ensure that the specified required downsample level is
     * produced. Each downsample operation is executed with retry logic.
     *
     * @param  sparkContext             the Spark context used for distributed processing
     * @param  n5Supplier               supplier for the N5 writer used to read and write datasets
     * @param  datasetPath              path to the full-resolution input dataset
     * @param  outputBlockSize          block size for the downsampled output datasets
     * @param  outputGroupPath          path to the output group where scale levels will be written
     * @param  downsamplingStepFactors  per-dimension factors applied at each downsampling step
     * @param  requiredDownsampleLevel  the minimum s-level that must be produced;
     *                                  downsampling will continue past a single block
     *                                  if this level has not yet been reached
     * @param  retryParameters          parameters controlling retry behavior on failure
     *
     * @return list of paths to all downsampled datasets created, in order from s1 outward
     *
     * @throws IOException
     *   if an N5 read or write operation fails
     */
    public static List<String> downsampleWithRetry(final JavaSparkContext sparkContext,
                                                   final N5WriterSupplier n5Supplier,
                                                   final String datasetPath,
                                                   final int[] outputBlockSize,
                                                   final String outputGroupPath,
                                                   final int[] downsamplingStepFactors,
                                                   final int requiredDownsampleLevel,
                                                   final RetryParameters retryParameters)
            throws IOException {

        logMessage("downsampleWithRetry: entry, datasetPath=" + datasetPath +
                   ", outputBlockSize=" + Arrays.toString(outputBlockSize) + ", outputGroupPath=" + outputGroupPath +
                   ", downsamplingStepFactors=" + Arrays.toString(downsamplingStepFactors) +
                   ", retryParameters=" + retryParameters);

        final N5Writer n5 = n5Supplier.get();
        final DatasetAttributes fullScaleAttributes = n5.getDatasetAttributes(datasetPath);
        final long[] dimensions = fullScaleAttributes.getDimensions();
        final int dim = dimensions.length;

        final List<String> downsampledDatasets = new ArrayList<>();

        long downsampledBlockCount = 2;
        for (int scale = 1; downsampledBlockCount > 1 || scale <= requiredDownsampleLevel; scale++) {
            final int[] scaleFactors = new int[dim];
            for (int d = 0; d < dim; d++) {
                scaleFactors[d] = (int) Math.round(Math.pow(downsamplingStepFactors[d], scale));
            }

            long blockCount = 1;
            final long[] downsampledDimensions = new long[dim];
            for (int d = 0; d < dim; d++) {
                downsampledDimensions[d] = dimensions[d] / scaleFactors[d];
                final long blocksInDim = (downsampledDimensions[d] + outputBlockSize[d] - 1) / outputBlockSize[d];
                blockCount *= blocksInDim;
            }
            downsampledBlockCount = blockCount;

            final String inputDatasetPath = scale == 1 ? datasetPath : Paths.get(outputGroupPath, "s" + (scale - 1 ) ).toString();
            final String outputDatasetPath = Paths.get( outputGroupPath, "s" + scale ).toString();

            final String operationDescription = "downsample s" + (scale-1) + " to s" + scale;
            try {
                final RetryStats retryStats = executeWithRetryVoid(
                        () -> N5DownsamplerSpark.downsample(sparkContext,
                                                            n5Supplier,
                                                            inputDatasetPath,
                                                            outputDatasetPath,
                                                            downsamplingStepFactors),
                        retryParameters,
                        operationDescription);

                logMessage("downsampleWithRetry: created s" + scale + " with " + downsampledBlockCount + " block(s) and " + retryStats);

            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            downsampledDatasets.add( outputDatasetPath );
        }

        logMessage("downsampleWithRetry: exit, created " + downsampledDatasets.size() + " downsampled datasets");

        return downsampledDatasets;
    }

    private static void logMessage(final String message) {
        org.janelia.saalfeldlab.hotknife.util.Util.logMessage(N5RetryUtil.class.getName(),
                                                              message);
    }

}
