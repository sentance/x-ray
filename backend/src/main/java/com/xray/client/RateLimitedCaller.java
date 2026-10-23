package com.xray.client;

import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Spaces requests evenly to stay under an upstream rate limit and retries throttled or failed calls with
 * exponential backoff.
 */
public class RateLimitedCaller {

	private static final Logger log = LoggerFactory.getLogger(RateLimitedCaller.class);

	private static final int MAX_ATTEMPTS = 5;

	private final long intervalNanos;

	private final Duration baseBackoff;

	private long nextSlot = System.nanoTime();

	public RateLimitedCaller(double requestsPerSecond, Duration baseBackoff) {
		this.intervalNanos = (long) (1_000_000_000L / requestsPerSecond);
		this.baseBackoff = baseBackoff;
	}

	public <T> T call(String description, Supplier<T> request) {
		for (int attempt = 1;; attempt++) {
			acquire();
			try {
				return request.get();
			}
			catch (HttpClientErrorException.TooManyRequests | HttpServerErrorException | ResourceAccessException e) {
				if (attempt >= MAX_ATTEMPTS) {
					throw e;
				}
				Duration backoff = baseBackoff.multipliedBy(1L << (attempt - 1));
				log.warn("{} failed ({}), retry {} in {}", description, e.getMessage(), attempt, backoff);
				sleep(backoff);
			}
		}
	}

	private void acquire() {
		long wait;
		synchronized (this) {
			long now = System.nanoTime();
			long slot = Math.max(now, nextSlot);
			nextSlot = slot + intervalNanos;
			wait = slot - now;
		}
		if (wait > 0) {
			sleep(Duration.ofNanos(wait));
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for rate limit", e);
		}
	}
}
