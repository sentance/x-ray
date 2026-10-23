package com.xray.binance;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.xray.client.RateLimitedCaller;
import com.xray.config.XrayProperties;

/** Read-only client for Binance's public spot market data. */
@Component
public class BinanceClient {

	/** The most candles one klines request returns. */
	public static final int MAX_KLINES = 1000;

	/** One 1-minute candle; prices are the minute's first and last trade. */
	public record Kline(long openTime, BigDecimal open, BigDecimal close) {
	}

	private final RestClient http;

	private final RateLimitedCaller caller;

	public BinanceClient(RestClient.Builder builder, XrayProperties properties) {
		this.http = builder.clone().baseUrl(properties.binance().baseUrl()).build();
		this.caller = new RateLimitedCaller(properties.binance().requestsPerSecond(), Duration.ofSeconds(1));
	}

	/** Up to {@link #MAX_KLINES} 1-minute candles from {@code startMs} (epoch ms, inclusive). */
	public List<Kline> minuteKlines(String symbol, long startMs) {
		// Each candle is an array: [openTime, open, high, low, close, volume, closeTime, ...], prices as strings.
		List<List<Object>> rows = caller.call("klines " + symbol, () -> http.get()
			.uri(u -> u.path("/api/v3/klines")
				.queryParam("symbol", symbol)
				.queryParam("interval", "1m")
				.queryParam("startTime", startMs)
				.queryParam("limit", MAX_KLINES)
				.build())
			.retrieve()
			.body(new ParameterizedTypeReference<List<List<Object>>>() {
			}));
		if (rows == null) {
			return List.of();
		}
		return rows.stream()
			.map(r -> new Kline(((Number) r.get(0)).longValue(), new BigDecimal(r.get(1).toString()),
					new BigDecimal(r.get(4).toString())))
			.toList();
	}
}
