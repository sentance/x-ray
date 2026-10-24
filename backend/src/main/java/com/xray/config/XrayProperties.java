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

	/**
	 * @param chatPerIpPerHour AI requests (explanations and chat answers) one visitor may make per hour
	 * @param requestsPerHour AI requests across all visitors per hour, a ceiling on what the public site spends
	 */
	public record Ai(String apiKey, String model, int chatPerIpPerHour, int requestsPerHour) {

		public boolean enabled() {
			return apiKey != null && !apiKey.isBlank();
		}
	}

	/** A Polymarket rule change drawn on the P&L chart. */
	public record Marker(LocalDate date, String label) {
	}
}
