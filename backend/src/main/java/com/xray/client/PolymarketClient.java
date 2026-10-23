package com.xray.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import com.xray.client.PolymarketDtos.Activity;
import com.xray.client.PolymarketDtos.GammaEvent;
import com.xray.client.PolymarketDtos.LeaderboardEntry;
import com.xray.client.PolymarketDtos.PnlPoint;
import com.xray.client.PolymarketDtos.PnlSeries;
import com.xray.client.PolymarketDtos.Position;
import com.xray.client.PolymarketDtos.Profile;
import com.xray.client.PolymarketDtos.ProfileSearch;
import com.xray.config.XrayProperties;

/** Read-only client for Polymarket's public Data API and Gamma API. */
@Component
public class PolymarketClient {

	/** The activity endpoint rejects offsets above this value. */
	public static final int MAX_ACTIVITY_OFFSET = 5000;

	public static final int ACTIVITY_PAGE_SIZE = 500;

	private final RestClient data;

	private final RestClient gamma;

	private final RateLimitedCaller caller;

	public PolymarketClient(RestClient.Builder builder, XrayProperties properties) {
		this.data = builder.clone().baseUrl(properties.polymarket().dataApi()).build();
		this.gamma = builder.clone().baseUrl(properties.polymarket().gammaApi()).build();
		this.caller = new RateLimitedCaller(properties.polymarket().requestsPerSecond(), Duration.ofSeconds(1));
	}

	/** Activity in ascending time order starting at {@code start} (inclusive, epoch seconds). */
	public List<Activity> activityAscending(String address, long start, int offset) {
		return caller.call("activity " + address, () -> data.get()
			.uri(u -> u.path("/activity")
				.queryParam("user", address)
				.queryParam("limit", ACTIVITY_PAGE_SIZE)
				.queryParam("offset", offset)
				.queryParam("start", start)
				.queryParam("sortDirection", "ASC")
				.build())
			.retrieve()
			.body(new ParameterizedTypeReference<List<Activity>>() {
			}));
	}

	/** Most recent trades, newest first. Used for quick profiles without a full sync. */
	public List<Activity> recentTrades(String address, int limit, int offset) {
		return caller.call("activity " + address, () -> data.get()
			.uri(u -> u.path("/activity")
				.queryParam("user", address)
				.queryParam("type", "TRADE")
				.queryParam("limit", limit)
				.queryParam("offset", offset)
				.build())
			.retrieve()
			.body(new ParameterizedTypeReference<List<Activity>>() {
			}));
	}

	/** Daily cumulative P&L, fees and volume over the wallet's whole life. */
	public List<PnlPoint> dailyPnl(String address) {
		PnlSeries series = caller.call("user-pnl " + address, () -> data.get()
			.uri(u -> u.path("/v2/user-pnl")
				.queryParam("user", address)
				.queryParam("interval", "all")
				.queryParam("fidelity", "1d")
				.build())
			.retrieve()
			.body(PnlSeries.class));
		return series == null || series.data() == null || series.data().points() == null ? List.of()
				: series.data().points();
	}

	public List<Position> openPositions(String address, int limit, int offset) {
		return caller.call("positions " + address, () -> data.get()
			.uri(u -> u.path("/positions")
				.queryParam("user", address)
				.queryParam("limit", limit)
				.queryParam("offset", offset)
				.queryParam("sortBy", "CURRENT")
				.build())
			.retrieve()
			.body(new ParameterizedTypeReference<List<Position>>() {
			}));
	}

	/** @param period DAY, WEEK, MONTH or ALL */
	public List<LeaderboardEntry> leaderboard(String period, String category, int limit, int offset) {
		return caller.call("leaderboard", () -> data.get()
			.uri(u -> u.path("/v1/leaderboard")
				.queryParam("timePeriod", period)
				.queryParam("category", category)
				.queryParam("orderBy", "PNL")
				.queryParam("limit", limit)
				.queryParam("offset", offset)
				.build())
			.retrieve()
			.body(new ParameterizedTypeReference<List<LeaderboardEntry>>() {
			}));
	}

	public Optional<Profile> profile(String address) {
		try {
			return Optional.ofNullable(caller.call("profile " + address, () -> gamma.get()
				.uri(u -> u.path("/public-profile").queryParam("address", address).build())
				.retrieve()
				.body(Profile.class)));
		}
		catch (HttpClientErrorException.NotFound e) {
			return Optional.empty();
		}
	}

	/** Finds a profile whose display name equals {@code username} (case-insensitive). */
	public Optional<Profile> findProfileByName(String username) {
		ProfileSearch result = caller.call("profile search", () -> gamma.get()
			.uri(u -> u.path("/public-search")
				.queryParam("q", username)
				.queryParam("search_profiles", true)
				.queryParam("limit_per_type", 10)
				.build())
			.retrieve()
			.body(ProfileSearch.class));
		if (result == null || result.profiles() == null) {
			return Optional.empty();
		}
		return result.profiles().stream().filter(p -> username.equalsIgnoreCase(p.name())).findFirst();
	}

	/** Events (with their markets and tags) for the given event slugs. */
	public List<GammaEvent> events(List<String> eventSlugs) {
		return caller.call("events", () -> gamma.get().uri((UriBuilder u) -> {
			u.path("/events");
			eventSlugs.forEach(slug -> u.queryParam("slug", slug));
			return u.build();
		}).retrieve().body(new ParameterizedTypeReference<List<GammaEvent>>() {
		}));
	}
}
