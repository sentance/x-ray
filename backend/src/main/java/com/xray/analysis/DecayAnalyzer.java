package com.xray.analysis;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** The "Still working?" screen: recent windows against the lifetime record. */
@Component
public class DecayAnalyzer {

	public record Window(String label, double pnl, double volume, double profitPerDollar, double greenDayPct,
			double feeShare, long trades, int activeDays) {
	}

	public record MonthlyEdge(String month, double profitPerDollar, double feeShare) {
	}

	public record DecayReport(List<Window> windows, List<MonthlyEdge> monthly, String verdict, String explanation) {
	}

	private static final long DAY = 86_400;

	public DecayReport analyze(List<DailyChange> days) {
		if (days.isEmpty()) {
			return new DecayReport(List.of(), List.of(), "No data", "The wallet has no P&L history.");
		}
		long lastTs = days.getLast().ts();
		List<Window> windows = new ArrayList<>();
		for (int n : new int[] { 30, 60, 90 }) {
			long from = lastTs - n * DAY;
			windows.add(window("Last " + n + " days", days.stream().filter(d -> d.ts() > from).toList()));
		}
		Window lifetime = window("Lifetime", days);
		windows.add(lifetime);

		List<MonthlyEdge> monthly = PnlAnalyzer.months(days)
			.stream()
			.map(m -> new MonthlyEdge(m.month(), m.profitPerDollar(), feeShare(m.pnl(), m.fees())))
			.toList();

		Window recent = windows.getFirst();
		String verdict;
		String explanation;
		if (lifetime.pnl() <= 0) {
			verdict = "Never worked";
			explanation = "The wallet is not profitable over its lifetime.";
		}
		else if (recent.trades() == 0) {
			verdict = "Inactive";
			explanation = "No trades in the last 30 days.";
		}
		else if (recent.pnl() <= 0) {
			verdict = "Stopped working";
			explanation = "Lost %s over the last 30 days despite a profitable lifetime record."
				.formatted(money(-recent.pnl()));
		}
		else {
			double ratio = recent.profitPerDollar() / lifetime.profitPerDollar();
			verdict = ratio >= 0.75 ? "Still working" : ratio >= 0.3 ? "Weakening" : "Mostly gone";
			explanation = "Earns %.2f¢ per $1 traded over the last 30 days vs %.2f¢ lifetime."
				.formatted(recent.profitPerDollar() * 100, lifetime.profitPerDollar() * 100);
		}
		return new DecayReport(windows, monthly, verdict, explanation);
	}

	private static Window window(String label, List<DailyChange> days) {
		double pnl = days.stream().mapToDouble(DailyChange::pnl).sum();
		double volume = days.stream().mapToDouble(DailyChange::volume).sum();
		double fees = days.stream().mapToDouble(DailyChange::fees).sum();
		long trades = days.stream().mapToLong(DailyChange::trades).sum();
		List<DailyChange> active = days.stream().filter(DailyChange::active).toList();
		long green = active.stream().filter(d -> d.pnl() > 0).count();
		return new Window(label, pnl, volume, Stats.safeDiv(pnl, volume), Stats.safeDiv(green, active.size()),
				feeShare(pnl, fees), trades, active.size());
	}

	/** Share of gross profit (before fees) that fees took. */
	static double feeShare(double netPnl, double fees) {
		double gross = netPnl + fees;
		return gross > 0 ? fees / gross : 0;
	}

	private static String money(double v) {
		return "$%,.0f".formatted(v);
	}
}
