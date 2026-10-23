package com.xray.config;

import java.time.LocalDate;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xray")
public record XrayProperties(Polymarket polymarket, Binance binance, Sync sync, Ai ai, List<Marker> markers) {

	public record Polymarket(String dataApi, String gammaApi, double requestsPerSecond) {
	}

	public record Binance(String baseUrl, double requestsPerSecond) {
	}

	public record Sync(int maxConcurrentWallets) {
	}

	public record Ai(String apiKey, String model) {

		public boolean enabled() {
			return apiKey != null && !apiKey.isBlank();
		}
	}

	/** A Polymarket rule change drawn on the P&L chart. */
	public record Marker(LocalDate date, String label) {
	}
}
