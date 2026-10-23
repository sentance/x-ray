package com.xray.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import com.xray.analysis.ActivityStatsRepository.MarketFlows;

class MarketResultTest {

	private static MarketFlows flows(Integer winner, double buyUsdc0, double buyShares0, double buyUsdc1,
			double buyShares1, double redeem) {
		return new MarketFlows("0xc", "btc-updown-15m-1", "t", "Crypto", winner != null, winner, null, buyUsdc0,
				buyShares0, buyUsdc1, buyShares1, 0, 0, 0, 0, redeem, 0, 0, 0, 0, 2, 0, 0);
	}

	@Test
	void pairBoughtBelowOneDollarLocksProfit() {
		// 100 Up at 0.48 + 100 Down at 0.49 = $97 for a guaranteed $100.
		MarketResult r = MarketResult.of(flows(0, 48, 100, 49, 100, 0));
		assertThat(r.bothSides()).isTrue();
		assertThat(r.pairCost()).isCloseTo(0.97, within(1e-9));
		assertThat(r.pnl()).isCloseTo(3, within(1e-9));
	}

	@Test
	void redeemedPayoutIsAlreadyCash() {
		MarketResult r = MarketResult.of(flows(1, 0, 0, 60, 100, 100));
		assertThat(r.bothSides()).isFalse();
		assertThat(r.pnl()).isCloseTo(40, within(1e-9));
	}

	@Test
	void openMarketIsValuedAtCost() {
		MarketResult r = MarketResult.of(flows(null, 30, 100, 0, 0, 0));
		assertThat(r.resolved()).isFalse();
		assertThat(r.pnl()).isCloseTo(0, within(1e-9));
	}
}
