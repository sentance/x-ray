package com.xray.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

@Configuration
public class HttpConfig {

	@Bean
	RestClientCustomizer upstreamTimeouts() {
		return builder -> {
			var requestFactory = new JdkClientHttpRequestFactory(
					HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
			requestFactory.setReadTimeout(Duration.ofSeconds(30));
			builder.requestFactory(requestFactory).defaultHeader("User-Agent", "polymarket-xray/0.1");
		};
	}
}
