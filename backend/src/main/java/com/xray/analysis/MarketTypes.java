package com.xray.analysis;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.xray.market.MarketCategory;

/**
 * Recognises the recurring Polymarket market formats from their slugs, e.g. {@code btc-updown-15m-…},
 * {@code ethereum-above-2400-on-october-23}, {@code will-solana-dip-to-100-on-october-23}.
 */
public final class MarketTypes {

	public static final String CRYPTO_UP_DOWN_SHORT = "Crypto Up/Down (≤1h)";

	public static final String CRYPTO_UP_DOWN_HOURLY = "Crypto Up/Down (hourly)";

	public static final String CRYPTO_UP_DOWN_DAILY = "Crypto Up/Down (daily+)";

	public static final String CRYPTO_ABOVE = "Crypto above-strike";

	public static final String CRYPTO_RANGE = "Crypto price range";

	public static final String CRYPTO_HIT = "Crypto hit-price";

	public static final String SPORTS_SPREAD_TOTAL = "Sports spread/total";

	public static final String SPORTS_MATCH = "Sports match/outright";

	private static final Pattern SHORT_UP_DOWN = Pattern.compile("updown-(5m|15m|30m|1h)|up-or-down-(5|15)-?m");

	private static final Pattern HOURLY_UP_DOWN = Pattern.compile("up-or-down-.*-\\d{1,2}(am|pm)");

	private static final Pattern UP_DOWN = Pattern.compile("updown|up-or-down");

	private static final Pattern RANGE = Pattern.compile("be-between|price-of-.*-be-");

	private static final Pattern ABOVE = Pattern.compile("-above-|-be-above-");

	private static final Pattern HIT = Pattern.compile("-reach-|-dip-to-|-hit-");

	private static final Pattern SPREAD_TOTAL = Pattern.compile("spread|total|-o-?u-|over-under");

	private static final List<Map.Entry<Pattern, String>> BINANCE_SYMBOLS = List.of(
			Map.entry(Pattern.compile("(^|-)(btc|bitcoin)(-|$)"), "BTCUSDT"),
			Map.entry(Pattern.compile("(^|-)(eth|ethereum)(-|$)"), "ETHUSDT"),
			Map.entry(Pattern.compile("(^|-)(sol|solana)(-|$)"), "SOLUSDT"),
			Map.entry(Pattern.compile("(^|-)xrp(-|$)"), "XRPUSDT"),
			Map.entry(Pattern.compile("(^|-)(doge|dogecoin)(-|$)"), "DOGEUSDT"),
			Map.entry(Pattern.compile("(^|-)bnb(-|$)"), "BNBUSDT"));

	private MarketTypes() {
	}

	public static String of(String slug, String category) {
		String s = slug == null ? "" : slug.toLowerCase(Locale.ROOT);
		if (MarketCategory.CRYPTO.equals(category) || binanceSymbol(s).isPresent()) {
			if (SHORT_UP_DOWN.matcher(s).find()) {
				return CRYPTO_UP_DOWN_SHORT;
			}
			if (HOURLY_UP_DOWN.matcher(s).find()) {
				return CRYPTO_UP_DOWN_HOURLY;
			}
			if (UP_DOWN.matcher(s).find()) {
				return CRYPTO_UP_DOWN_DAILY;
			}
			if (RANGE.matcher(s).find()) {
				return CRYPTO_RANGE;
			}
			if (ABOVE.matcher(s).find()) {
				return CRYPTO_ABOVE;
			}
			if (HIT.matcher(s).find()) {
				return CRYPTO_HIT;
			}
			if (MarketCategory.CRYPTO.equals(category)) {
				return "Crypto other";
			}
		}
		if (MarketCategory.SPORTS.equals(category)) {
			return SPREAD_TOTAL.matcher(s).find() ? SPORTS_SPREAD_TOTAL : SPORTS_MATCH;
		}
		return category == null ? MarketCategory.OTHER : category;
	}

	/** The Binance spot symbol of a crypto market's underlying asset, if recognisable. */
	public static Optional<String> binanceSymbol(String slug) {
		String s = slug == null ? "" : slug.toLowerCase(Locale.ROOT);
		return BINANCE_SYMBOLS.stream().filter(e -> e.getKey().matcher(s).find()).map(Map.Entry::getValue).findFirst();
	}

	/**
	 * Whether buying this outcome is a bet on the price going up (+1), down (-1), or neither (0, e.g. a range).
	 */
	public static int direction(String slug, String outcome) {
		String s = slug == null ? "" : slug.toLowerCase(Locale.ROOT);
		String o = outcome == null ? "" : outcome.toLowerCase(Locale.ROOT);
		if (UP_DOWN.matcher(s).find()) {
			return switch (o) {
				case "up" -> 1;
				case "down" -> -1;
				default -> 0;
			};
		}
		if (RANGE.matcher(s).find()) {
			return 0;
		}
		int yes = switch (o) {
			case "yes" -> 1;
			case "no" -> -1;
			default -> 0;
		};
		if (s.contains("-dip-to-")) {
			return -yes;
		}
		if (ABOVE.matcher(s).find() || s.contains("-reach-") || s.contains("-hit-")) {
			return yes;
		}
		return 0;
	}
}
