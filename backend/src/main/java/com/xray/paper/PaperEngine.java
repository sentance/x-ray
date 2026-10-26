package com.xray.paper;

import static com.xray.paper.PaperModel.*;
import java.util.*;

/** Deterministic replay engine. No network, credentials, signing, or real order path. */
public class PaperEngine {
    public final State state;
    public final Map<String, Book> books = new HashMap<>();
    private final Set<String> trades = new HashSet<>();
    private final List<Evidence> evidence = new ArrayList<>();
    private long lastTick;
    private record QuoteEvidence(String groupId, String token, String hedgeToken, String name,
                                 double price, double size, double queueAhead, double reserve,
                                 double hedgeLimit, long createdAt, long expiresAt) { }
    private QuoteEvidence snapshot(Quote q) {
        return new QuoteEvidence(q.groupId, q.token, q.hedgeToken, q.name, q.price, q.size,
                q.queueAhead, q.reserve, q.hedgeLimit, q.createdAt, q.expiresAt);
    }

    public PaperEngine(State state) { this.state = state; }
    public void record(long at, String type, Object payload) { evidence.add(new Evidence(at, type, payload)); }
    public List<Evidence> drainEvidence() { var result = List.copyOf(evidence); evidence.clear(); return result; }
    public void restoreEvidence(List<Evidence> batch) { evidence.addAll(0, batch); }
    public void cancel(long at, String reason) {
        if (state.quote != null) {
            record(at, "CANCEL", Map.of("reason", reason, "quote", snapshot(state.quote)));
            state.quote = null;
        }
    }
    public void pause(long at, String reason) {
        cancel(at, reason); state.status = "PAUSED"; state.message = reason;
        state.note(at, "PAUSE", reason); mark(at); state.reconcile();
    }
    public void disconnected(long at, String reason) {
        cancel(at, reason); books.clear(); state.feed = reason;
        record(at, "FEED_GAP", Map.of("reason", reason));
    }
    public void book(String token, List<Level> bids, List<Level> asks, long exchange, long receipt) {
        Book book = books.computeIfAbsent(token, _ -> new Book());
        if (exchange < book.exchangeAt) { cancel(receipt, "Out-of-order book"); return; }
        book.snapshot(bids, asks, exchange, receipt); queueGrowth(token, book, receipt);
    }
    public void change(String token, String side, double price, double size, long exchange, long receipt) {
        Book b = books.get(token);
        if (b == null) return; // A delta cannot initialize a book.
        if (exchange < b.exchangeAt) { cancel(receipt, "Out-of-order book delta"); return; }
        b.level(side, price, size); b.exchangeAt = exchange; b.receivedAt = receipt;
        queueGrowth(token, b, receipt);
    }
    private void queueGrowth(String token, Book b, long at) {
        Quote q = state.quote;
        if (q == null || !q.token.equals(token)) return;
        double visible = b.bids.getOrDefault(q.price, 0.);
        // Cancellations never move us forward. Replenishment is pessimistically put ahead of us.
        q.queueAhead += Math.max(0, visible - q.visibleQueue); q.visibleQueue = visible;
        if (b.ask() <= q.price || !eligible(q, at)) cancel(at, "Quote no longer post-only / reward eligible");
    }
    public void trade(String token, String side, double price, double size, long exchange, long receipt, String key) {
        Quote q = state.quote;
        if (q == null || !q.token.equals(token) || !"SELL".equals(side)) return;
        if (!Double.isFinite(size) || size <= 0 || !Double.isFinite(price)) return;
        if (exchange <= q.createdAt || receipt < q.createdAt + 500 || exchange > receipt + 2000) return;
        if (receipt - exchange > 5000 || receipt >= q.expiresAt) { cancel(receipt, "Stale trade / expired quote"); return; }
        if (!trades.add(key)) return;
        if (trades.size() > 100_000) { pause(receipt, "Trade deduplication capacity reached; start a new session"); return; }
        if (Math.abs(price - q.price) > EPS || !eligible(q, receipt)) return;
        double oldQueue = q.queueAhead;
        q.queueAhead = Math.max(0, oldQueue - size);
        double filled = credit(Math.min(q.size, Math.max(0, size - oldQueue)));
        record(receipt, "QUEUE_TRADE", Map.of("key", key, "size", size, "aheadBefore", oldQueue, "filled", filled));
        if (filled <= EPS) return;
        state.cashflow(receipt, "MAKER_BUY", -debit(filled * q.price), q.name + " NO; simulated queue fill");
        addPosition(q.token, q.groupId, q.name + " NO", filled, outcome(q.token).feeRate(), outcome(q.token).minSize());
        Pending p = new Pending();
        p.groupId = q.groupId; p.token = q.token; p.hedgeToken = q.hedgeToken;
        p.shares = filled; p.makerCost = debit(filled * q.price); p.hedgeLimit = q.hedgeLimit;
        p.reserve = debit(filled * (q.hedgeLimit + outcome(q.hedgeToken).feeRate() * .25) + state.settings.gasPerConversion());
        p.filledAt = receipt; p.hedgeAt = receipt + state.settings.hedgeDelayMs(); p.deadline = p.hedgeAt + 15_000;
        state.pending = p; state.fills++;
        cancel(receipt, "Maker fill; hedge pending, unfilled remainder cancelled");
        state.message = "Maker fill simulated; waiting for hedge latency and fresh depth";
    }
    private Outcome outcome(String token) {
        return state.groups.stream().flatMap(g -> g.outcomes().stream())
                .filter(o -> o.no().equals(token) || o.yes().equals(token)).findFirst().orElseThrow();
    }
    private Group group(String id) { return state.groups.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow(); }
    private boolean eligible(Quote q, long at) {
        Outcome o = outcome(q.token); Book b = books.get(q.token);
        if (b == null || !b.fresh(at) || at >= o.rewardUntil() || at >= o.endAt()) return false;
        Outcome hedge = outcome(q.hedgeToken); Book h = books.get(q.hedgeToken); Group g = group(q.groupId);
        if (h == null || !h.fresh(at) || at >= hedge.endAt() || at - g.verifiedAt() > 300_000) return false;
        Execution execution = h.walk(true, q.size, hedge.feeRate(), q.hedgeLimit, false);
        if (execution == null || debit(q.size * q.price) + execution.buyCost() + state.settings.gasPerConversion()
                - convertedShares(q.size, g.conversionFeeBps()) > state.settings.maxCycleLoss()) return false;
        double mid = b.midpoint(o.rewardMin());
        // Single-sided quoting only in the interior where it can score. Extreme prices need two sides.
        return mid > .10 && mid < .90 && mid >= q.price && mid - q.price < o.rewardSpread()
                && q.size + EPS >= o.rewardMin();
    }
    public void tick(long at) {
        if (!"RUNNING".equals(state.status)) return;
        if (state.quote != null) {
            if (at >= state.quote.expiresAt || !eligible(state.quote, at)) cancel(at, "Expired or stale quote");
            else if (lastTick > 0) state.quoteMillis += Math.min(2000, Math.max(0, at - lastTick));
        }
        lastTick = at;
        if (state.pending != null) hedge(at);
        if (!"RUNNING".equals(state.status)) return;
        sellResiduals(at);
        mark(at);
        if (state.pnl() <= -state.settings.maxLoss()) { pause(at, "Paper loss limit reached"); return; }
        if (state.quote == null && state.pending == null && state.positions.values().stream().noneMatch(p -> p.name.endsWith(" NO"))) quote(at);
        state.updatedAt = at; state.reconcile();
    }
    private void quote(long at) {
        for (Group g : state.groups) {
            if (at - g.verifiedAt() > 300_000) { state.skip("Conversion metadata expired"); continue; }
            for (Outcome maker : g.outcomes()) {
                Book b = books.get(maker.no());
                if (!maker.tradable() || maker.dailyPool() <= 0 || maker.rewardMin() <= 0 || b == null || !b.fresh(at)) continue;
                for (Outcome hedge : g.outcomes()) {
                    if (hedge == maker || !hedge.tradable()) continue;
                    Book h = books.get(hedge.no());
                    if (h == null || !h.fresh(at)) continue;
                    double shares = Math.max(maker.rewardMin(), Math.max(maker.minSize(), hedge.minSize()));
                    double price = Math.floor((b.bid() + EPS) / maker.tick()) * maker.tick();
                    if (price <= 0 || price >= b.ask()) continue;
                    double limit = Math.min(.999999, h.ask() + state.settings.slippage());
                    Execution ex = h.walk(true, shares, hedge.feeRate(), limit, false);
                    if (ex == null) { state.skip("Insufficient hedge ask depth"); continue; }
                    // Reserve a full fill at the hedge limit with worst-case fee, before quoting.
                    double reserve = debit(shares * (price + limit + hedge.feeRate() * .25) + state.settings.gasPerConversion());
                    if (reserve > state.settings.maxCommitted() || reserve > state.cash - state.settings.bankroll() * .1) {
                        state.skip("Insufficient reserved capital"); continue;
                    }
                    double returned = convertedShares(shares, g.conversionFeeBps());
                    double loss = debit(shares * price) + ex.buyCost() + state.settings.gasPerConversion() - returned;
                    // Residual bids and reward pools never fund entry or rescue the loss budget.
                    if (loss > state.settings.maxCycleLoss()) { state.skip("Cycle cash loss exceeds policy"); continue; }
                    Quote q = new Quote(); q.groupId = g.id(); q.token = maker.no(); q.hedgeToken = hedge.no();
                    q.name = maker.name(); q.price = money(price); q.size = shares; q.reserve = reserve; q.hedgeLimit = limit;
                    q.queueAhead = b.bids.getOrDefault(q.price, 0.); q.visibleQueue = q.queueAhead;
                    q.createdAt = at; q.expiresAt = Math.min(at + 30_000, Math.min(maker.rewardUntil(), maker.endAt()));
                    if (!eligible(q, at)) { state.skip("Single-sided reward eligibility / midpoint"); continue; }
                    state.quote = q; record(at, "QUOTE", snapshot(q));
                    state.message = "Resting paper NO quote; full hedge capital reserved"; return;
                }
            }
        }
        state.message = state.positions.isEmpty() ? "Scanning: no qualifying pair within the frozen risk policy" : "Holding residual inventory";
    }
    private void hedge(long at) {
        Pending p = state.pending;
        if (at < p.hedgeAt) return;
        if (at > p.deadline) { pause(at, "Hedge deadline missed; unhedged NO inventory retained"); return; }
        Outcome o = outcome(p.hedgeToken); Book b = books.get(p.hedgeToken);
        if (p.shares + EPS < o.minSize()) { pause(at, "Partial maker fill below hedge minimum; NO inventory retained"); return; }
        if (b == null || !b.fresh(at) || b.receivedAt < p.hedgeAt) return;
        Execution ex = b.walk(true, p.shares, o.feeRate(), p.hedgeLimit, true);
        if (ex == null || ex.shares() <= EPS) return;
        if (ex.shares() + EPS < o.minSize()) return;
        if (ex.buyCost() > state.cash) { pause(at, "Hedge exceeds available cash; NO inventory retained"); return; }
        state.cashflow(at, "HEDGE_BUY", -ex.buyCost(), o.name() + " NO; latency-adjusted ask depth");
        state.fees = money(state.fees + debit(ex.fee()));
        addPosition(o.no(), p.groupId, o.name() + " NO", ex.shares(), o.feeRate(), o.minSize());
        b.consume(true, ex.shares(), p.hedgeLimit);
        record(at, "HEDGE", Map.of("pending", p, "execution", ex));
        if (ex.shares() + EPS < p.shares) {
            p.reserve = Math.max(0, money(p.reserve - ex.buyCost()));
            // A partial taker execution is kept on the balance sheet, never rolled back.
            pause(at, "Partial hedge; both NO inventories retained for review"); return;
        }
        Group g = group(p.groupId);
        double out = convertedShares(p.shares, g.conversionFeeBps());
        state.positions.remove(p.token); state.positions.remove(p.hedgeToken);
        state.cashflow(at, "CONVERSION", out, "Two NO legs → collateral + residual YES; principal recycled");
        state.cashflow(at, "GAS", -debit(state.settings.gasPerConversion()), "Configured paper conversion cost");
        state.gas = money(state.gas + debit(state.settings.gasPerConversion()));
        for (Outcome residual : g.outcomes()) if (!residual.no().equals(p.token) && !residual.no().equals(p.hedgeToken)) {
            addPosition(residual.yes(), g.id(), residual.name() + " YES", out, residual.feeRate(), residual.minSize());
            state.positions.get(residual.yes()).sellable = residual.tradable();
            state.positions.get(residual.yes()).sellAfter = at + state.settings.hedgeDelayMs();
        }
        record(at, "CONVERSION", Map.of("groupId", g.id(), "sharesIn", p.shares, "sharesOut", out, "feeBps", g.conversionFeeBps()));
        state.pending = null; state.conversions++; state.message = "Converted; selling named residual YES inventory";
    }
    private void addPosition(String token, String group, String name, double shares, double rate, double min) {
        Position pos = state.positions.computeIfAbsent(token, _ -> new Position(token, group, name, 0, rate, min));
        pos.shares = money(pos.shares + shares);
    }
    private boolean currentTerms(Position p, long at) {
        if (!p.sellable) return false;
        for (Group g : state.groups) if (g.id().equals(p.groupId) && at - g.verifiedAt() <= 300_000) {
            for (Outcome o : g.outcomes()) if (o.yes().equals(p.token) || o.no().equals(p.token)) {
                if (!o.tradable() || at >= o.endAt()) return false;
                p.feeRate = o.feeRate(); p.minSize = o.minSize(); return true;
            }
        }
        return false;
    }
    private void sellResiduals(long at) {
        // Unhedged NO positions require review; automatic liquidation handles only converted YES inventory.
        for (var it = state.positions.values().iterator(); it.hasNext();) {
            Position p = it.next();
            if (!currentTerms(p, at) || !p.name.endsWith(" YES")) continue;
            Book b = books.get(p.token);
            if (!p.sellable || b == null || !b.fresh(at) || at < p.sellAfter || b.receivedAt < p.sellAfter || p.shares + EPS < p.minSize) continue;
            Execution ex = b.walk(false, p.shares, p.feeRate, .000001, true);
            if (ex.shares() + EPS < p.minSize) continue;
            state.cashflow(at, "RESIDUAL_SELL", ex.sellCredit(), p.name + "; executable bid depth");
            state.fees = money(state.fees + debit(ex.fee()));
            record(at, "RESIDUAL_SELL", Map.of("token", p.token, "execution", ex));
            b.consume(false, ex.shares(), .000001); p.shares = money(p.shares - ex.shares());
            if (p.shares <= EPS) it.remove();
        }
    }
    public void mark(long at) {
        double value = 0;
        for (Position p : state.positions.values()) {
            Book b = books.get(p.token);
            if (!currentTerms(p, at) || b == null || !b.fresh(at) || p.shares + EPS < p.minSize) continue;
            Execution ex = b.walk(false, p.shares, p.feeRate, .000001, true);
            if (ex.shares() + EPS >= p.minSize) value += ex.sellCredit();
        }
        state.liquidationValue = credit(value);
    }
}
