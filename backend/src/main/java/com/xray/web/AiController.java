package com.xray.web;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.xray.ai.AiRateLimiter;
import com.xray.ai.NarrativeRepository;
import com.xray.ai.WalletAnalyst;
import com.xray.ai.WalletAnalyst.Outcome;
import com.xray.ai.WalletAnalyst.Turn;
import com.xray.ai.WalletBriefing;
import com.xray.analysis.AnalysisService;
import com.xray.ingest.WalletRepository.Wallet;

import jakarta.servlet.http.HttpServletRequest;

/**
 * "Ask Claude": a strategy explanation per wallet (stored until the next sync) and follow-up questions, both
 * streamed as server-sent events: {@code text} chunks, then {@code done}, or {@code refusal} (discard the partial
 * text) or {@code error}.
 */
@RestController
public class AiController {

	private static final Logger log = LoggerFactory.getLogger(AiController.class);

	public record ChatRequest(List<Turn> turns, String question) {
	}

	private static final int MAX_TURNS = 20;

	private static final int MAX_QUESTION = 2_000;

	private static final int MAX_TURN_TEXT = 8_000;

	private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

	private final WalletAnalyst analyst;

	private final WalletBriefing briefing;

	private final NarrativeRepository narratives;

	private final AnalysisService analysis;

	private final AiRateLimiter limiter;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public AiController(WalletAnalyst analyst, WalletBriefing briefing, NarrativeRepository narratives,
			AnalysisService analysis, AiRateLimiter limiter) {
		this.analyst = analyst;
		this.briefing = briefing;
		this.narratives = narratives;
		this.analysis = analysis;
		this.limiter = limiter;
	}

	@GetMapping("/api/ai")
	public Map<String, Boolean> status() {
		return Map.of("enabled", analyst.enabled());
	}

	/** The stored explanation for the wallet's current data, if one was written. */
	@GetMapping("/api/wallets/{address}/narrative")
	public Map<String, Object> narrative(@PathVariable String address) {
		Wallet wallet = analysis.wallet(AddressResolver.requireAddress(address));
		return Map.of("enabled", analyst.enabled(),
				"text", narratives.find(wallet.address(), wallet.syncVersion()).orElse(""));
	}

	@PostMapping(path = "/api/wallets/{address}/narrative", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter explain(@PathVariable String address, HttpServletRequest request) {
		Wallet wallet = readyWallet(AddressResolver.requireAddress(address));
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
		var stored = narratives.find(wallet.address(), wallet.syncVersion());
		if (stored.isPresent()) {
			executor.submit(() -> {
				send(emitter, "text", Map.of("text", stored.get()));
				finish(emitter, "done", Map.of("stopReason", "end_turn"));
			});
			return emitter;
		}
		requireAllowance(request);
		String brief = briefing.build(wallet.address());
		executor.submit(() -> stream(emitter, brief, List.of(), WalletAnalyst.EXPLAIN,
				text -> narratives.save(wallet.address(), wallet.syncVersion(), text)));
		return emitter;
	}

	@PostMapping(path = "/api/wallets/{address}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter chat(@PathVariable String address, @RequestBody ChatRequest body,
			HttpServletRequest request) {
		Wallet wallet = readyWallet(AddressResolver.requireAddress(address));
		String question = body.question() == null ? "" : body.question().strip();
		if (question.isEmpty() || question.length() > MAX_QUESTION) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Ask a question of up to " + MAX_QUESTION + " characters");
		}
		List<Turn> turns = validTurns(body.turns());
		requireAllowance(request);
		// The stored explanation opens the conversation, so follow-ups can refer to it.
		List<Turn> history = new ArrayList<>();
		narratives.find(wallet.address(), wallet.syncVersion()).ifPresent(text -> {
			history.add(new Turn("user", WalletAnalyst.EXPLAIN));
			history.add(new Turn("assistant", text));
		});
		history.addAll(turns);
		String brief = briefing.build(wallet.address());
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
		executor.submit(() -> stream(emitter, brief, history, question, text -> {
		}));
		return emitter;
	}

	private void stream(SseEmitter emitter, String brief, List<Turn> history, String question,
			Consumer<String> onComplete) {
		StringBuilder text = new StringBuilder();
		try {
			Outcome outcome = analyst.answer(brief, history, question, chunk -> {
				text.append(chunk);
				send(emitter, "text", Map.of("text", chunk));
			});
			if (outcome.refused()) {
				finish(emitter, "refusal", Map.of("message",
						"Claude declined to answer this one. Try rephrasing the question."));
				return;
			}
			onComplete.accept(text.toString());
			finish(emitter, "done", Map.of("stopReason", String.valueOf(outcome.stopReason())));
		}
		catch (RateLimitException e) {
			finish(emitter, "error", Map.of("message", "Claude is busy right now; try again in a minute."));
		}
		catch (AnthropicServiceException e) {
			// Details (billing, bad request) are for the operator's logs, not the visitor.
			log.warn("Claude request failed: {}", e.getMessage());
			finish(emitter, "error", Map.of("message", "Ask Claude is unavailable right now; try again later."));
		}
		catch (Exception e) {
			log.error("Claude stream failed", e);
			finish(emitter, "error", Map.of("message", "Something went wrong while Claude was answering."));
		}
	}

	/** Explanations need the finished reports; a wallet still syncing would get a half-built picture. */
	private Wallet readyWallet(String address) {
		if (!analyst.enabled()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Ask Claude isn't set up on this server (no Anthropic API key)");
		}
		Wallet wallet = analysis.wallet(address);
		if (wallet.lastSyncedAt() == null || analysis.syncing(address)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Wait for the wallet's sync to finish");
		}
		return wallet;
	}

	private void requireAllowance(HttpServletRequest request) {
		if (!limiter.tryAcquire(visitor(request))) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"You've reached the hourly limit for questions to Claude; try again later");
		}
	}

	/** The visitor's address as Cloudflare or the edge proxy reports it. */
	private static String visitor(HttpServletRequest request) {
		String cf = request.getHeader("CF-Connecting-IP");
		if (cf != null && !cf.isBlank()) {
			return cf.strip();
		}
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			return forwarded.split(",")[0].strip();
		}
		return request.getRemoteAddr();
	}

	private static List<Turn> validTurns(List<Turn> turns) {
		if (turns == null) {
			return List.of();
		}
		if (turns.size() > MAX_TURNS) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"The conversation is too long; start a new one");
		}
		for (int i = 0; i < turns.size(); i++) {
			Turn t = turns.get(i);
			String expected = i % 2 == 0 ? "user" : "assistant";
			if (t == null || !expected.equals(t.role()) || t.text() == null || t.text().isBlank()
					|| t.text().length() > MAX_TURN_TEXT) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed conversation");
			}
		}
		if (turns.size() % 2 != 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed conversation");
		}
		return turns;
	}

	private static void send(SseEmitter emitter, String event, Object data) {
		try {
			emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException e) {
			// The browser went away; the stream keeps running so a finished explanation is still stored.
		}
	}

	private static void finish(SseEmitter emitter, String event, Object data) {
		send(emitter, event, data);
		emitter.complete();
	}
}
