package com.xray.analysis;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.xray.ingest.PnlRepository.DailyPnl;

/** Day-over-day change of the cumulative P&L series. Fees are reported as a positive amount paid. */
public record DailyChange(long ts, LocalDate date, double pnl, double volume, double fees, double rebates,
		long trades) {

	public boolean active() {
		return trades > 0 || Math.abs(pnl) > 0.01;
	}

	public static List<DailyChange> of(List<DailyPnl> series) {
		List<DailyChange> days = new ArrayList<>(series.size());
		DailyPnl prev = null;
		for (DailyPnl p : series) {
			double pnl = p.economicPnl() - (prev == null ? 0 : prev.economicPnl());
			double volume = p.volumeUsdc() - (prev == null ? 0 : prev.volumeUsdc());
			double fees = Math.abs(p.fees()) - (prev == null ? 0 : Math.abs(prev.fees()));
			double rebates = rebates(p) - (prev == null ? 0 : rebates(prev));
			long trades = p.tradeCount() - (prev == null ? 0 : prev.tradeCount());
			days.add(new DailyChange(p.ts(), Instant.ofEpochSecond(p.ts()).atZone(ZoneOffset.UTC).toLocalDate(), pnl,
					volume, fees, rebates, trades));
			prev = p;
		}
		return days;
	}

	private static double rebates(DailyPnl p) {
		return p.makerRebate() + p.takerRebate();
	}
}
