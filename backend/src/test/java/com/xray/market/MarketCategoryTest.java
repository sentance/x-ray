package com.xray.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class MarketCategoryTest {

	@Test
	void tagsTakePriorityInRuleOrder() {
		assertThat(MarketCategory.fromTags(List.of("business", "crypto", "bitcoin"), "x")).isEqualTo("Crypto");
		assertThat(MarketCategory.fromTags(List.of("sports", "nba", "games"), "x")).isEqualTo("Sports");
		assertThat(MarketCategory.fromTags(List.of("elections"), "x")).isEqualTo("Politics");
	}

	@Test
	void fallsBackToSlug() {
		assertThat(MarketCategory.fromTags(List.of(), "btc-updown-15m-1792718100")).isEqualTo("Crypto");
		assertThat(MarketCategory.fromSlug("nba-den-okc-2026-10-22")).isEqualTo("Sports");
		assertThat(MarketCategory.fromSlug("who-will-win-eurovision")).isEqualTo("Other");
	}
}
