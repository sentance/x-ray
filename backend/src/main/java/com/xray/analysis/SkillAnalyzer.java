package com.xray.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/** The "Skill or luck" screen: would a coin-flipper plausibly produce this record? */
@Component
public class SkillAnalyzer {

	public record SkillReport(int activeDays, int greenDays, double greenDayPct, double greenDaysPValue,
			int longestWinStreak, double streakCoinFlipProbability, double meanDailyPnl, double dailyStdDev,
			double sharpeAnnualized, double tStat, double tStatPValue, double maxDrawdown, double maxDrawdownPct,
			double pnlWithoutBest5Days, Double top5MarketShare, int marketsTraded, double marketWinRate,
			String verdict, List<String> reasons) {
	}

	private static final int MIN_DAYS = 20;

	/**
	 * @param marketPnl estimated P&L of each market the wallet traded, used to see whether a handful of markets
	 * carry the whole record
	 */
	public SkillReport analyze(List<DailyChange> allDays, List<Double> marketPnl) {
		List<DailyChange> days = allDays.stream().filter(DailyChange::active).toList();
		List<Double> pnls = days.stream().map(DailyChange::pnl).toList();
		int n = days.size();
		int green = (int) pnls.stream().filter(p -> p > 0).count();

		int longest = 0;
		int current = 0;
		for (double p : pnls) {
			current = p > 0 ? current + 1 : 0;
			longest = Math.max(longest, current);
		}

		double mean = Stats.mean(pnls);
		double sd = Stats.stdDev(pnls);
		double tStat = sd == 0 ? 0 : mean / (sd / Math.sqrt(n));

		double peak = 0;
		double cumulative = 0;
		double maxDrawdown = 0;
		double peakAtMaxDrawdown = 0;
		for (DailyChange d : allDays) {
			cumulative += d.pnl();
			peak = Math.max(peak, cumulative);
			if (peak - cumulative > maxDrawdown) {
				maxDrawdown = peak - cumulative;
				peakAtMaxDrawdown = peak;
			}
		}

		double total = pnls.stream().mapToDouble(Double::doubleValue).sum();
		double best5 = pnls.stream().sorted(Comparator.reverseOrder()).limit(5).mapToDouble(Double::doubleValue).sum();

		double marketTotal = marketPnl.stream().mapToDouble(Double::doubleValue).sum();
		double top5Markets = marketPnl.stream()
			.sorted(Comparator.reverseOrder())
			.limit(5)
			.mapToDouble(Double::doubleValue)
			.sum();
		Double top5Share = marketTotal > 0 ? top5Markets / marketTotal : null;
		long marketsWon = marketPnl.stream().filter(p -> p > 0).count();

		double greenP = Stats.binomialUpperTail(n, green);
		double tStatP = Stats.normalUpperTail(tStat);
		List<String> reasons = new ArrayList<>();
		String verdict = verdict(n, total, total - best5, top5Share, greenP, tStat, tStatP, reasons);
		return new SkillReport(n, green, Stats.safeDiv(green, n), greenP, longest, Stats.longestRunAtLeast(n, longest),
				mean, sd, sd == 0 ? 0 : mean / sd * Math.sqrt(365), tStat, tStatP, maxDrawdown,
				Stats.safeDiv(maxDrawdown, peakAtMaxDrawdown), total - best5, top5Share, marketPnl.size(),
				Stats.safeDiv(marketsWon, marketPnl.size()), verdict, reasons);
	}

	private static String verdict(int activeDays, double total, double withoutBest5, Double top5Share, double greenP,
			double tStat, double tStatP, List<String> reasons) {
		if (activeDays < MIN_DAYS) {
			reasons.add("Only %d active days; at least %d are needed to judge.".formatted(activeDays, MIN_DAYS));
			return "Too little history";
		}
		if (total <= 0) {
			reasons.add("The wallet has lost money overall.");
			return "No edge";
		}
		boolean concentrated = top5Share != null && top5Share > 0.8;
		if (withoutBest5 <= 0 || concentrated) {
			if (withoutBest5 <= 0) {
				reasons.add("Without its 5 best days the wallet is not profitable.");
			}
			if (concentrated) {
				reasons.add("%.0f%% of profit came from just 5 markets.".formatted(top5Share * 100));
			}
			return "Looks like luck";
		}
		if (greenP < 0.01 && tStat > 3) {
			reasons.add("A coin-flipper would have this many green days with probability %s."
				.formatted(formatP(greenP)));
			reasons.add("Average daily P&L is %.1f standard errors above zero.".formatted(tStat));
			return "Skill";
		}
		if (tStatP < 0.05) {
			reasons.add("Daily results are positive with p = %s, but the record is not overwhelming."
				.formatted(formatP(tStatP)));
			return "Probably skill";
		}
		reasons.add("Profitable, but the daily results are too noisy to rule out luck (p = %s)."
			.formatted(formatP(tStatP)));
		return "Can't tell yet";
	}

	static String formatP(double p) {
		return p < 1e-6 ? "< 1 in a million" : p < 0.001 ? "%.1e".formatted(p) : "%.3f".formatted(p);
	}
}
