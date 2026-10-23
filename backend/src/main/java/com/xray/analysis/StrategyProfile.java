package com.xray.analysis;

import java.util.List;

import com.xray.analysis.ActivityStatsRepository.Bucket;
import com.xray.analysis.ActivityStatsRepository.SizePercentiles;

/** The measurable facts behind "What it does". */
public record StrategyProfile(Activity activity, List<Share> categories, List<Share> marketTypes,
		List<Share> cryptoAssets, BothSides bothSides, Exits exits, EntryPrices entryPrices, Expiry expiry,
		SizePercentiles tradeSizes, Timing timing, List<MarketResult> topWinners, List<MarketResult> topLosers,
		double estimatedMarketPnl) {

	public record Activity(long trades, long buys, long sells, double buyUsdc, double sellUsdc, int markets,
			long activeDays, double tradesPerActiveDay, double incomeUsdc) {
	}

	/** A slice of buy+sell volume (e.g. one category). */
	public record Share(String name, long trades, double volume, double share, double pnl) {
	}

	public record BothSides(int markets, double shareOfMarkets, double shareOfVolume, Double avgPairCost,
			double pairsBelowOneShare) {
	}

	public record Exits(double sellToBuyRatio, double heldToResolutionShare, double mergeToBuyRatio,
			double redeemUsdc) {
	}

	public record EntryPrices(List<Bucket> buckets, double favouriteShare, double longshotShare,
			double avgEntryPrice) {
	}

	public record Expiry(List<Bucket> buckets, Double medianMinutes) {
	}

	public record Timing(long[] hourOfDay, long[] dayOfWeek, int activeHours, Double medianGapSeconds) {
	}
}
