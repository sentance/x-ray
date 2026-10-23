package com.xray.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.xray.analysis.PnlAnalyzer.Totals;
import com.xray.analysis.StrategyProfile.Share;

/** Turns a {@link StrategyProfile} into human-readable strategy labels and a one-sentence summary. */
@Component
public class StrategyClassifier {

	public enum Kind {
		/** What the trader is doing to make money; the strongest one leads the summary. */
		STRATEGY,
		/** How they do it (automation, holding style). */
		TRAIT
	}

	public record Label(String name, Kind kind, double confidence, List<String> evidence) {
	}

	public record Classification(String summary, List<Label> labels) {
	}

	public Classification classify(StrategyProfile p, Totals totals) {
		List<Label> labels = new ArrayList<>();
		var both = p.bothSides();
		var exits = p.exits();
		var entry = p.entryPrices();
		Double expiryMinutes = p.expiry().medianMinutes();
		double crypto = share(p.categories(), "Crypto");
		double sports = share(p.categories(), "Sports");
		double politics = share(p.categories(), "Politics");
		double shortUpDown = share(p.marketTypes(), MarketTypes.CRYPTO_UP_DOWN_SHORT)
				+ share(p.marketTypes(), MarketTypes.CRYPTO_UP_DOWN_HOURLY);
		double ladder = share(p.marketTypes(), MarketTypes.CRYPTO_ABOVE) + share(p.marketTypes(), MarketTypes.CRYPTO_RANGE)
				+ share(p.marketTypes(), MarketTypes.CRYPTO_HIT);
		double makerRebate = totals.makerRebate();
		boolean maker = makerRebate > 1000 && makerRebate > 0.05 * Math.max(totals.pnl(), 0);

		if (both.shareOfVolume() >= 0.4 && both.avgPairCost() != null && both.avgPairCost() < 1) {
			labels.add(new Label("Pair arbitrage", Kind.STRATEGY, Math.min(1, both.shareOfVolume() + 0.2), List.of(
					"Buys both outcomes in %s of its volume".formatted(pct(both.shareOfVolume())),
					"A Yes+No pair costs it $%.3f on average, below the $1 payout".formatted(both.avgPairCost()),
					"%s of two-sided markets were bought below $1".formatted(pct(both.pairsBelowOneShare())))));
		}
		if (maker && exits.sellToBuyRatio() >= 0.3) {
			labels.add(new Label("Market maker", Kind.STRATEGY, Math.min(1, 0.5 + exits.sellToBuyRatio() / 2),
					List.of("Earned %s in maker rebates".formatted(money(makerRebate)),
							"Sells %s of what it buys".formatted(pct(exits.sellToBuyRatio())),
							"%.0f trades per active day".formatted(p.activity().tradesPerActiveDay()))));
		}
		if (entry.favouriteShare() >= 0.5) {
			boolean sniper = expiryMinutes != null && expiryMinutes <= 60;
			labels.add(new Label(sniper ? "Near-expiry sniper" : "Favourite grinder", Kind.STRATEGY,
					entry.favouriteShare(), sniper
							? List.of("%s of buy volume is at prices ≥ 0.90".formatted(pct(entry.favouriteShare())),
									"Median buy is placed %s before the market ends".formatted(minutes(expiryMinutes)))
							: List.of("%s of buy volume is at prices ≥ 0.90".formatted(pct(entry.favouriteShare())),
									"Collects small, likely wins on heavy favourites")));
		}
		if (entry.longshotShare() >= 0.3) {
			labels.add(new Label("Longshot hunter", Kind.STRATEGY, Math.min(1, entry.longshotShare() + 0.3),
					List.of("%s of buy volume is at prices below 0.10".formatted(pct(entry.longshotShare())))));
		}
		if (shortUpDown >= 0.4) {
			labels.add(new Label("Crypto latency trader", Kind.STRATEGY, Math.min(1, shortUpDown + 0.2), List.of(
					"%s of volume is in short-dated crypto Up/Down markets".formatted(pct(shortUpDown)),
					"Run the Binance test to see whether it buys after spot moves")));
		}
		if (crypto >= 0.6 && ladder >= 0.5) {
			labels.add(new Label("Crypto price-ladder trader", Kind.STRATEGY, ladder, List.of(
					"%s of volume is in crypto strike, range and hit-price markets".formatted(pct(ladder)),
					"Most traded asset: " + p.cryptoAssets().stream().findFirst().map(Share::name).orElse("n/a"))));
		}
		if (sports >= 0.6) {
			labels.add(new Label("Sports bettor", Kind.STRATEGY, sports,
					List.of("%s of volume is in sports markets".formatted(pct(sports)))));
		}
		if (politics >= 0.5) {
			labels.add(new Label("Politics/event trader", Kind.STRATEGY, politics,
					List.of("%s of volume is in politics markets".formatted(pct(politics)))));
		}
		if (exits.sellToBuyRatio() >= 0.6 && !maker) {
			labels.add(new Label("Swing trader", Kind.STRATEGY, exits.sellToBuyRatio() / 1.5, List.of(
					"Sells %s of what it buys before resolution".formatted(pct(exits.sellToBuyRatio())))));
		}

		var timing = p.timing();
		if (p.activity().tradesPerActiveDay() >= 200 || timing.activeHours() >= 20) {
			labels.add(new Label("Automated (bot-like)", Kind.TRAIT, 0.9, List.of(
					"%.0f trades per active day".formatted(p.activity().tradesPerActiveDay()),
					"Active in %d of 24 hours".formatted(timing.activeHours()))));
		}
		if (exits.heldToResolutionShare() >= 0.8) {
			labels.add(new Label("Holds to resolution", Kind.TRAIT, exits.heldToResolutionShare(), List.of(
					"%s of buy volume is never sold".formatted(pct(exits.heldToResolutionShare())))));
		}

		labels.sort(Comparator.comparing(Label::kind).thenComparing(Comparator.comparingDouble(Label::confidence).reversed()));
		return new Classification(summary(p, labels), labels);
	}

	private static String summary(StrategyProfile p, List<Label> labels) {
		boolean bot = labels.stream().anyMatch(l -> l.name().startsWith("Automated"));
		Optional<Label> primary = labels.stream().filter(l -> l.kind() == Kind.STRATEGY).findFirst();
		String who = bot ? "A bot" : "A trader";
		String what = primary.map(l -> "best described as a " + l.name().toLowerCase())
			.orElse("with no single dominant pattern");
		String where = p.marketTypes()
			.stream()
			.findFirst()
			.map(s -> ", mostly in %s markets (%s of volume)".formatted(s.name(), pct(s.share())))
			.orElse("");
		String exits = p.exits().heldToResolutionShare() >= 0.8 ? "holds positions to resolution"
				: p.exits().sellToBuyRatio() >= 0.5 ? "sells most positions before resolution"
						: "sometimes sells before resolution";
		return "%s %s%s; it %s, averaging %.0f trades per active day at a median $%,.0f per trade.".formatted(who,
				what, where, exits, p.activity().tradesPerActiveDay(), p.tradeSizes().p50());
	}

	private static double share(List<Share> shares, String name) {
		return shares.stream().filter(s -> s.name().equals(name)).mapToDouble(Share::share).sum();
	}

	private static String pct(double v) {
		return "%.0f%%".formatted(v * 100);
	}

	private static String money(double v) {
		return "$%,.0f".formatted(v);
	}

	private static String minutes(double m) {
		return m < 120 ? "%.0f min".formatted(m) : m < 2880 ? "%.1f h".formatted(m / 60) : "%.0f days".formatted(m / 1440);
	}
}
