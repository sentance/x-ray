package com.xray.analysis;

import com.xray.analysis.ActivityStatsRepository.MarketFlows;

/** One market as the wallet played it, with an estimated P&L. */
public record MarketResult(String conditionId, String title, String slug, String category, String marketType,
		double volume, double pnl, boolean bothSides, Double pairCost, boolean resolved, long trades) {

	/** Buying the smaller side at least this fraction of the larger side counts as holding both sides. */
	static final double BOTH_SIDES_MIN_RATIO = 0.25;

	/**
	 * Estimates P&L from cash flows. Resolved markets are valued at the payout of the winning shares still held
	 * (zero once redeemed, since the payout is then already in cash); open markets are valued at cost, so their
	 * P&L only reflects what was realised so far.
	 */
	public static MarketResult of(MarketFlows f) {
		double cash = -f.buyUsdc() + f.sellUsdc() + f.redeemUsdc() + f.mergeUsdc() - f.splitUsdc();
		double net0 = f.buyShares0() - f.sellShares0() + f.splitShares() - f.mergeShares();
		double net1 = f.buyShares1() - f.sellShares1() + f.splitShares() - f.mergeShares();
		boolean resolved = f.winningOutcome() != null;
		double held;
		if (resolved) {
			double winning = f.winningOutcome() == 0 ? net0 : net1;
			held = f.redeemUsdc() > 0 ? 0 : Math.max(0, winning);
		}
		else {
			held = Math.max(0, net0) * avgPrice(f.buyUsdc0(), f.buyShares0())
					+ Math.max(0, net1) * avgPrice(f.buyUsdc1(), f.buyShares1());
		}

		double small = Math.min(f.buyShares0(), f.buyShares1());
		double large = Math.max(f.buyShares0(), f.buyShares1());
		boolean bothSides = large > 0 && small / large >= BOTH_SIDES_MIN_RATIO;
		Double pairCost = bothSides
				? avgPrice(f.buyUsdc0(), f.buyShares0()) + avgPrice(f.buyUsdc1(), f.buyShares1()) : null;

		return new MarketResult(f.conditionId(), f.title(), f.slug(), f.category(),
				MarketTypes.of(f.slug(), f.category()), f.buyUsdc() + f.sellUsdc(), cash + held, bothSides, pairCost,
				resolved, f.trades());
	}

	private static double avgPrice(double usdc, double shares) {
		return shares > 0 ? usdc / shares : 0;
	}
}
