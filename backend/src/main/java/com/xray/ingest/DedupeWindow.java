package com.xray.ingest;

import java.util.HashMap;
import java.util.Map;

import com.xray.client.PolymarketDtos.Activity;

/**
 * Builds stable identity keys for activity rows, which have no id of their own.
 * <p>
 * Identical fills in one transaction (same asset, side, size and price) are legitimate, so a key is the row's
 * content plus its occurrence number among identical rows. A window covers all pages fetched from one
 * {@code start} timestamp. Because a new window starts at a timestamp that the previous window may have only partly
 * covered, numbering at that timestamp restarts from zero and reproduces the same keys for rows already stored.
 */
public class DedupeWindow {

	private final Map<String, Integer> occurrences = new HashMap<>();

	public String key(Activity a) {
		String content = String.join("|", String.valueOf(a.timestamp()), a.type(), String.valueOf(a.transactionHash()),
				String.valueOf(a.asset()), String.valueOf(a.side()), String.valueOf(a.size()),
				String.valueOf(a.price()), String.valueOf(a.usdcSize()));
		int n = occurrences.merge(content, 1, Integer::sum) - 1;
		return content + "#" + n;
	}
}
