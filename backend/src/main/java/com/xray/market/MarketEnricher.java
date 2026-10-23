package com.xray.market;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.xray.client.PolymarketClient;
import com.xray.client.PolymarketDtos.GammaEvent;
import com.xray.client.PolymarketDtos.GammaMarket;
import com.xray.client.PolymarketDtos.Tag;
import com.xray.market.MarketRepository.MarketUpdate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Fills in category, tags, end date and resolution for the markets a wallet traded. */
@Service
public class MarketEnricher {

	private static final Logger log = LoggerFactory.getLogger(MarketEnricher.class);

	private static final int EVENTS_PER_REQUEST = 20;

	private final PolymarketClient polymarket;

	private final MarketRepository markets;

	private final JsonMapper json;

	public MarketEnricher(PolymarketClient polymarket, MarketRepository markets, JsonMapper json) {
		this.polymarket = polymarket;
		this.markets = markets;
		this.json = json;
	}

	public void enrich(String address) {
		markets.insertMissingFromActivity(address);
		List<String> slugs = markets.eventSlugsToEnrich(address);
		log.info("Enriching {} events for {}", slugs.size(), address);
		for (int i = 0; i < slugs.size(); i += EVENTS_PER_REQUEST) {
			List<String> batch = slugs.subList(i, Math.min(i + EVENTS_PER_REQUEST, slugs.size()));
			List<GammaEvent> events = polymarket.events(batch);
			List<MarketUpdate> updates = new ArrayList<>();
			for (GammaEvent event : events == null ? List.<GammaEvent>of() : events) {
				List<String> tags = event.tags() == null ? List.of() : event.tags().stream().map(Tag::slug).toList();
				String category = MarketCategory.fromTags(tags, event.slug());
				for (GammaMarket m : event.markets() == null ? List.<GammaMarket>of() : event.markets()) {
					updates.add(new MarketUpdate(m.conditionId(), category, tags, parseInstant(m.endDate()),
							m.closed(), winningOutcome(m), m.negRisk()));
				}
			}
			markets.update(updates);
			markets.markUnresolvedEvents(batch);
		}
	}

	/** Index of the outcome that settled at 1, or null while unresolved. */
	Integer winningOutcome(GammaMarket m) {
		if (!Boolean.TRUE.equals(m.closed()) || m.outcomePrices() == null) {
			return null;
		}
		List<String> prices = json.readValue(m.outcomePrices(), new TypeReference<List<String>>() {
		});
		for (int i = 0; i < prices.size(); i++) {
			if (Double.parseDouble(prices.get(i)) >= 0.99) {
				return i;
			}
		}
		return null;
	}

	private static Instant parseInstant(String s) {
		if (s == null) {
			return null;
		}
		try {
			return Instant.parse(s);
		}
		catch (DateTimeParseException e) {
			return null;
		}
	}
}
