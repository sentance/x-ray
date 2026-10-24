package com.xray.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.xray.config.XrayProperties;

class AiRateLimiterTest {

	private static AiRateLimiter limiter(int perVisitor, int total) {
		return new AiRateLimiter(new XrayProperties(null, null, null,
				new XrayProperties.Ai("key", "claude-opus-5-5", perVisitor, total), List.of()));
	}

	@Test
	void limitsEachVisitor() {
		AiRateLimiter limiter = limiter(2, 100);
		assertThat(limiter.tryAcquire("a")).isTrue();
		assertThat(limiter.tryAcquire("a")).isTrue();
		assertThat(limiter.tryAcquire("a")).isFalse();
		assertThat(limiter.tryAcquire("b")).isTrue();
	}

	@Test
	void capsEveryoneTogether() {
		AiRateLimiter limiter = limiter(10, 3);
		assertThat(limiter.tryAcquire("a")).isTrue();
		assertThat(limiter.tryAcquire("b")).isTrue();
		assertThat(limiter.tryAcquire("c")).isTrue();
		assertThat(limiter.tryAcquire("d")).isFalse();
	}
}
