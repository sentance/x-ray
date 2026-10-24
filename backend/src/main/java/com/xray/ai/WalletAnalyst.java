package com.xray.ai;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.xray.config.XrayProperties;

import jakarta.annotation.PreDestroy;

/** Claude explaining one wallet's reports, as a one-off explanation or a conversation about it. */
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
			the trade; about 50% aligned is a coin flip.""";

	public static final String EXPLAIN = """
			Explain how this wallet makes money. Cover, in order: what it does (its playbook in two or three \
			sentences), where the edge comes from, whether the record looks like skill or luck, whether it still \
			works, and the main risks or open questions. Keep it under 400 words.""";

	/** Streaming, so a long answer can't hit a request timeout; effort keeps the actual spend down. */
	private static final long MAX_TOKENS = 64_000;

	private final Optional<AnthropicClient> client;

	private final String model;

	public WalletAnalyst(XrayProperties properties) {
		this.model = properties.ai().model();
		this.client = properties.ai().enabled()
				? Optional.of(AnthropicOkHttpClient.builder().apiKey(properties.ai().apiKey()).build())
				: Optional.empty();
	}

	public boolean enabled() {
		return client.isPresent();
	}

	/**
	 * Streams Claude's answer to {@code question}, after the earlier {@code turns}, about the wallet in
	 * {@code briefing}.
	 */
	public Outcome answer(String briefing, List<Turn> turns, String question, Consumer<String> onText) {
		AnthropicClient anthropic = client.orElseThrow(() -> new IllegalStateException("No Anthropic API key"));
		MessageCreateParams.Builder params = MessageCreateParams.builder()
			.model(model)
			.maxTokens(MAX_TOKENS)
			.thinking(BetaThinkingConfigAdaptive.builder().build())
			.outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.MEDIUM).build())
			// The instructions never change and the wallet data only changes per wallet, so both are cached;
			// follow-up questions about the same wallet then read them from the cache.
			.systemOfBetaTextBlockParams(List.of(BetaTextBlockParam.builder().text(INSTRUCTIONS).build(),
					BetaTextBlockParam.builder()
						.text("Wallet data:\n" + briefing)
						.cacheControl(BetaCacheControlEphemeral.builder().build())
						.build()))
			// If a safety classifier declines, retry server-side on the model Anthropic recommends for that case.
			.addBeta("server-side-fallback-2026-07-01")
			.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
		for (Turn t : turns) {
			if ("assistant".equals(t.role())) {
				params.addAssistantMessage(t.text());
			}
			else {
				params.addUserMessage(t.text());
			}
		}
		params.addUserMessage(question);

		String[] stopReason = { null };
		try (StreamResponse<BetaRawMessageStreamEvent> stream = anthropic.beta().messages().createStreaming(params.build())) {
			stream.stream().forEach(event -> {
				event.contentBlockDelta().flatMap(d -> d.delta().text()).ifPresent(t -> onText.accept(t.text()));
				event.messageDelta().flatMap(d -> d.delta().stopReason()).ifPresent(r -> stopReason[0] = r.toString());
			});
		}
		boolean refused = BetaStopReason.REFUSAL.toString().equals(stopReason[0]);
		if (refused) {
			log.info("Claude declined a wallet question");
		}
		return new Outcome(stopReason[0], refused);
	}

	@PreDestroy
	void close() {
		client.ifPresent(AnthropicClient::close);
	}
}
