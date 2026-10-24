package com.xray.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.xray.config.XrayProperties;

import jakarta.annotation.PreDestroy;

/** An OpenAI model explaining one wallet's reports, as a one-off explanation or a conversation about it. */
@Service
public class WalletAnalyst {

	private static final Logger log = LoggerFactory.getLogger(WalletAnalyst.class);

	/** One turn of a conversation, as the browser keeps it. */
	public record Turn(String role, String text) {
	}

	/** How a streamed answer ended. A refusal means the partial text must be discarded. */
	public record Outcome(String stopReason, boolean refused) {
	}

	static final String INSTRUCTIONS = """
			You are the analyst inside Polymarket X-Ray, a tool that reverse-engineers how a public Polymarket wallet \
			makes money. You get one wallet's computed reports as JSON and explain them to someone deciding what to \
			make of this trader.

			How to work:
			- Ground every claim in the numbers you were given and cite the figures behind it (dollar amounts, \
			percentages, p-values). If the data can't answer a question, say so plainly instead of guessing.
			- Explain the mechanism, not just the labels: what the trader buys, at what prices, how it exits, and why \
			that would or wouldn't make money.
			- Bring up the analysis's limits when they matter to the answer: per-market P&L is estimated from cash \
			flows with open markets valued at cost; timing is in UTC; the Binance test uses 1-minute candles, so it \
			can't see moves within seconds of a trade.
			- Judge skill against luck from the skillOrLuck statistics (green-day share and its p-value, t-statistic, \
			P&L without the best five days, profit concentration in the top markets), not from total profit.
			- You describe what a public wallet did. Don't recommend trades or tell anyone to copy it; if asked, \
			explain what would have to stay true for the edge to last and what the data says about that.
			- Write for a smart reader who doesn't know Polymarket's jargon. Use Markdown: short paragraphs, headings \
			or bullets only where they help, no tables wider than four columns.

			Field guide to the JSON:
			- totals: lifetime figures from Polymarket's P&L history; profitPerDollar = pnl / volume.
			- classification: the rule-based strategy labels with their evidence. profile: the numbers behind them. \
			entryPrices buckets are buy volume by price paid (0 to 1, the implied probability); \
			exits.heldToResolutionShare is buy volume never sold; bothSides.avgPairCost below 1 means it bought YES \
			and NO together for less than the $1 payout; tradeSizes are USDC per trade.
			- stillWorking: recent 30/60/90-day windows against lifetime.
			- binanceTest, when present: whether crypto buys bet in the direction Binance spot moved before or after \
			the trade; about 50% aligned is a coin flip. When it is absent, don't mention it.""";

	public static final String EXPLAIN = """
			Explain how this wallet makes money. Cover, in order: what it does (its playbook in two or three \
			sentences), where the edge comes from, whether the record looks like skill or luck, whether it still \
			works, and the main risks or open questions. Keep it under 400 words.""";

	/** Generous: explanations run to about 1,000 output tokens including reasoning. */
	private static final long MAX_OUTPUT_TOKENS = 16_000;

	private final Optional<OpenAIClient> client;

	private final String model;

	public WalletAnalyst(XrayProperties properties) {
		this.model = properties.ai().model();
		this.client = properties.ai().enabled()
				? Optional.of(OpenAIOkHttpClient.builder().apiKey(properties.ai().apiKey()).build())
				: Optional.empty();
	}

	public boolean enabled() {
		return client.isPresent();
	}

	/**
	 * Streams the model's answer to {@code question}, after the earlier {@code turns}, about the wallet in
	 * {@code briefing}.
	 */
	public Outcome answer(String address, String briefing, List<Turn> turns, String question,
			Consumer<String> onText) {
		OpenAIClient openai = client.orElseThrow(() -> new IllegalStateException("No OpenAI API key"));
		List<ResponseInputItem> input = new ArrayList<>();
		for (Turn t : turns) {
			input.add(message("assistant".equals(t.role()) ? EasyInputMessage.Role.ASSISTANT
					: EasyInputMessage.Role.USER, t.text()));
		}
		input.add(message(EasyInputMessage.Role.USER, question));
		ResponseCreateParams params = ResponseCreateParams.builder()
			.model(model)
			// Instructions first, then the wallet data: a stable prefix that OpenAI caches automatically, so
			// follow-up questions about the same wallet reuse it. The cache key keeps one wallet's requests together.
			.instructions(INSTRUCTIONS + "\n\nWallet data:\n" + briefing)
			.promptCacheKey("xray-wallet-" + address)
			.inputOfResponse(input)
			// Low effort: the reports already hold the analysis, so the model mostly has to explain it.
			.reasoning(Reasoning.builder().effort(ReasoningEffort.LOW).build())
			.maxOutputTokens(MAX_OUTPUT_TOKENS)
			.store(false)
			.build();

		String[] stopReason = { null };
		boolean[] refused = { false };
		try (StreamResponse<ResponseStreamEvent> stream = openai.responses().createStreaming(params)) {
			stream.stream().forEach(event -> {
				event.outputTextDelta().ifPresent(d -> onText.accept(d.delta()));
				event.refusalDelta().ifPresent(d -> refused[0] = true);
				event.completed().ifPresent(c -> stopReason[0] = "completed");
				event.incomplete()
					.ifPresent(i -> stopReason[0] = i.response()
						.incompleteDetails()
						.flatMap(d -> d.reason())
						.map(Object::toString)
						.orElse("incomplete"));
				event.failed().ifPresent(f -> {
					throw new IllegalStateException("Response failed: " + f.response().error());
				});
				event.error().ifPresent(e -> {
					throw new IllegalStateException("Stream error: " + e.message());
				});
			});
		}
		if (refused[0]) {
			log.info("The model declined a wallet question");
		}
		return new Outcome(stopReason[0], refused[0]);
	}

	private static ResponseInputItem message(EasyInputMessage.Role role, String text) {
		return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder().role(role).content(text).build());
	}

	@PreDestroy
	void close() {
		client.ifPresent(OpenAIClient::close);
	}
}
