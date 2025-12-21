package org.janelia.saalfeldlab.hotknife.util;

import java.io.Serializable;
import java.util.function.Supplier;

import scala.Tuple2;

/**
 * Utility class for retry logic with exponential backoff and jitter.
 * Specifically designed for handling GCS rate limits and other transient failures.
 */
public class N5RetryUtil {

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
	 * @param operation The operation to execute
	 * @param maxRetries Maximum number of retry attempts (beyond initial attempt)
	 * @param retryDelayMs Initial delay in milliseconds before first retry
	 * @param backoffMultiplier Exponential backoff multiplier for delays
	 * @param startupJitterMs Maximum random delay in milliseconds before first attempt
	 * @param operationDescription Description of operation for logging
	 * @return Tuple2 containing the result and retry statistics
	 * @throws Exception if all retries are exhausted
	 */
	public static <T> Tuple2<T, RetryStats> executeWithRetry(
			final Supplier<T> operation,
			final int maxRetries,
			final long retryDelayMs,
			final double backoffMultiplier,
			final long startupJitterMs,
			final String operationDescription) throws Exception {

		// Track statistics
		long initialJitterMs = 0;
		long totalWaitTimeMs = 0;

		// Add initial random delay to space out task execution (0 to startupJitterMs)
		if (startupJitterMs > 0)
		{
			initialJitterMs = (long)(Math.random() * startupJitterMs);
			System.out.println(String.format(
				"Initial jitter for %s: delaying first attempt by %dms (max: %dms)",
				operationDescription, initialJitterMs, startupJitterMs));
			Thread.sleep(initialJitterMs);
			totalWaitTimeMs += initialJitterMs;
		}
		else
		{
			System.out.println(String.format(
					"NO initial jitter for %s: delaying first attempt",
					operationDescription ));
		}

		Exception lastException = null;
		long delayMs = retryDelayMs;
		int actualRetries = 0;

		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			try {
				final T result = operation.get();
				final RetryStats stats = new RetryStats(
					operationDescription,
					actualRetries,
					totalWaitTimeMs,
					initialJitterMs);
				return new Tuple2<>(result, stats);
			} catch (final Exception e) {
				lastException = e;

				// Check if it's a GCS rate limit error
				final boolean isRateLimitError = e.getMessage() != null &&
					(e.getMessage().contains("GCS429") ||
					 e.getMessage().contains("rate limit") ||
					 e.getMessage().contains("StorageException"));

				if (isRateLimitError && attempt < maxRetries) {
					// Add jitter: randomize delay between 50% and 150% of calculated value
					// This prevents thundering herd where all workers retry at similar intervals
					long jitteredDelay = (long)(delayMs * (0.5 + Math.random()));
					System.out.println(String.format(
						"GCS rate limit hit for %s (attempt %d/%d), retrying in %dms (jittered from %dms)",
						operationDescription, attempt + 1, maxRetries + 1, jitteredDelay, delayMs));
					Thread.sleep(jitteredDelay);
					totalWaitTimeMs += jitteredDelay;
					delayMs = (long)(delayMs * backoffMultiplier);
					actualRetries++;
				} else if (attempt < maxRetries) {
					// For non-rate-limit errors, retry without delay
					System.out.println(String.format(
						"Error in %s (attempt %d/%d): %s",
						operationDescription, attempt + 1, maxRetries + 1, e.getMessage()));
					actualRetries++;
				}
			}
		}

		// All retries exhausted - fail fast
		throw new RuntimeException(
			String.format("Failed %s after %d attempts", operationDescription, maxRetries + 1),
			lastException);
	}


	/**
	 * Execute a void operation with exponential backoff retry logic.
	 *
	 * @param operation The operation to execute
	 * @param maxRetries Maximum number of retry attempts
	 * @param retryDelayMs Initial delay in milliseconds
	 * @param backoffMultiplier Exponential backoff multiplier
	 * @param startupJitterMs Maximum random delay in milliseconds before first attempt
	 * @param operationDescription Description of operation for logging
	 * @throws Exception if all retries are exhausted
	 */
	public static RetryStats executeWithRetryVoid(
			final RunnableWithException operation,
			final int maxRetries,
			final long retryDelayMs,
			final double backoffMultiplier,
			final long startupJitterMs,
			final String operationDescription) throws Exception {

		Tuple2<Void, RetryStats> result = executeWithRetry(() -> {
			try {
				operation.run();
				return null;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, maxRetries, retryDelayMs, backoffMultiplier, startupJitterMs, operationDescription);
		return result._2();
	}
}
