package com.xray.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class StatsTest {

	@Test
	void binomialUpperTail() {
		// P(X >= 8 | n = 10) = (45 + 10 + 1) / 1024
		assertThat(Stats.binomialUpperTail(10, 8)).isCloseTo(56 / 1024.0, within(1e-12));
		assertThat(Stats.binomialUpperTail(10, 0)).isEqualTo(1);
		assertThat(Stats.binomialUpperTail(10, 11)).isEqualTo(0);
	}

	@Test
	void longestRunAtLeast() {
		// Of the 8 sequences of 3 flips, HHH, HHT and THH contain two heads in a row.
		assertThat(Stats.longestRunAtLeast(3, 2)).isCloseTo(3 / 8.0, within(1e-12));
		assertThat(Stats.longestRunAtLeast(4, 1)).isCloseTo(15 / 16.0, within(1e-12));
		assertThat(Stats.longestRunAtLeast(5, 6)).isEqualTo(0);
	}

	@Test
	void normalUpperTail() {
		assertThat(Stats.normalUpperTail(1.959964)).isCloseTo(0.025, within(1e-6));
		assertThat(Stats.normalUpperTail(0)).isCloseTo(0.5, within(1e-7));
		assertThat(Stats.normalUpperTail(-1.959964)).isCloseTo(0.975, within(1e-6));
	}
}
