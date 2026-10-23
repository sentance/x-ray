package com.xray.analysis;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * The Binance test: does a crypto trader buy the side Binance spot has already moved toward (reacting to spot
 * before Polymarket's odds catch up), or the side spot moves to next (predicting it)?
 * <p>
 * Prices are 1-minute candle opens, so the trade's own minute is left out of every window: "before" windows end
 * at the open of the trade's minute, "after" windows start at the open of the next one. Moves within the same
 * minute, where the fastest bots act, are not visible at this resolution.
 */
@Component
public class BinanceLagAnalyzer {

	/**
	 * @param direction +1 if the buy bets on the price rising, -1 if on it falling
	 * @param shares outcome shares bought; weights are in shares, not dollars, because the side spot has moved
	 * toward costs more per share, so dollar weights would lean "aligned" even for a trader buying both sides evenly
	 * @param windowStartTs epoch seconds an Up/Down market's window opened (its strike is the price then), or null
	 */
	public record CryptoBuy(long ts, String symbol, int direction, double shares, Long windowStartTs) {
	}

	/** Opening price of the 1-minute candle at {@code minuteMs}, or null if unknown. */
	public interface Prices {

		Double openAt(String symbol, long minuteMs);
	}

	public enum When {
		BEFORE, SINCE_OPEN, AFTER
	}

	/**
	 * @param alignedShare share of buys whose bet direction matches the spot move in this window
	 * @param pValue chance a coin-flipper lines up at least this often
	 * @param sharesAligned the same, weighted by shares bought
	 * @param sharesPValue chance of a weighted alignment this high if each buy's side were a coin flip
	 * @param meanSignedMoveBps average spot move in the bet's direction, in basis points
	 */
	public record Window(String label, When when, int minutes, long trades, long aligned, double alignedShare,
			double pValue, double sharesAligned, double sharesPValue, double meanSignedMoveBps) {

		/** Lines up with spot clearly more often than chance, by count or by shares. */
		boolean beats(double share) {
			return (alignedShare >= share && pValue < SIGNIFICANT) || (sharesAligned >= share && sharesPValue < SIGNIFICANT);
		}

		/** Whichever measure makes the stronger case, for the explanation. */
		String strongest() {
			return sharesPValue < pValue
					? "%s of the shares it buys (%s of its %,d buys)".formatted(pct(sharesAligned), pct(alignedShare),
							trades)
					: "%s of %,d buys".formatted(pct(alignedShare), trades);
		}
	}

	public record BinanceReport(long cryptoBuys, long directionalBuys, List<String> symbols, List<Window> windows,
			String verdict, String explanation, Long fromTs, Long toTs) {
	}

	/** Fewer matched buys than this and the test says nothing. */
	static final int MIN_TRADES = 30;

	static final double LAG_SHARE = 0.6;

	static final double LEAD_SHARE = 0.58;

	static final double SIGNIFICANT = 0.01;

	private static final long MINUTE_MS = 60_000;

	/**
	 * @param cryptoBuys every crypto buy with a recognisable underlying, including ones with no up/down direction
	 * (ranges), which only count towards {@link BinanceReport#cryptoBuys()}
	 */
	public BinanceReport analyze(List<CryptoBuy> cryptoBuys, Prices prices) {
		List<CryptoBuy> buys = cryptoBuys.stream().filter(b -> b.direction() != 0).toList();
		List<Window> windows = new ArrayList<>();
		for (int m : new int[] { 15, 5, 1 }) {
			windows.add(window("%d min before".formatted(m), When.BEFORE, m, buys, prices));
		}
		windows.add(window("Since the window opened", When.SINCE_OPEN, 0, buys, prices));
		for (int m : new int[] { 1, 5, 15 }) {
			windows.add(window("%d min after".formatted(m), When.AFTER, m, buys, prices));
		}

		Window sinceOpen = windows.get(3);
		Window lead = sinceOpen.trades() >= MIN_TRADES ? sinceOpen : windows.get(2);
		Window after5 = windows.get(5);
		String verdict;
		String explanation;
		if (lead.trades() < MIN_TRADES) {
			verdict = "Not enough data";
			explanation = "Only %,d directional crypto buys could be matched to Binance prices; the test needs %d."
				.formatted(lead.trades(), MIN_TRADES);
		}
		else if (lead.beats(LAG_SHARE)) {
			verdict = "Trades after spot moves";
			explanation = ("Puts %s on the side Binance spot has already moved toward (%s; a coin flip gets 50%%). "
					+ "It reacts to spot before Polymarket's odds catch up.")
				.formatted(lead.strongest(), lead.label().toLowerCase());
		}
		else if (after5.trades() >= MIN_TRADES && after5.beats(LEAD_SHARE)) {
			verdict = "Ahead of spot";
			explanation = "Puts %s on the side spot moves toward over the next 5 minutes: it predicts moves rather than following them."
				.formatted(after5.strongest());
		}
		else {
			verdict = "No lag pattern";
			explanation = ("Its buys line up with spot's prior move in %s of %,d buys and %s of shares (%s), close to "
					+ "a coin flip, so it is not simply following Binance.")
				.formatted(pct(lead.alignedShare()), lead.trades(), pct(lead.sharesAligned()), lead.label().toLowerCase());
		}
		return new BinanceReport(cryptoBuys.size(), buys.size(),
				buys.stream().map(CryptoBuy::symbol).distinct().sorted().toList(), windows, verdict, explanation,
				buys.stream().map(CryptoBuy::ts).min(Long::compare).orElse(null),
				buys.stream().map(CryptoBuy::ts).max(Long::compare).orElse(null));
	}

	private static Window window(String label, When when, int minutes, List<CryptoBuy> buys, Prices prices) {
		long n = 0;
		long aligned = 0;
		double shares = 0;
		double alignedShares = 0;
		double sumSquares = 0;
		double signedMoveSum = 0;
		for (CryptoBuy b : buys) {
			Double move = move(b, when, minutes, prices);
			if (move == null || move == 0) {
				continue;
			}
			double signed = b.direction() * move;
			n++;
			shares += b.shares();
			sumSquares += b.shares() * b.shares();
			signedMoveSum += signed;
			if (signed > 0) {
				aligned++;
				alignedShares += b.shares();
			}
		}
		// Weighted sign test: under random sides, sum(w * ±1) has mean 0 and variance sum(w²).
		double weightedSum = 2 * alignedShares - shares;
		double sharesP = sumSquares > 0 ? Stats.normalUpperTail(weightedSum / Math.sqrt(sumSquares)) : 1;
		return new Window(label, when, minutes, n, aligned, Stats.safeDiv(aligned, n),
				Stats.binomialUpperTail((int) n, (int) aligned), Stats.safeDiv(alignedShares, shares), sharesP,
				n == 0 ? 0 : signedMoveSum / n * 10_000);
	}

	/** Relative spot move over the window, or null if a price is missing or the window doesn't apply. */
	static Double move(CryptoBuy b, When when, int minutes, Prices prices) {
		long minute = Math.floorDiv(b.ts() * 1000, MINUTE_MS) * MINUTE_MS;
		long from;
		long to;
		switch (when) {
			case BEFORE -> {
				from = minute - minutes * MINUTE_MS;
				to = minute;
			}
			case AFTER -> {
				from = minute + MINUTE_MS;
				to = from + minutes * MINUTE_MS;
			}
			default -> {
				if (b.windowStartTs() == null) {
					return null;
				}
				from = Math.floorDiv(b.windowStartTs() * 1000, MINUTE_MS) * MINUTE_MS;
				to = minute;
				if (from >= to) {
					return null;
				}
			}
		}
		Double p0 = prices.openAt(b.symbol(), from);
		Double p1 = prices.openAt(b.symbol(), to);
		if (p0 == null || p1 == null || p0 == 0) {
			return null;
		}
		return p1 / p0 - 1;
	}

	private static String pct(double v) {
		return "%.0f%%".formatted(v * 100);
	}
}
