package com.xray.market;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Maps Polymarket tags (or, failing that, slugs) to a coarse market category. */
public final class MarketCategory {

	public static final String CRYPTO = "Crypto";

	public static final String SPORTS = "Sports";

	public static final String POLITICS = "Politics";

	public static final String OTHER = "Other";

	// Checked in order: a crypto market tagged "business" is still crypto.
	private static final List<Map.Entry<String, List<String>>> TAG_RULES = List.of(
			Map.entry(CRYPTO, List.of("crypto", "crypto-prices", "bitcoin", "ethereum", "solana", "xrp")),
			Map.entry(SPORTS, List.of("sports", "nba", "nfl", "mlb", "nhl", "soccer", "tennis", "esports", "ufc",
					"golf", "f1", "cricket", "games")),
			Map.entry(POLITICS, List.of("politics", "elections", "us-politics", "geopolitics", "world-elections",
					"trump")),
			Map.entry("Economy", List.of("economy", "finance", "fed", "business", "stocks", "inflation",
					"fed-rates", "earnings")),
			Map.entry("Tech", List.of("tech", "ai", "science")),
			Map.entry("Culture", List.of("culture", "pop-culture", "entertainment", "movies", "music", "awards",
					"celebrities", "mentions")),
			Map.entry("Weather", List.of("weather", "climate")));

	private static final Pattern CRYPTO_SLUG = Pattern
		.compile("(^|-)(btc|bitcoin|eth|ethereum|sol|solana|xrp|doge|dogecoin|bnb|hype|crypto)(-|$)");

	private static final Pattern SPORTS_SLUG = Pattern
		.compile("^(nba|nfl|mlb|nhl|epl|ucl|fifwc|fif|lal|sea|bun|ufc|atp|wta|cbb|cfb|mls|ipl|lol|cs2|dota2|val)-");

	private MarketCategory() {
	}

	public static String fromTags(Collection<String> tags, String fallbackSlug) {
		for (var rule : TAG_RULES) {
			if (rule.getValue().stream().anyMatch(tags::contains)) {
				return rule.getKey();
			}
		}
		return fromSlug(fallbackSlug);
	}

	public static String fromSlug(String slug) {
		if (slug == null) {
			return OTHER;
		}
		String s = slug.toLowerCase(Locale.ROOT);
		if (CRYPTO_SLUG.matcher(s).find()) {
			return CRYPTO;
		}
		if (SPORTS_SLUG.matcher(s).find()) {
			return SPORTS;
		}
		return OTHER;
	}
}
