package com.xray.leaderboard;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.xray.analysis.StrategyClassifier.Classification;
import com.xray.analysis.StrategyClassifier.Label;
import com.xray.client.PolymarketClient;
import com.xray.client.PolymarketDtos.LeaderboardEntry;
import com.xray.leaderboard.TagRepository.QuickTag;

/** Polymarket's leaderboard, with each wallet tagged by the strategy it appears to run. */
@Service
public class LeaderboardService {

	public enum Period {
		DAY, WEEK, MONTH, ALL
	}

	public enum OrderBy {
		PNL, VOL
	}

	/** Categories the leaderboard endpoint accepts. */
	public static final Set<String> CATEGORIES = Set.of("OVERALL", "POLITICS", "SPORTS", "CRYPTO", "CULTURE",
			"MENTIONS", "WEATHER", "ECONOMICS", "TECH", "FINANCE");

	public enum TagSource {
		/** From the wallet's full synced history. */
		FULL,
		/** From a sample of its most recent activity. */
		QUICK
	}

	public record Tag(TagSource source, String summary, List<Label> labels, Integer sampleTrades, Long sampleFromTs,
			Long sampleToTs) {
	}

	/** {@code tag} is null while the wallet waits to be tagged, or if tagging it failed. */
	public record Row(int rank, String address, String name, String profileImage, boolean verified, double pnl,
			double volume, Tag tag, boolean tagPending) {
	}

	public record Board(List<Row> rows, int pending) {
	}

	static final int SIZE = 50;

	private static final Duration ROWS_TTL = Duration.ofMinutes(5);

	private static final Duration QUICK_TAG_MAX_AGE = Duration.ofHours(12);

	private record CachedRows(List<LeaderboardEntry> entries, Instant fetchedAt) {
	}

	private final PolymarketClient polymarket;

	private final TagRepository tags;

	private final QuickTagger tagger;

	private final Map<String, CachedRows> rows = new ConcurrentHashMap<>();

	public LeaderboardService(PolymarketClient polymarket, TagRepository tags, QuickTagger tagger) {
		this.polymarket = polymarket;
		this.tags = tags;
		this.tagger = tagger;
	}

	/** Rows come back at once; untagged wallets are queued and show up tagged on a later call. */
	public Board board(Period period, String category, OrderBy orderBy) {
		List<LeaderboardEntry> entries = entries(period, category, orderBy);
		List<String> addresses = entries.stream().map(e -> e.proxyWallet().toLowerCase(Locale.ROOT)).toList();
		Map<String, Classification> full = tags.fullClassifications(addresses);
		Map<String, QuickTag> quick = tags.quickTags(addresses, QUICK_TAG_MAX_AGE);

		int pending = 0;
		List<Row> result = new ArrayList<>(entries.size());
		for (int i = 0; i < entries.size(); i++) {
			LeaderboardEntry e = entries.get(i);
			String address = addresses.get(i);
			Tag tag = null;
			boolean tagPending = false;
			if (full.containsKey(address)) {
				Classification c = full.get(address);
				tag = new Tag(TagSource.FULL, c.summary(), c.labels(), null, null, null);
			}
			else if (quick.containsKey(address)) {
				QuickTag q = quick.get(address);
				tag = new Tag(TagSource.QUICK, q.summary(), q.labels(), q.sampleTrades(), q.sampleFromTs(),
						q.sampleToTs());
			}
			else {
				tagPending = tagger.enqueue(address);
				if (tagPending) {
					pending++;
				}
			}
			result.add(new Row(parseRank(e.rank(), i + 1), address, e.userName(), blankToNull(e.profileImage()),
					e.verifiedBadge(), e.pnl(), e.vol(), tag, tagPending));
		}
		return new Board(result, pending);
	}

	private List<LeaderboardEntry> entries(Period period, String category, OrderBy orderBy) {
		String key = period + "/" + category + "/" + orderBy;
		CachedRows cached = rows.get(key);
		if (cached != null && cached.fetchedAt().plus(ROWS_TTL).isAfter(Instant.now())) {
			return cached.entries();
		}
		List<LeaderboardEntry> fresh = polymarket.leaderboard(period.name(), category, orderBy.name(), SIZE, 0);
		fresh = fresh == null ? List.of() : fresh;
		rows.put(key, new CachedRows(fresh, Instant.now()));
		return fresh;
	}

	private static int parseRank(String rank, int fallback) {
		try {
			return Integer.parseInt(rank);
		}
		catch (NumberFormatException | NullPointerException e) {
			return fallback;
		}
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s;
	}
}
