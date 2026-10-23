package com.xray.analysis;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.xray.config.XrayProperties;
import com.xray.config.XrayProperties.Marker;
import com.xray.ingest.PnlRepository.DailyPnl;

/** The "Wallet" screen: lifetime P&L curve and month-by-month results. */
@Component
public class PnlAnalyzer {

	public record Point(long ts, double pnl, double fees, double volume) {
	}

	public record Month(String month, double pnl, double volume, double fees, double rebates, long trades,
			double profitPerDollar) {
	}

	public record Totals(double pnl, double realizedPnl, double unrealizedPnl, double volume, double fees,
			double makerRebate, double takerRebate, double rewards, long trades, double profitPerDollar, Long firstTs,
			Long lastTs) {
	}

	public record PnlReport(Totals totals, List<Point> series, List<Month> months, List<Marker> markers) {
	}

	private final List<Marker> markers;

	public PnlAnalyzer(XrayProperties properties) {
		this.markers = properties.markers() == null ? List.of() : properties.markers();
	}

	public PnlReport analyze(List<DailyPnl> series) {
		if (series.isEmpty()) {
			return new PnlReport(new Totals(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null), List.of(), List.of(), markers);
		}
		DailyPnl last = series.getLast();
		Totals totals = new Totals(last.economicPnl(), last.realizedPnl(), last.unrealizedPnl(), last.volumeUsdc(),
				Math.abs(last.fees()), last.makerRebate(), last.takerRebate(), last.rewardIncome(), last.tradeCount(),
				Stats.safeDiv(last.economicPnl(), last.volumeUsdc()), series.getFirst().ts(), last.ts());

		List<Point> points = series.stream()
			.map(p -> new Point(p.ts() * 1000, p.economicPnl(), Math.abs(p.fees()), p.volumeUsdc()))
			.toList();
		return new PnlReport(totals, points, months(DailyChange.of(series)), markers);
	}

	static List<Month> months(List<DailyChange> days) {
		Map<YearMonth, double[]> byMonth = new LinkedHashMap<>();
		for (DailyChange d : days) {
			double[] m = byMonth.computeIfAbsent(YearMonth.from(d.date()), k -> new double[5]);
			m[0] += d.pnl();
			m[1] += d.volume();
			m[2] += d.fees();
			m[3] += d.rebates();
			m[4] += d.trades();
		}
		List<Month> months = new ArrayList<>();
		byMonth.forEach((month, m) -> months
			.add(new Month(month.toString(), m[0], m[1], m[2], m[3], (long) m[4], Stats.safeDiv(m[0], m[1]))));
		return months;
	}
}
