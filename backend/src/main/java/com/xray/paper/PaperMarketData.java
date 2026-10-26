package com.xray.paper;

import static com.xray.paper.PaperModel.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.concurrent.*;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Public endpoints only. The sole POST is a read-only eth_call; there is no order client. */
@Component
public class PaperMarketData {
    static final String CLOB = "https://clob.polymarket.com";
    static final String GAMMA = "https://gamma-api.polymarket.com";
    static final String RPC = "https://polygon-bor-rpc.publicnode.com";
    static final String ADAPTER = "0xd91E80cF2E7be2e162c6513ceD06f1dD0dA35296";
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(20)).build();
    private final JsonMapper json;
    public PaperMarketData(JsonMapper json) { this.json = json; }

    private JsonNode get(String url, Consumer<JsonNode> evidence) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(url.contains("/book?") ? 10 : 30))
                .header("User-Agent", "polymarket-xray-paper/1").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Public market data HTTP " + response.statusCode());
        JsonNode node = json.readTree(response.body());
        emit(evidence, json.valueToTree(Map.of("url", url, "receivedAt", System.currentTimeMillis(), "response", node)));
        return node;
    }
    public record Scan(List<Group> groups, Map<String, Integer> skipped) { }
    public Scan discover(Consumer<JsonNode> evidence) throws Exception {
        return discover(evidence, () -> false);
    }
    private record Candidate(Group group, String error) { }
    public Scan discover(Consumer<JsonNode> evidence, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        long now = System.currentTimeMillis();
        JsonNode rewards = get(CLOB + "/rewards/markets/multi?order_by=rate_per_day&position=DESC&page_size=100", evidence);
        if (!rewards.path("data").isArray()) throw new IllegalStateException("Unsupported reward response");
        Map<String, JsonNode> byCondition = new HashMap<>();
        Set<String> slugs = new LinkedHashSet<>();
        for (JsonNode r : rewards.path("data")) {
            byCondition.put(r.path("condition_id").asText(), r);
            String slug = r.path("event_slug").asText();
            if (slug.matches("[a-zA-Z0-9-]{1,220}")) slugs.add(slug);
        }
        List<Group> groups = new ArrayList<>(); Map<String, Integer> skipped = new TreeMap<>();
        // Four bounded readers avoid minutes of serial startup on a slow public endpoint.
        // Consume results in API order so completion speed never selects the sample.
        try (var pool = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory())) {
            List<Future<Candidate>> futures = new ArrayList<>();
            for (String slug : slugs.stream().limit(12).toList()) futures.add(pool.submit(() -> {
                checkCancelled(cancelled);
                try {
                    JsonNode events = get(GAMMA + "/events?slug=" + slug, evidence);
                    checkCancelled(cancelled);
                    if (!events.isArray() || events.size() != 1) throw new IllegalArgumentException("Event metadata unavailable");
                    return new Candidate(parseGroup(events.get(0), byCondition, now, evidence), null);
                } catch (InterruptedException | CancellationException e) { throw e; }
                catch (Exception e) { return new Candidate(null, safeMessage(e)); }
            }));
            int tokens = 0;
            for (Future<Candidate> future : futures) {
                checkCancelled(cancelled);
                Candidate candidate;
                try { candidate = future.get(); }
                catch (ExecutionException e) {
                    if (e.getCause() instanceof CancellationException c) throw c;
                    if (e.getCause() instanceof InterruptedException i) throw i;
                    throw new IllegalStateException("Public discovery failed", e.getCause());
                }
                if (candidate.group == null) { skipped.merge(candidate.error, 1, Integer::sum); continue; }
                Group group = candidate.group;
                if (groups.size() >= 4) continue;
                if (tokens + group.outcomes().size() * 2 > 160) { skipped.merge("Stream asset limit", 1, Integer::sum); continue; }
                tokens += group.outcomes().size() * 2; groups.add(group);
            }
        }
        return new Scan(groups, skipped);
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new CancellationException("Paper pause requested");
    }
    private static void emit(Consumer<JsonNode> evidence, JsonNode value) {
        synchronized (evidence) { evidence.accept(value); }
    }
    Group parseGroup(JsonNode e, Map<String, JsonNode> rewards, long now, Consumer<JsonNode> evidence) throws Exception {
        String id = e.path("negRiskMarketID").asText();
        if (!e.path("negRisk").asBoolean() || e.path("closed").asBoolean() || !id.matches("0x[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("Not an active CTF negative-risk event");
        JsonNode markets = e.path("markets");
        if (!markets.isArray() || markets.size() < 2 || markets.size() > 40)
            throw new IllegalArgumentException("Unsupported event size");
        int[] chain = verifyAdapter(id, evidence);
        if (chain[0] != markets.size()) throw new IllegalArgumentException("On-chain / metadata question count mismatch");
        List<Outcome> outcomes = new ArrayList<>(); Set<Integer> indexes = new HashSet<>();
        for (JsonNode m : markets) {
            if (!id.equalsIgnoreCase(m.path("negRiskMarketID").asText()) || !m.path("negRisk").asBoolean()
                    || !"v1".equals(m.path("version").asText("v1")))
                throw new IllegalArgumentException("Unsupported conversion contract / position system");
            String qid = m.path("questionID").asText();
            if (!qid.matches("0x[0-9a-fA-F]{64}") || !qid.substring(0, 64).equalsIgnoreCase(id.substring(0, 64)))
                throw new IllegalArgumentException("Question does not belong to adapter market");
            int index = Integer.parseInt(qid.substring(64), 16);
            if (index >= chain[0] || !indexes.add(index)) throw new IllegalArgumentException("Invalid conversion question index");
            List<String> ids = strings(m.path("clobTokenIds")), names = strings(m.path("outcomes"));
            if (ids.size() != 2 || names.size() != 2 || !names.contains("Yes") || !names.contains("No"))
                throw new IllegalArgumentException("Unsupported binary outcome identifiers");
            String yes = ids.get(names.indexOf("Yes")), no = ids.get(names.indexOf("No"));
            if (!yes.matches("[0-9]{1,80}") || !no.matches("[0-9]{1,80}")) throw new IllegalArgumentException("Invalid token ID");
            String name = m.path("groupItemTitle").asText(m.path("question").asText());
            boolean named = named(name) && !m.path("negRiskOther").asBoolean();
            boolean tradable = named && m.path("acceptingOrders").asBoolean() && !m.path("closed").asBoolean();
            double rate = feeRate(m);
            double min = number(m, "orderMinSize"), tick = number(m, "orderPriceMinTickSize");
            if (min <= 0 || tick <= 0 || tick >= 1) throw new IllegalArgumentException("Missing order size / tick metadata");
            JsonNode reward = rewards.get(m.path("conditionId").asText());
            double rewardMin = 0, spread = 0, pool = 0; long until = 0; String asset = "";
            if (reward != null && tradable) {
                rewardMin = number(reward, "rewards_min_size"); spread = number(reward, "rewards_max_spread") / 100.;
                for (JsonNode cfg : reward.path("rewards_config")) {
                    long start = LocalDate.parse(cfg.path("start_date").asText()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                    long end = LocalDate.parse(cfg.path("end_date").asText()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
                    if (start <= now && now < end && number(cfg, "rate_per_day") > 0) {
                        String currency = cfg.path("asset_address").asText();
                        // Only report a single known dollar collateral reward pool; do not sum unrelated assets.
                        if (!Set.of("0x2791bca1f2de4661ed88a30c99a7a9449aa84174", "0xc011a7e12a19f7b1f670d46f03b03f3342e82dfb").contains(currency.toLowerCase())) continue;
                        if (!asset.isEmpty() && !asset.equalsIgnoreCase(currency)) continue;
                        asset = currency; pool += number(cfg, "rate_per_day"); until = until == 0 ? end : Math.min(until, end);
                    }
                }
            }
            long endAt = Instant.parse(m.path("endDate").asText()).toEpochMilli();
            outcomes.add(new Outcome(m.path("conditionId").asText(), name, yes, no, rate, min, tick,
                    rewardMin, spread, pool, asset, until, endAt, tradable));
        }
        if (outcomes.stream().noneMatch(o -> o.tradable() && o.dailyPool() > 0))
            throw new IllegalArgumentException("No active named reward allocation");
        return new Group(id, e.path("slug").asText(), e.path("title").asText(), chain[1], System.currentTimeMillis(), outcomes);
    }
    private int[] verifyAdapter(String id, Consumer<JsonNode> evidence) throws Exception {
        String body = json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "eth_call",
                "params", List.of(Map.of("to", ADAPTER, "data", "0x30f4f4bb" + id.substring(2)), "latest")));
        var response = http.send(HttpRequest.newBuilder(URI.create(RPC)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalArgumentException("Adapter RPC unavailable");
        JsonNode result = json.readTree(response.body());
        emit(evidence, json.valueToTree(Map.of("rpc", RPC, "request", json.readTree(body), "response", result)));
        return decodeMarketData(result.path("result").asText());
    }
    static int[] decodeMarketData(String hex) {
        if (!hex.matches("0x[0-9a-fA-F]{64}") || !hex.substring(4, 6).equals("00")
                || hex.substring(26).matches("0+")) throw new IllegalArgumentException("Unprepared / determined adapter market");
        int count = Integer.parseInt(hex.substring(2, 4), 16), fee = Integer.parseInt(hex.substring(8, 12), 16);
        if (count < 2 || fee > 10_000) throw new IllegalArgumentException("Unsupported adapter fee / question count");
        return new int[]{count, fee};
    }
    static boolean named(String name) {
        return !name.isBlank() && !name.equalsIgnoreCase("Other") && !name.toLowerCase().contains("placeholder")
                && !name.toLowerCase().matches("(party|candidate|outcome) [a-z]");
    }
    static double feeRate(JsonNode m) {
        if (m.path("feesEnabled").isBoolean() && !m.path("feesEnabled").asBoolean()) return 0;
        JsonNode f = m.path("feeSchedule");
        double rate = number(f, "rate");
        if (number(f, "exponent") != 1 || rate < 0 || rate > 1 || !f.path("takerOnly").asBoolean())
            throw new IllegalArgumentException("Unsupported taker fee schedule");
        return rate;
    }
    static double number(JsonNode n, String key) {
        JsonNode v = n.path(key);
        if (v.isMissingNode() || v.isNull()) throw new IllegalArgumentException("Missing " + key);
        double d;
        try { d = Double.parseDouble(v.asText()); } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid " + key); }
        if (!Double.isFinite(d)) throw new IllegalArgumentException("Invalid " + key);
        return d;
    }
    private List<String> strings(JsonNode node) {
        if (node.isTextual()) node = json.readTree(node.asText());
        List<String> out = new ArrayList<>(); if (node.isArray()) node.forEach(v -> out.add(v.asText())); return out;
    }
    public JsonNode book(String token, Consumer<JsonNode> evidence) throws Exception {
        if (!token.matches("[0-9]{1,80}")) throw new IllegalArgumentException("Invalid token ID");
        return get(CLOB + "/book?token_id=" + token, evidence);
    }
    static String safeMessage(Exception e) {
        return e instanceof IllegalArgumentException ? e.getMessage() : "Public market data: " + e.getClass().getSimpleName();
    }
}
