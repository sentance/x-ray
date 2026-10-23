package com.xray.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.xray.client.PolymarketDtos.Activity;

class DedupeWindowTest {

	private static Activity fill(long ts, double size) {
		return new Activity("0xw", ts, "0xc", "TRADE", size, size * 0.5, "0xtx", 0.5, "1", "BUY", 0, "t", "s", "e",
				"Yes");
	}

	@Test
	void identicalFillsGetDistinctKeys() {
		DedupeWindow window = new DedupeWindow();
		assertThat(window.key(fill(1, 10))).isNotEqualTo(window.key(fill(1, 10)));
	}

	@Test
	void newWindowReproducesKeysForRowsAtBoundary() {
		DedupeWindow first = new DedupeWindow();
		String a = first.key(fill(5, 10));

		DedupeWindow second = new DedupeWindow();
		assertThat(second.key(fill(5, 10))).isEqualTo(a);
		assertThat(second.key(fill(5, 10))).isNotEqualTo(a);
	}
}
