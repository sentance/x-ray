package com.xray.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Response shapes of the public Polymarket Data and Gamma APIs (only the fields we use). */
public final class PolymarketDtos {

	private PolymarketDtos() {
	}

	public record Activity(String proxyWallet, long timestamp, String conditionId, String type, Double size,
			Double usdcSize, String transactionHash, Double price, String asset, String side, Integer outcomeIndex,
			String title, String slug, String eventSlug, String outcome) {
	}

	public record PnlSeries(Data data) {

		public record Data(List<PnlPoint> points) {
		}
	}

	public record PnlPoint(long timestamp, @JsonProperty("economic_pnl") Double economicPnl,
			@JsonProperty("realized_pnl") Double realizedPnl, @JsonProperty("unrealized_pnl") Double unrealizedPnl,
			@JsonProperty("trade_pnl") Double tradePnl, Double fees, @JsonProperty("maker_rebate") Double makerRebate,
			@JsonProperty("taker_rebate") Double takerRebate, @JsonProperty("reward_income") Double rewardIncome,
			@JsonProperty("volume_usdc") Double volumeUsdc, @JsonProperty("trade_count") Long tradeCount) {
	}

	public record Profile(String proxyWallet, String name, String pseudonym, String bio, String profileImage,
			String createdAt, Boolean verifiedBadge, String takerTierName) {
	}

	public record ProfileSearch(List<Profile> profiles) {
	}

	public record LeaderboardEntry(String rank, String proxyWallet, String userName, double vol, double pnl,
			String profileImage, boolean verifiedBadge) {
	}

	public record Position(String conditionId, String asset, String title, String slug, String outcome,
			Integer outcomeIndex, Double size, Double avgPrice, Double initialValue, Double currentValue,
			Double cashPnl, Double curPrice, Boolean redeemable, String endDate) {
	}

	public record GammaEvent(String slug, String title, String endDate, List<Tag> tags, List<GammaMarket> markets) {
	}

	public record Tag(String slug, String label) {
	}

	/** Gamma encodes {@code outcomes}, {@code outcomePrices} and {@code clobTokenIds} as JSON strings. */
	public record GammaMarket(String conditionId, String slug, String question, String endDate, Boolean closed,
			String outcomes, String outcomePrices, Boolean negRisk, String umaResolutionStatus) {
	}
}
