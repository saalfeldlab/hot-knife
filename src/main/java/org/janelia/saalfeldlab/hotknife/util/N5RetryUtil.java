package org.janelia.saalfeldlab.hotknife.util;

import java.io.Serializable;
import java.util.function.Supplier;

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
	 * @return Result of the operation
	 * @throws Exception if all retries are exhausted
	 */
	public static <T> T executeWithRetry(
			final Supplier<T> operation,
			final int maxRetries,
			final long retryDelayMs,
			final double backoffMultiplier,
			final long startupJitterMs,
			final String operationDescription) throws Exception {

		// Add initial random delay to space out task execution (0 to startupJitterMs)
		if (startupJitterMs > 0) {
			long initialDelay = (long)(Math.random() * startupJitterMs);
			System.out.println(String.format(
				"Initial jitter for %s: delaying first attempt by %dms (max: %dms)",
				operationDescription, initialDelay, startupJitterMs));
			Thread.sleep(initialDelay);
		}

		Exception lastException = null;
		long delayMs = retryDelayMs;

		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			try {
				return operation.get();
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
					delayMs = (long)(delayMs * backoffMultiplier);
				} else if (attempt < maxRetries) {
					// For non-rate-limit errors, retry without delay
					System.out.println(String.format(
						"Error in %s (attempt %d/%d): %s",
						operationDescription, attempt + 1, maxRetries + 1, e.getMessage()));
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
	public static void executeWithRetryVoid(
			final RunnableWithException operation,
			final int maxRetries,
			final long retryDelayMs,
			final double backoffMultiplier,
			final long startupJitterMs,
			final String operationDescription) throws Exception {

		executeWithRetry(() -> {
			try {
				operation.run();
				return null;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}, maxRetries, retryDelayMs, backoffMultiplier, startupJitterMs, operationDescription);
	}
}
