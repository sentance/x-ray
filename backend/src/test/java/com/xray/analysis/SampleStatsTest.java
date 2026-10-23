package com.xray.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.xray.analysis.ActivityStatsRepository.Bucket;
import com.xray.analysis.ActivityStatsRepository.MarketFlows;
import com.xray.client.PolymarketDtos.Activity;
import com.xray.market.MarketRepository.KnownMarket;

class SampleStatsTest {

	// 2026-10-19 is a Monday.
	private static final long MONDAY_10_UTC = Instant.parse("2026-10-19T10:00:00Z").getEpochSecond();

	private static Activity trade(String market, long ts, String side, int outcome, double price, double usdc) {
		return new Activity("0xw", ts, market, "TRADE", usdc / price, usdc, "0xtx", price, "a", side, outcome, "t",
				"btc-updown-15m-1", "btc-updown-15m-1", "Up");
	}

	private static Activity redeem(String market, long ts, double usdc) {
		return new Activity("0xw", ts, market, "REDEEM", usdc, usdc, "0xtx", null, null, null, null, "t", null, null,
				null);
	}

	@Test
	void percentileInterpolatesLikePostgres() {
		assertThat(SampleStats.percentile(List.of(4.0, 1.0, 3.0, 2.0), 0.5)).isEqualTo(2.5);
		assertThat(SampleStats.percentile(List.of(10.0, 20.0, 30.0), 0.9)).isCloseTo(28, within(1e-9));
		assertThat(SampleStats.percentile(List.of(), 0.5)).isNull();
	}

	@Test
	void flowsSplitByOutcomeAndSide() {
		var stats = new SampleStats(List.of(trade("0xa", MONDAY_10_UTC, "BUY", 0, 0.48, 48),
				trade("0xa", MONDAY_10_UTC + 1, "BUY", 1, 0.49, 49), redeem("0xa", MONDAY_10_UTC + 900, 100)),
				Map.of());

		MarketFlows f = stats.marketFlows().getFirst();
		assertThat(f.buyUsdc0()).isEqualTo(48);
		assertThat(f.buyShares1()).isCloseTo(100, within(1e-9));
		assertThat(f.redeemUsdc()).isEqualTo(100);
		assertThat(f.trades()).isEqualTo(2);
		assertThat(f.category()).isEqualTo("Crypto");
		assertThat(MarketResult.of(f).bothSides()).isTrue();
	}

	@Test
	void knownMarketsSupplyCategoryAndExpiry() {
		Instant end = Instant.ofEpochSecond(MONDAY_10_UTC + 600);
		var stats = new SampleStats(List.of(trade("0xa", MONDAY_10_UTC, "BUY", 0, 0.95, 95)),
				Map.of("0xa", new KnownMarket("0xa", "Sports", end, false, null)));

		assertThat(stats.marketFlows().getFirst().category()).isEqualTo("Sports");
		assertThat(stats.medianSecondsToExpiry()).isEqualTo(600);
		assertThat(stats.timeToExpiryBuckets()).containsExactly(new Bucket("5–15 min", 1, 95));
		assertThat(stats.entryPriceBuckets()).containsExactly(new Bucket("0.90–0.97", 1, 95));
	}

	@Test
	void timingCountsTradesOnly() {
		var stats = new SampleStats(List.of(trade("0xa", MONDAY_10_UTC, "BUY", 0, 0.5, 10),
				trade("0xa", MONDAY_10_UTC + 30, "SELL", 0, 0.6, 12), redeem("0xa", MONDAY_10_UTC + 7200, 5)),
				Map.of());

		var timing = stats.timing();
		assertThat(timing.hourOfDay()[10]).isEqualTo(2);
		assertThat(timing.dayOfWeek()[0]).isEqualTo(2);
		assertThat(timing.activeDays()).isEqualTo(1);
		assertThat(timing.medianGapSeconds()).isEqualTo(30);
		assertThat(stats.tradeCount()).isEqualTo(2);
		assertThat(stats.tradeSizes().p50()).isEqualTo(11);
	}
}
