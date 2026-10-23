package com.xray.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.xray.ingest.PnlRepository.DailyPnl;

class DecayAnalyzerTest {

	/** Cumulative series earning {@code early} per day for 200 days, then {@code late} per day for 30 days. */
	private static List<DailyChange> series(double early, double late) {
		List<DailyPnl> points = new ArrayList<>();
		double pnl = 0;
		for (int day = 0; day < 230; day++) {
			pnl += day < 200 ? early : late;
			points.add(new DailyPnl(1_700_000_000L + day * 86_400L, pnl, pnl, 0, -day, 0, 0, 0, 1000.0 * (day + 1),
					10L * (day + 1)));
		}
		return DailyChange.of(points);
	}

	@Test
	void stableEdgeIsStillWorking() {
		assertThat(new DecayAnalyzer().analyze(series(10, 10)).verdict()).isEqualTo("Still working");
	}

	@Test
	void shrinkingEdgeIsWeakening() {
		assertThat(new DecayAnalyzer().analyze(series(10, 4)).verdict()).isEqualTo("Weakening");
	}

	@Test
	void recentLossesMeanStopped() {
		assertThat(new DecayAnalyzer().analyze(series(10, -5)).verdict()).isEqualTo("Stopped working");
	}
}
