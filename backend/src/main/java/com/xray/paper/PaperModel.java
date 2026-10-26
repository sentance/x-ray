package com.xray.paper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import com.fasterxml.jackson.annotation.JsonCreator;

/** Paper-only domain. Monetary debits round up and credits down to collateral micro-units. */
public final class PaperModel {
    private PaperModel() { }
    public static final String POLICY = "akroll-pair-v1";
    public static final double EPS = 0.0000001;
    public static double debit(double n) { return BigDecimal.valueOf(n).setScale(6, RoundingMode.CEILING).doubleValue(); }
    public static double credit(double n) { return BigDecimal.valueOf(n).setScale(6, RoundingMode.FLOOR).doubleValue(); }
    public static double money(double n) { return BigDecimal.valueOf(n).setScale(6, RoundingMode.HALF_EVEN).doubleValue(); }
    public static double convertedShares(double shares, int feeBps) {
        BigDecimal amount = BigDecimal.valueOf(shares).setScale(6, RoundingMode.DOWN);
        BigDecimal fee = amount.multiply(BigDecimal.valueOf(feeBps)).divide(BigDecimal.valueOf(10_000), 6, RoundingMode.DOWN);
        return amount.subtract(fee).doubleValue();
    }

    public record Settings(double bankroll, double maxCommitted, double maxLoss, double maxCycleLoss,
                           double slippage, double gasPerConversion, int hedgeDelayMs) {
        public Settings {
            double[] ns = {bankroll, maxCommitted, maxLoss, maxCycleLoss, slippage, gasPerConversion};
            for (double n : ns) if (!Double.isFinite(n)) throw new IllegalArgumentException("Settings must be finite");
            if (bankroll < 100 || bankroll > 100_000 || maxCommitted < 10 || maxCommitted > bankroll * .9
                    || maxLoss <= 0 || maxLoss > bankroll || maxCycleLoss < 0 || maxCycleLoss > maxLoss
                    || slippage < .001 || slippage > .05 || gasPerConversion < 0 || gasPerConversion > 10
                    || hedgeDelayMs < 1000 || hedgeDelayMs > 30_000)
                throw new IllegalArgumentException("Invalid paper risk limits");
        }
        public static Settings defaults() { return new Settings(1000, 400, 100, 2, .01, .05, 1000); }
    }

    public record Outcome(String conditionId, String name, String yes, String no, double feeRate,
                          double minSize, double tick, double rewardMin, double rewardSpread,
                          double dailyPool, String rewardAsset, long rewardUntil, long endAt, boolean tradable) { }
    public record Group(String id, String slug, String title, int conversionFeeBps, long verifiedAt,
                        List<Outcome> outcomes) { }
    public record Level(double price, double size) { }
    public record Execution(double shares, double notional, double fee) {
        public double buyCost() { return debit(notional + fee); }
        public double sellCredit() { return credit(Math.max(0, notional - fee)); }
    }

    public static class Book {
        public final NavigableMap<Double, Double> bids = new TreeMap<>(Comparator.reverseOrder());
        public final NavigableMap<Double, Double> asks = new TreeMap<>();
        public long receivedAt, exchangeAt;
        public void snapshot(List<Level> bid, List<Level> ask, long exchange, long receipt) {
            bids.clear(); asks.clear();
            bid.forEach(l -> level("BUY", l.price, l.size));
            ask.forEach(l -> level("SELL", l.price, l.size));
            exchangeAt = exchange; receivedAt = receipt;
        }
        public void level(String side, double p, double s) {
            if (!Set.of("BUY", "SELL").contains(side)) throw new IllegalArgumentException("Invalid book side");
            if (!Double.isFinite(p) || !Double.isFinite(s) || p <= 0 || p >= 1 || s < 0)
                throw new IllegalArgumentException("Invalid book level");
            var levels = "BUY".equals(side) ? bids : asks;
            if (s == 0) levels.remove(p); else levels.put(p, s);
        }
        public double bid() { return bids.isEmpty() ? 0 : bids.firstKey(); }
        public double ask() { return asks.isEmpty() ? 1 : asks.firstKey(); }
        public boolean fresh(long now) { return receivedAt > 0 && now - receivedAt <= 15_000 && now - exchangeAt <= 15_000 && exchangeAt <= now + 2000 && bid() < ask(); }
        /** Size-cutoff midpoint, rather than a dust top-of-book midpoint. */
        public double midpoint(double size) {
            double b = cutoff(bids, size), a = cutoff(asks, size);
            return b > 0 && a < 1 && b < a ? (b + a) / 2 : Double.NaN;
        }
        private static double cutoff(NavigableMap<Double, Double> levels, double size) {
            double total = 0;
            for (var l : levels.entrySet()) { total += l.getValue(); if (total + EPS >= size) return l.getKey(); }
            return levels.comparator() == null ? 1 : 0;
        }
        public Execution walk(boolean buy, double shares, double rate, double limit, boolean partial) {
            double left = shares, notional = 0, fee = 0;
            for (var l : (buy ? asks : bids).entrySet()) {
                double p = l.getKey();
                if ((buy && p > limit + EPS) || (!buy && p < limit - EPS)) break;
                double q = Math.min(left, l.getValue());
                notional += q * p;
                // Public fees have 5 decimal precision. Round each visible level up conservatively.
                fee += BigDecimal.valueOf(q * rate * p * (1 - p)).setScale(5, RoundingMode.CEILING).doubleValue();
                left -= q;
                if (left <= EPS) break;
            }
            return left <= EPS || partial ? new Execution(shares - left, notional, fee) : null;
        }
        public void consume(boolean buy, double shares, double limit) {
            var levels = buy ? asks : bids;
            for (var it = levels.entrySet().iterator(); it.hasNext() && shares > EPS;) {
                var l = it.next();
                if ((buy && l.getKey() > limit + EPS) || (!buy && l.getKey() < limit - EPS)) break;
                double q = Math.min(shares, l.getValue()); shares -= q;
                if (q + EPS >= l.getValue()) it.remove(); else l.setValue(l.getValue() - q);
            }
        }
    }

    public static class Quote {
        public String groupId, token, hedgeToken, name;
        public double price, size, queueAhead, visibleQueue, reserve, hedgeLimit;
        public long createdAt, expiresAt;
    }
    public static class Pending {
        public String groupId, token, hedgeToken;
        public double shares, makerCost, hedgeLimit, reserve;
        public long filledAt, hedgeAt, deadline;
    }
    public static class Position {
        public String token, groupId, name;
        public double shares, feeRate, minSize;
        public boolean sellable = true;
        public long sellAfter;
        public Position() { }
        public Position(String token, String groupId, String name, double shares, double feeRate, double minSize) {
            this.token = token; this.groupId = groupId; this.name = name;
            this.shares = shares; this.feeRate = feeRate; this.minSize = minSize;
        }
    }
    public record Entry(long at, String type, double amount, String detail) { }
    public record Evidence(long receivedAt, String type, Object payload) { }

    public static class State {
        public UUID id;
        public String policy = POLICY, status = "PAUSED", message = "Ready to start", feed = "Disconnected";
        public Settings settings;
        public long createdAt, updatedAt, lastScanAt, lastFeedAt, quoteMillis;
        public double cash, fees, gas, rewards, makerRebates, takerRebates, liquidationValue;
        public long fills, conversions;
        public Quote quote;
        public Pending pending;
        public List<Group> groups = new ArrayList<>();
        public Map<String, Position> positions = new LinkedHashMap<>();
        public List<Entry> ledger = new ArrayList<>();
        public Map<String, Integer> skipped = new TreeMap<>();
        @JsonCreator public State() { }
        public State(UUID id, Settings settings, long now) {
            this.id = id; this.settings = settings; this.cash = settings.bankroll;
            this.createdAt = now; this.updatedAt = now;
        }
        public double equity() { return money(cash + liquidationValue); }
        public double pnl() { return money(equity() - settings.bankroll); }
        public double reserved() { return quote != null ? quote.reserve : pending != null ? pending.reserve : 0; }
        public void skip(String reason) { skipped.merge(reason, 1, Integer::sum); }
        public void cashflow(long at, String type, double amount, String detail) {
            amount = money(amount); cash = money(cash + amount);
            ledger.add(new Entry(at, type, amount, detail));
        }
        public void note(long at, String type, String detail) { ledger.add(new Entry(at, type, 0, detail)); }
        public void reconcile() {
            double expected = settings.bankroll + ledger.stream().mapToDouble(Entry::amount).sum();
            if (!Double.isFinite(cash) || cash < -EPS || Math.abs(expected - cash) > .000002)
                throw new IllegalStateException("Paper cash ledger does not reconcile");
        }
    }
}
