package org.janelia.saalfeldlab.hotknife.util;

import java.io.IOException;
import java.io.Serializable;
import java.util.function.Supplier;

/**
 * Utility class for retry logic with exponential backoff and jitter.
 * Specifically designed for handling GCS rate limits and other transient failures.
 */
public class N5RetryUtil {

    public static class RetryParameters implements Serializable {

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
                    e.printStackTrace();
					actualRetries++;
				}
			}
		}

		// All retries exhausted - fail fast
		throw new RuntimeException(
			"Failed " + operationDescription + " after " + (parameters.maxRetries+1) + " attempts",
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

	/**
	 * Retry parameters for n5 metadata writes on the Spark driver (createGroup, createDataset, setAttribute).
	 * With n5-google-cloud, every createGroup and createDataset rewrites the folder marker object of each
	 * parent group.  Concurrent jobs writing under the same parents can hit the GCS limit of one write per
	 * second to the same object (429 rateLimitExceeded).  The retries wait about 2, 4, 8, 16, 32, and 64
	 * seconds (each randomized between 50% and 150%) so that a collision costs seconds instead of the job.
	 */
	public static final RetryParameters METADATA_RETRY_PARAMETERS = new RetryParameters(6, 2000, 2.0, 0);

	/**
	 * Run a driver-side n5 metadata write with {@link #METADATA_RETRY_PARAMETERS}.
	 *
	 * @param  operation             the write to run.
	 * @param  operationDescription  description of the write for logging.
	 * @throws IOException if the write still fails after all retries.
	 */
	public static void executeMetadataWriteWithRetry(
			final RunnableWithException operation,
			final String operationDescription) throws IOException {

		try {
			executeWithRetryVoid(operation, METADATA_RETRY_PARAMETERS, operationDescription);
		} catch (final Exception e) {
			throw new IOException(e.getMessage(), e);
		}
	}

    private static void logMessage(final String message) {
        org.janelia.saalfeldlab.hotknife.util.Util.logMessage(N5RetryUtil.class.getName(),
                                                              message);
    }

}
