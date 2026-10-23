package com.xray.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.xray.config.XrayProperties;

class PolymarketClientTest {

	private MockRestServiceServer server;

	private PolymarketClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(false).build();
		var properties = new XrayProperties(new XrayProperties.Polymarket("https://data", "https://gamma", 100), null,
				null, null, List.of());
		client = new PolymarketClient(builder, properties);
	}

	@Test
	void retriesWhenRateLimited() {
		String url = "https://data/activity?user=0xw&limit=500&offset=0&start=10&sortDirection=ASC";
		server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		server.expect(ExpectedCount.once(), requestTo(url))
			.andRespond(withSuccess("""
					[{"proxyWallet":"0xw","timestamp":11,"type":"TRADE","side":"BUY","size":2,"price":0.4}]""",
					MediaType.APPLICATION_JSON));

		var page = client.activityAscending("0xw", 10, 0);

		assertThat(page).singleElement().satisfies(a -> {
			assertThat(a.timestamp()).isEqualTo(11);
			assertThat(a.side()).isEqualTo("BUY");
		});
		server.verify();
	}

	@Test
	void requestsSeveralEventsAtOnce() {
		server.expect(requestTo("https://gamma/events?slug=a&slug=b"))
			.andRespond(withSuccess("""
					[{"slug":"a","tags":[{"slug":"crypto"}],"markets":[{"conditionId":"0x1","closed":true,
					  "outcomePrices":"[\\"0\\", \\"1\\"]"}]}]""", MediaType.APPLICATION_JSON));

		var events = client.events(List.of("a", "b"));

		assertThat(events).singleElement().satisfies(e -> {
			assertThat(e.tags()).extracting(PolymarketDtos.Tag::slug).containsExactly("crypto");
			assertThat(e.markets().getFirst().outcomePrices()).isEqualTo("[\"0\", \"1\"]");
		});
	}
}
