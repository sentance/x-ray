package com.xray.paper;

import static com.xray.paper.PaperModel.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Each session has one writer. DB checkpoints and evidence commit together. Restarts never replay downtime. */
@Service
public class PaperService {
    private final PaperRepository repository;
    private final PaperMarketData data;
    private final JsonMapper json;
    private final ConcurrentMap<UUID, Runner> runners = new ConcurrentHashMap<>();
    public PaperService(PaperRepository repository, PaperMarketData data, JsonMapper json) {
        this.repository = repository; this.data = data; this.json = json;
    }
    public record View(State state, double equity, double pnl, double reserved, double available,
                       boolean active, String incentiveNote) { }
    public synchronized View create(Settings settings) {
        if (runners.size() >= 3) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Three paper sessions are already running");
        State state = new State(UUID.randomUUID(), settings, System.currentTimeMillis());
        state.note(state.createdAt, "START", "Frozen " + POLICY + "; rewards/rebates excluded from profit");
        repository.save(state, List.of(new Evidence(state.createdAt, "POLICY", settings)));
        return start(state.id);
    }
    public View view(UUID id) {
        State state = load(id);
        if (!runners.containsKey(id) && "RUNNING".equals(state.status)) {
            // A process restart invalidates every resting quote. The next start obtains fresh books.
            state.quote = null; state.status = "PAUSED"; state.feed = "Disconnected";
            state.message = "Process stopped; resume with fresh data. Downtime never produces fills.";
        }
        // No stale mark is presented as current executable equity while disconnected.
        if (!runners.containsKey(id)) state.liquidationValue = 0;
        Runner runner = runners.get(id);
        if (runner != null && runner.stop.get()) state.message = "Pausing after the current public read / checkpoint; inventory will be retained";
        double reserved = state.reserved();
        return new View(state, state.equity(), state.pnl(), reserved, money(Math.max(0, state.cash - reserved)),
                runners.containsKey(id), "No earned rewards or rebates are credited. Pool allocations are not account income.");
    }
    private State load(UUID id) {
        return repository.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Paper session not found"));
    }
    public synchronized View start(UUID id) {
        if (runners.containsKey(id)) return view(id);
        if (runners.size() >= 3) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Paper runner capacity reached");
        State s = load(id);
        if (!POLICY.equals(s.policy)) throw new ResponseStatusException(HttpStatus.CONFLICT, "This session uses an older policy; create a new session");
        if (s.pending != null || s.positions.values().stream().anyMatch(p -> p.name.endsWith(" NO")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Unhedged inventory retained for review; export this session and start a new one");
        if (s.cash - s.settings.bankroll() <= -s.settings.maxLoss())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Paper loss limit reached; start a new session with an explicit policy");
        s.quote = null; s.status = "RUNNING"; s.feed = "Connecting"; s.message = "Discovering reward markets";
        s.liquidationValue = 0; s.note(System.currentTimeMillis(), "RESUME", "Fresh book and queue required");
        repository.save(s, List.of());
        Runner r = new Runner(s); runners.put(id, r); r.thread = Thread.ofVirtual().name("paper-" + id).start(r);
        return view(id);
    }
    public View pause(UUID id) {
        load(id);
        Runner runner = runners.get(id);
        if (runner != null) runner.stop.set(true);
        return view(id);
    }
    public List<PaperRepository.Row> evidence(UUID id, long after) {
        load(id); return repository.evidence(id, Math.max(0, after), 500);
    }
    static boolean relevantLifecycle(JsonNode node, Set<String> subscribed, List<Group> groups) {
        if (subscribed.contains(node.path("asset_id").asText())) return true;
        for (JsonNode asset : node.path("assets_ids")) if (subscribed.contains(asset.asText())) return true;
        String condition = node.path("market").asText();
        String slug = node.path("event_message").path("slug").asText();
        String riskId = node.path("neg_risk_market_id").asText();
        return groups.stream().anyMatch(g -> g.id().equals(riskId) || g.slug().equals(slug)
                || g.outcomes().stream().anyMatch(o -> o.conditionId().equals(condition)));
    }
    @PreDestroy
    public void close() {
        runners.values().forEach(r -> r.stop.set(true));
        runners.values().forEach(r -> { try { r.thread.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
    }
    private record Frame(long at, String body) { }
    private final class Runner implements Runnable {
        final PaperEngine engine;
        final AtomicBoolean stop = new AtomicBoolean(), gap = new AtomicBoolean();
        final BlockingQueue<Frame> frames = new ArrayBlockingQueue<>(4000);
        final AtomicLong lastReceive = new AtomicLong();
        final AtomicLong streamEpoch = new AtomicLong();
        volatile WebSocket socket;
        Thread thread;
        long lastScan, lastPing, lastSave, lastRefresh, frameCount;
        Set<String> subscribed = new HashSet<>();
        Runner(State state) { engine = new PaperEngine(state); }
        @Override public void run() {
            State s = engine.state;
            try {
                while (!stop.get() && "RUNNING".equals(s.status)) {
                    long now = System.currentTimeMillis();
                    if (now - s.createdAt > Duration.ofDays(30).toMillis()) { engine.pause(now, "30-day paper capture complete; review the export"); break; }
                    if (lastScan == 0 || gap.get() || (lastReceive.get() > 0 && now - lastReceive.get() > 25_000)) {
                        disconnect(now, "Feed reconnect; queue cancelled");
                        scan(now); connect(); lastScan = System.currentTimeMillis();
                    }
                    if (now - lastScan > 240_000 && s.pending == null) {
                        // No maker fills are inferred while metadata is being refreshed over REST.
                        disconnect(now, "Refreshing conversion and reward metadata");
                        scan(now); connect(); lastScan = System.currentTimeMillis();
                    }
                    Frame frame = frames.poll(250, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        drainFrames(frame);
                        if (gap.get()) continue;
                        if (frameCount > 2_000_000) { engine.pause(now, "Paper capture size limit reached; export and review"); break; }
                    }
                    now = System.currentTimeMillis();
                    if (now - lastPing >= 10_000 && socket != null) {
                        socket.sendText("PING", true).join(); lastPing = now;
                    }
                    // Obtain post-latency hedge evidence. Quote queues never advance from REST snapshots.
                    if (s.pending != null && now >= s.pending.hedgeAt && now - lastRefresh > 1000) {
                        String token = s.pending.hedgeToken;
                        JsonNode book = data.book(token, n -> engine.record(System.currentTimeMillis(), "REST", n));
                        long receipt = System.currentTimeMillis();
                        applyBook(token, book, receipt, true); lastRefresh = receipt;
                        // Older queued deltas are rejected by the engine's exchange timestamp checks.
                        drainFrames(frames.poll());
                    } else if (s.quote == null && now - lastRefresh > 1000) {
                        // Residual sales also require depth received after the conversion/latency.
                        for (Position p : s.positions.values()) {
                            Book b = engine.books.get(p.token);
                            if (!p.sellable || !p.name.endsWith(" YES") || now < p.sellAfter || p.shares < p.minSize
                                    || (b != null && b.fresh(now) && b.receivedAt >= p.sellAfter)) continue;
                            JsonNode book = data.book(p.token, n -> engine.record(System.currentTimeMillis(), "REST", n));
                            applyBook(p.token, book, System.currentTimeMillis(), true);
                            lastRefresh = System.currentTimeMillis(); drainFrames(frames.poll()); break;
                        }
                    }
                    if (gap.get()) continue;
                    engine.tick(System.currentTimeMillis());
                    if (now - lastSave >= 1000 || !"RUNNING".equals(s.status)) save();
                    if (s.groups.isEmpty()) {
                        s.message = "No supported reward events. See skipped reasons; retrying in four minutes.";
                        // Empty universes still need a heartbeat without opening an empty subscription.
                        Thread.sleep(1000);
                    }
                }
                if (stop.get()) engine.pause(System.currentTimeMillis(), "Paused by user; inventory retained");
            } catch (InterruptedException e) {
                Thread.interrupted(); engine.pause(System.currentTimeMillis(), "Paused; inventory retained and quotes cancelled");
            } catch (Exception e) {
                engine.pause(System.currentTimeMillis(), stop.get() ? "Paused by user; inventory retained"
                        : "Paper runner paused: " + PaperMarketData.safeMessage(e));
            } finally {
                if (socket != null) socket.abort();
                s.feed = "Disconnected"; s.quote = null; s.updatedAt = System.currentTimeMillis();
                try { save(); } finally { runners.remove(s.id, this); }
            }
        }
        void save() {
            engine.state.updatedAt = System.currentTimeMillis();
            List<Evidence> batch = engine.drainEvidence();
            try { repository.save(engine.state, batch); }
            catch (RuntimeException e) { engine.restoreEvidence(batch); throw e; }
            lastSave = System.currentTimeMillis();
        }
        void scan(long now) throws Exception {
            engine.state.message = "Refreshing public reward and conversion data; paper quotes cancelled";
            save();
            var scan = data.discover(n -> {
                engine.record(System.currentTimeMillis(), "DISCOVERY", n);
                if (stop.get()) throw new CancellationException("Paper pause requested");
            }, stop::get);
            State s = engine.state;
            // Held residuals keep their original conversion provenance when discovery rotates.
            List<Group> groups = new ArrayList<>(scan.groups());
            for (Group old : s.groups) if (s.positions.values().stream().anyMatch(p -> p.groupId.equals(old.id()))
                    && groups.stream().noneMatch(g -> g.id().equals(old.id()))) groups.add(old);
            s.groups = groups; scan.skipped().forEach((k, v) -> s.skipped.merge(k, v, Integer::sum));
            s.lastScanAt = System.currentTimeMillis(); save();
        }
        void disconnect(long now, String reason) {
            streamEpoch.incrementAndGet();
            if (socket != null) socket.abort(); socket = null;
            frames.clear(); engine.disconnected(now, reason); gap.set(false); lastReceive.set(0);
        }
        void connect() throws Exception {
            long epoch = streamEpoch.get();
            Set<String> assets = new LinkedHashSet<>();
            for (Group g : engine.state.groups) for (Outcome o : g.outcomes()) {
                if (o.tradable()) { assets.add(o.no()); assets.add(o.yes()); }
            }
            engine.state.positions.values().stream().filter(p -> p.sellable).forEach(p -> assets.add(p.token));
            if (assets.size() > 256) throw new IllegalArgumentException("Too many retained assets for a bounded paper feed; export and review");
            subscribed = assets;
            if (assets.isEmpty()) { lastScan = System.currentTimeMillis(); return; }
            WebSocket.Listener listener = new WebSocket.Listener() {
                final StringBuilder buffer = new StringBuilder();
                @Override public void onOpen(WebSocket ws) { ws.request(1); }
                @Override public CompletionStage<?> onText(WebSocket ws, CharSequence message, boolean last) {
                    if (epoch != streamEpoch.get()) return null;
                    lastReceive.set(System.currentTimeMillis()); buffer.append(message);
                    if (buffer.length() > 8_000_000) { gap.set(true); ws.abort(); return null; }
                    if (last) {
                        String body = buffer.toString(); buffer.setLength(0);
                        if (!"PONG".equals(body) && !frames.offer(new Frame(System.currentTimeMillis(), body))) gap.set(true);
                    }
                    ws.request(1); return null;
                }
                @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { if (epoch == streamEpoch.get()) gap.set(true); return null; }
                @Override public void onError(WebSocket ws, Throwable error) { if (epoch == streamEpoch.get()) gap.set(true); }
            };
            var connection = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build().newWebSocketBuilder()
                    .header("User-Agent", "polymarket-xray-paper/1").connectTimeout(Duration.ofSeconds(20))
                    .buildAsync(URI.create("wss://ws-subscriptions-clob.polymarket.com/ws/market"), listener);
            long deadline = System.currentTimeMillis() + 25_000;
            while (true) {
                if (stop.get() || System.currentTimeMillis() > deadline) {
                    connection.cancel(true);
                    throw new CancellationException(stop.get() ? "Paper pause requested" : "Public WebSocket handshake timed out");
                }
                try { socket = connection.get(250, TimeUnit.MILLISECONDS); break; }
                catch (TimeoutException e) { /* Cooperative cancellation never interrupts a JDBC checkpoint. */ }
            }
            socket.sendText(json.writeValueAsString(Map.of("assets_ids", assets, "type", "market", "custom_feature_enabled", true)), true).get(5, TimeUnit.SECONDS);
            lastReceive.set(System.currentTimeMillis()); engine.state.feed = "Connected";
        }
        void consume(Frame frame) {
            JsonNode payload = json.readTree(frame.body);
            if (payload.isArray()) payload.forEach(n -> consumeNode(n, frame.at)); else consumeNode(payload, frame.at);
        }
        void drainFrames(Frame first) {
            Frame frame = first; int count = 0;
            while (frame != null && count++ < 4000) {
                if (System.currentTimeMillis() - frame.at > 2000) { gap.set(true); return; }
                consume(frame); frameCount++; frame = frames.poll();
            }
        }
        void consumeNode(JsonNode n, long receipt) {
            State s = engine.state; String type = n.path("event_type").asText();
            if (type.isEmpty()) throw new IllegalArgumentException("Unsupported market stream message");
            String token = n.path("asset_id").asText();
            long exchange = n.path("timestamp").asLong(0);
            s.lastFeedAt = receipt;
            // Store immutable public inputs needed to replay quotes, queues and inventory decisions.
            engine.record(receipt, "WS", n);
            if (Set.of("tick_size_change", "market_resolved", "new_market").contains(type)) {
                if (relevantLifecycle(n, subscribed, s.groups))
                    engine.pause(receipt, "Market rules / lifecycle changed (" + type + "); review and start a fresh session");
                return; // The feed can broadcast lifecycle messages for unrelated events.
            }
            if (exchange == 0 || exchange > receipt + 2000 || receipt - exchange > 15_000) {
                engine.cancel(receipt, "Stale or invalid stream timestamp"); return;
            }
            switch (type) {
                case "book" -> { if (subscribed.contains(token)) applyBook(token, n, receipt, false); }
                case "price_change" -> {
                    for (JsonNode c : n.path("price_changes")) {
                        String asset = c.path("asset_id").asText();
                        if (subscribed.contains(asset)) engine.change(asset, c.path("side").asText(),
                                PaperMarketData.number(c, "price"), PaperMarketData.number(c, "size"), exchange, receipt);
                    }
                }
                case "last_trade_price" -> {
                    String tx = n.path("transaction_hash").asText();
                    if (!tx.matches("0x[0-9a-fA-F]{64}")) { s.skip("Unidentifiable trade ignored"); return; }
                    String key = tx + ":" + token + ":" + n.path("side").asText() + ":" + n.path("price").asText() + ":" + n.path("size").asText();
                    engine.trade(token, n.path("side").asText(), PaperMarketData.number(n, "price"), PaperMarketData.number(n, "size"), exchange, receipt, key);
                }
                case "best_bid_ask" -> { } // Depth events, not displayed prices, drive decisions.
                default -> throw new IllegalArgumentException("Unknown market stream event");
            }
        }
        void applyBook(String token, JsonNode node, long receipt, boolean rest) {
            if (rest && !token.equals(node.path("asset_id").asText())) throw new IllegalArgumentException("Book asset mismatch");
            long exchange = node.path("timestamp").asLong(0);
            if (exchange <= 0 || exchange > receipt + 2000 || receipt - exchange > 15_000)
                throw new IllegalArgumentException("Stale book snapshot");
            engine.book(token, levels(node.path("bids")), levels(node.path("asks")), exchange, receipt);
        }
        List<Level> levels(JsonNode node) {
            if (!node.isArray()) throw new IllegalArgumentException("Missing order book levels");
            List<Level> levels = new ArrayList<>();
            for (JsonNode l : node) levels.add(new Level(PaperMarketData.number(l, "price"), PaperMarketData.number(l, "size")));
            return levels;
        }
    }
}
