package com.xray.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.xray.config.XrayProperties;

/**
 * Hourly limits on AI requests, per visitor and overall. The site is public and every request is billed to
 * the operator's API key, so both limits apply before a request is sent.
 */
@Component
public class AiRateLimiter {

	private static final Duration WINDOW = Duration.ofHours(1);

	private final int perVisitor;

	private final int total;

	private final Map<String, Deque<Instant>> byVisitor = new ConcurrentHashMap<>();

	private final Deque<Instant> all = new ArrayDeque<>();

	public AiRateLimiter(XrayProperties properties) {
		this.perVisitor = properties.ai().chatPerIpPerHour();
		this.total = properties.ai().requestsPerHour();
	}

	/** Records the request if both limits allow it. */
	public synchronized boolean tryAcquire(String visitor) {
		Instant now = Instant.now();
		Deque<Instant> mine = byVisitor.computeIfAbsent(visitor, v -> new ArrayDeque<>());
		prune(mine, now);
		prune(all, now);
		if (mine.size() >= perVisitor || all.size() >= total) {
			return false;
		}
		mine.addLast(now);
		all.addLast(now);
		byVisitor.values().removeIf(Deque::isEmpty);
		return true;
	}

	private static void prune(Deque<Instant> times, Instant now) {
		while (!times.isEmpty() && times.peekFirst().plus(WINDOW).isBefore(now)) {
			times.pollFirst();
		}
	}
}
