package com.xray.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarketTypesTest {

	@Test
	void classifiesCryptoFormats() {
		assertThat(MarketTypes.of("btc-updown-15m-1792718100", "Crypto")).isEqualTo(MarketTypes.CRYPTO_UP_DOWN_SHORT);
		assertThat(MarketTypes.of("bitcoin-up-or-down-october-23-3pm-et", "Crypto"))
			.isEqualTo(MarketTypes.CRYPTO_UP_DOWN_HOURLY);
		assertThat(MarketTypes.of("ethereum-up-or-down-on-april-3-2026", "Crypto"))
			.isEqualTo(MarketTypes.CRYPTO_UP_DOWN_DAILY);
		assertThat(MarketTypes.of("ethereum-above-2400-on-october-23", "Crypto")).isEqualTo(MarketTypes.CRYPTO_ABOVE);
		assertThat(MarketTypes.of("will-the-price-of-solana-be-between-100-110-on-october-24", "Crypto"))
			.isEqualTo(MarketTypes.CRYPTO_RANGE);
		assertThat(MarketTypes.of("will-solana-dip-to-100-on-october-23", "Crypto")).isEqualTo(MarketTypes.CRYPTO_HIT);
	}

	@Test
	void classifiesSports() {
		assertThat(MarketTypes.of("nba-den-okc-2026-10-22-spread-home-8pt5", "Sports"))
			.isEqualTo(MarketTypes.SPORTS_SPREAD_TOTAL);
		assertThat(MarketTypes.of("nba-den-okc-2026-10-22", "Sports")).isEqualTo(MarketTypes.SPORTS_MATCH);
	}

	@Test
	void outcomeDirection() {
		assertThat(MarketTypes.direction("btc-updown-15m-1", "Up")).isEqualTo(1);
		assertThat(MarketTypes.direction("btc-updown-15m-1", "Down")).isEqualTo(-1);
		assertThat(MarketTypes.direction("bitcoin-above-82k-on-october-23", "No")).isEqualTo(-1);
		assertThat(MarketTypes.direction("will-solana-dip-to-100-on-october-23", "Yes")).isEqualTo(-1);
		assertThat(MarketTypes.direction("will-solana-reach-150-on-october-23", "Yes")).isEqualTo(1);
		assertThat(MarketTypes.direction("will-the-price-of-bitcoin-be-between-80000-82000-on-october-23", "Yes"))
			.isZero();
	}

	@Test
	void binanceSymbol() {
		assertThat(MarketTypes.binanceSymbol("btc-updown-15m-1")).contains("BTCUSDT");
		assertThat(MarketTypes.binanceSymbol("xrp-above-2pt5-on-april-3")).contains("XRPUSDT");
		assertThat(MarketTypes.binanceSymbol("nba-den-okc")).isEmpty();
	}
}
