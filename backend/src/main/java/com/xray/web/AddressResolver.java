package com.xray.web;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.xray.client.PolymarketClient;
import com.xray.client.PolymarketDtos.Profile;

/** Turns user input (address, profile URL or username) into a lowercase wallet address. */
@Component
public class AddressResolver {

	private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");

	private static final Pattern USERNAME = Pattern.compile("(?:polymarket\\.com/)?@?([A-Za-z0-9_.-]{1,64})/?$");

	private final PolymarketClient polymarket;

	public AddressResolver(PolymarketClient polymarket) {
		this.polymarket = polymarket;
	}

	public String resolve(String input) {
		String trimmed = input == null ? "" : input.trim();
		Matcher address = ADDRESS.matcher(trimmed);
		if (address.find()) {
			return address.group().toLowerCase(Locale.ROOT);
		}
		Matcher username = USERNAME.matcher(trimmed);
		if (username.find()) {
			return polymarket.findProfileByName(username.group(1))
				.map(Profile::proxyWallet)
				.map(a -> a.toLowerCase(Locale.ROOT))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"No Polymarket profile named " + username.group(1)));
		}
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a wallet address, profile URL or username");
	}

	/** Validates a path variable that must already be an address. */
	public static String requireAddress(String value) {
		if (value == null || !ADDRESS.matcher(value).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid wallet address");
		}
		return value.toLowerCase(Locale.ROOT);
	}
}
