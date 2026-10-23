package com.xray.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToDoubleFunction;

import org.junit.jupiter.api.Test;

import com.xray.analysis.BinanceLagAnalyzer.BinanceReport;
import com.xray.analysis.BinanceLagAnalyzer.CryptoBuy;
import com.xray.analysis.BinanceLagAnalyzer.Window;

class BinanceLagAnalyzerTest {

	private static final long T0 = 1_792_000_000L - 1_792_000_000L % 3600;

	private final BinanceLagAnalyzer analyzer = new BinanceLagAnalyzer();

	/** Prices from a function of the minute index since T0. */
	private static BinanceLagAnalyzer.Prices prices(LongToDoubleFunction byMinute) {
		return (symbol, minuteMs) -> byMinute.applyAsDouble((minuteMs / 1000 - T0) / 60);
	}

	private static Window window(BinanceReport r, String label) {
		return r.windows().stream().filter(w -> w.label().equals(label)).findFirst().orElseThrow();
	}

	@Test
	void buyingTheSideSpotAlreadyMovedTowardIsLag() {
		// Price zigzags every 10 minutes; each buy bets on the direction of the previous 10 minutes.
		LongToDoubleFunction zigzag = m -> 100 + ((m / 10) % 2 == 0 ? m % 10 : 10 - m % 10);
		List<CryptoBuy> buys = new ArrayList<>();
		for (int i = 1; i <= 60; i++) {
			long minute = i * 10L + 5;
			int direction = ((minute / 10) % 2 == 0) ? 1 : -1;
			buys.add(new CryptoBuy(T0 + minute * 60 + 20, "BTCUSDT", direction, 10, null));
		}
		BinanceReport r = analyzer.analyze(buys, prices(zigzag));

		Window before1 = window(r, "1 min before");
		assertThat(before1.trades()).isEqualTo(60);
		assertThat(before1.alignedShare()).isEqualTo(1.0);
		assertThat(before1.pValue()).isLessThan(1e-9);
		assertThat(r.verdict()).isEqualTo("Trades after spot moves");
	}

	@Test
	void sinceOpenUsesTheWindowStartAndLeadsTheVerdict() {
		// Spot rises all the time; buys of Down since the window opened go against it.
		List<CryptoBuy> buys = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			long start = T0 + i * 900L;
			buys.add(new CryptoBuy(start + 600, "ETHUSDT", -1, 5, start));
		}
		BinanceReport r = analyzer.analyze(buys, prices(m -> 1000 + m));

		Window sinceOpen = window(r, "Since the window opened");
		assertThat(sinceOpen.trades()).isEqualTo(40);
		assertThat(sinceOpen.alignedShare()).isZero();
		assertThat(sinceOpen.meanSignedMoveBps()).isNegative();
		assertThat(r.verdict()).isEqualTo("No lag pattern");
	}

	@Test
	void flatOrUnknownPricesAreNotCounted() {
		List<CryptoBuy> buys = List.of(new CryptoBuy(T0 + 3600, "SOLUSDT", 1, 1, null),
				new CryptoBuy(T0 + 7200, "SOLUSDT", 0, 1, null));
		BinanceReport r = analyzer.analyze(buys, prices(m -> 50));

		assertThat(r.cryptoBuys()).isEqualTo(2);
		assertThat(r.directionalBuys()).isEqualTo(1);
		assertThat(window(r, "5 min after").trades()).isZero();
		assertThat(r.verdict()).isEqualTo("Not enough data");
	}

	@Test
	void windowsExcludeTheTradesOwnMinute() {
		CryptoBuy b = new CryptoBuy(T0 + 10 * 60 + 30, "BTCUSDT", 1, 1, null);
		BinanceLagAnalyzer.Prices p = prices(m -> 100 + m);
		// Before: open of minute 10 vs minute 9. After: open of minute 11 vs 12.
		assertThat(BinanceLagAnalyzer.move(b, BinanceLagAnalyzer.When.BEFORE, 1, p)).isCloseTo(110.0 / 109 - 1,
				within(1e-12));
		assertThat(BinanceLagAnalyzer.move(b, BinanceLagAnalyzer.When.AFTER, 1, p)).isCloseTo(112.0 / 111 - 1,
				within(1e-12));
	}
}
