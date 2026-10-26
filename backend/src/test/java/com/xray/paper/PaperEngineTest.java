package com.xray.paper;

import static com.xray.paper.PaperModel.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaperEngineTest {
    static final long NOW = 1_800_000_000_000L;
    Outcome outcome(String name, double reward, boolean tradable) {
        return new Outcome(name, name, name + "Y", name + "N", 0, 5, .01,
                reward, .05, reward > 0 ? 100 : 0, "USDC", NOW + 100_000, NOW + 200_000, tradable);
    }
    PaperEngine engine(int fee) {
        State s = new State(UUID.randomUUID(), Settings.defaults(), NOW);
        s.status = "RUNNING";
        s.groups.add(new Group("event", "event", "Event", fee, NOW,
                List.of(outcome("A", 100, true), outcome("B", 0, true), outcome("C", 0, true))));
        PaperEngine e = new PaperEngine(s);
        book(e, "AN", .4, 20, .42, 500, NOW);
        book(e, "BN", .58, 500, .6, 500, NOW);
        book(e, "CY", .02, 500, .04, 500, NOW);
        return e;
    }
    void book(PaperEngine e, String token, double bid, double bidSize, double ask, double askSize, long at) {
        e.book(token, List.of(new Level(bid, bidSize), new Level(bid - .01, 500)), List.of(new Level(ask, askSize)), at, at);
    }
    void fill(PaperEngine e, double size, long at) { e.trade("AN", "SELL", .4, size, at, at, "tx-" + at); }

    @Test void reservesFullBasketWithoutSpendingCashOnQuote() {
        PaperEngine e = engine(0); e.tick(NOW);
        assertThat(e.state.quote).isNotNull();
        assertThat(e.state.quote.size).isEqualTo(100);
        assertThat(e.state.quote.queueAhead).isEqualTo(20);
        assertThat(e.state.reserved()).isBetween(101., 102.);
        assertThat(e.state.cash).isEqualTo(1000);
        assertThat(e.state.rewards).isZero();
    }
    @Test void queueDepletionThenPartialFillOnlyFillsExcessTradeSize() {
        PaperEngine e = engine(0); e.tick(NOW);
        fill(e, 15, NOW + 1000);
        assertThat(e.state.pending).isNull();
        assertThat(e.state.quote.queueAhead).isEqualTo(5);
        fill(e, 15, NOW + 2000);
        assertThat(e.state.pending.shares).isEqualTo(10);
        assertThat(e.state.positions.get("AN").shares).isEqualTo(10);
        assertThat(e.state.cash).isEqualTo(996);
        assertThat(e.state.quote).isNull();
    }
    @Test void ignoresDuplicateWrongSidePreQuoteAndWrongPriceTrades() {
        PaperEngine e = engine(0); e.tick(NOW);
        e.trade("AN", "SELL", .4, 1000, NOW - 1, NOW + 1000, "pre");
        e.trade("AN", "BUY", .4, 1000, NOW + 1000, NOW + 1000, "buy");
        e.trade("AN", "SELL", .39, 1000, NOW + 1000, NOW + 1000, "wrong-price");
        fill(e, 10, NOW + 1000); fill(e, 10, NOW + 1000);
        assertThat(e.state.quote.queueAhead).isEqualTo(10);
        assertThat(e.state.cash).isEqualTo(1000);
    }
    @Test void cancellationsNeverReduceQueueAndReplenishmentGoesAhead() {
        PaperEngine e = engine(0); e.tick(NOW);
        e.change("AN", "BUY", .4, 5, NOW + 100, NOW + 100);
        assertThat(e.state.quote.queueAhead).isEqualTo(20);
        e.change("AN", "BUY", .4, 15, NOW + 200, NOW + 200);
        assertThat(e.state.quote.queueAhead).isEqualTo(30);
    }
    @Test void convertsOnlyAfterLatencyAndFreshHedgeThenSellsResidualAtBid() {
        PaperEngine e = engine(0); e.tick(NOW); fill(e, 120, NOW + 1000);
        e.tick(NOW + 1999); assertThat(e.state.conversions).isZero();
        e.tick(NOW + 2000); assertThat(e.state.conversions).isZero(); // Pre-latency depth is insufficient evidence.
        book(e, "BN", .58, 500, .6, 500, NOW + 2100); e.tick(NOW + 2100);
        assertThat(e.state.conversions).isEqualTo(1);
        assertThat(e.state.pending).isNull();
        assertThat(e.state.positions.get("CY").shares).isEqualTo(100);
        book(e, "CY", .02, 500, .04, 500, NOW + 3200); e.tick(NOW + 3200);
        assertThat(e.state.positions).isEmpty();
        assertThat(e.state.cash).isCloseTo(1001.95, within(.000002));
        assertThat(e.state.gas).isEqualTo(.05);
        assertThat(e.state.rewards + e.state.makerRebates + e.state.takerRebates).isZero();
        e.state.reconcile();
    }
    @Test void conversionFeeReducesCashAndResidualSharesAndKeepsPrincipalSeparate() {
        PaperEngine e = engine(100); e.tick(NOW); fill(e, 120, NOW + 1000);
        book(e, "BN", .58, 500, .6, 500, NOW + 2100); e.tick(NOW + 2100);
        book(e, "CY", .02, 500, .04, 500, NOW + 3200); e.tick(NOW + 3200);
        assertThat(e.state.cash).isCloseTo(1000.93, within(.000002));
        assertThat(e.state.ledger).anySatisfy(l -> { assertThat(l.type()).isEqualTo("CONVERSION"); assertThat(l.amount()).isEqualTo(99); });
        e.state.reconcile();
    }
    @Test void partialHedgeIsDebitedAndRetainedWithoutFictitiousConversion() {
        PaperEngine e = engine(0); e.tick(NOW); fill(e, 120, NOW + 1000);
        book(e, "BN", .58, 500, .6, 10, NOW + 2100); e.tick(NOW + 2100);
        assertThat(e.state.status).isEqualTo("PAUSED");
        assertThat(e.state.conversions).isZero();
        assertThat(e.state.positions.get("AN").shares).isEqualTo(100);
        assertThat(e.state.positions.get("BN").shares).isEqualTo(10);
        assertThat(e.state.cash).isEqualTo(954);
        e.state.reconcile();
    }
    @Test void belowMinimumFillPausesWithTheActualUnhedgedInventory() {
        PaperEngine e = engine(0); e.tick(NOW); fill(e, 21, NOW + 1000); e.tick(NOW + 2000);
        assertThat(e.state.status).isEqualTo("PAUSED");
        assertThat(e.state.positions.get("AN").shares).isEqualTo(1);
        assertThat(e.state.cash).isEqualTo(999.6);
    }
    @Test void gapAndStaleTradesCancelRatherThanInventingFills() {
        PaperEngine e = engine(0); e.tick(NOW);
        e.trade("AN", "SELL", .4, 120, NOW + 1000, NOW + 7000, "stale");
        assertThat(e.state.quote).isNull(); assertThat(e.state.fills).isZero();
        e.tick(NOW + 7100); assertThat(e.state.quote).isNotNull();
        e.disconnected(NOW + 7200, "Gap");
        assertThat(e.state.quote).isNull(); assertThat(e.books).isEmpty();
    }
    @Test void unknownAndBelowMinimumResidualMarksAreZero() {
        PaperEngine e = engine(0);
        e.state.positions.put("phantom", new Position("phantom", "event", "Other YES", 100, 0, 5));
        book(e, "phantom", .5, 1000, .6, 1000, NOW);
        e.state.positions.get("phantom").sellable = false;
        e.state.positions.put("dust", new Position("dust", "event", "Dust YES", 1, 0, 5));
        book(e, "dust", .5, 1000, .6, 1000, NOW);
        e.mark(NOW); assertThat(e.state.liquidationValue).isZero();
    }
    @Test void insufficientCapitalAndExtremeSingleSidedQuoteAreSkipped() {
        PaperEngine e = engine(0);
        e.state.settings = new Settings(1000, 50, 100, 2, .01, .05, 1000); e.tick(NOW);
        assertThat(e.state.quote).isNull();
        e.state.settings = Settings.defaults();
        book(e, "AN", .04, 1000, .06, 1000, NOW + 1000); e.tick(NOW + 1000);
        assertThat(e.state.quote).isNull();
    }
    @Test void ledgerMismatchAndInvalidSettingsAreRejected() {
        PaperEngine e = engine(0); e.state.cash = 1100;
        assertThatThrownBy(e.state::reconcile).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Settings(Double.NaN, 400, 100, 2, .01, .05, 1000)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disappearingHedgeDepthCancelsTheMakerQuote() {
        PaperEngine e = engine(0); e.tick(NOW);
        book(e, "BN", .58, 500, .65, 500, NOW + 1000);
        fill(e, 120, NOW + 1100); e.tick(NOW + 1100);
        assertThat(e.state.fills).isZero(); assertThat(e.state.quote).isNull();
    }
    @Test void unknownBookSideIsRejectedRatherThanAddedToAsks() {
        Book b = new Book();
        assertThatThrownBy(() -> b.level("UNKNOWN", .5, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThat(b.asks).isEmpty();
    }
    @Test void conversionFeeRoundsDownInShareMicroUnitsLikeTheAdapter() {
        assertThat(convertedShares(1.000001, 333)).isEqualTo(.966701);
        assertThat(convertedShares(145.97, 0)).isEqualTo(145.97);
    }
    @Test void quoteEvidencePreservesItsInitialQueueWhileTheLiveQueueChanges() {
        PaperEngine e = engine(0); e.tick(NOW);
        var evidence = e.drainEvidence(); fill(e, 10, NOW + 1000);
        var json = tools.jackson.databind.json.JsonMapper.shared();
        var stored = json.readTree(json.writeValueAsString(evidence.getFirst()));
        assertThat(stored.path("payload").path("queueAhead").asDouble()).isEqualTo(20);
        assertThat(e.state.quote.queueAhead).isEqualTo(10);
    }
    @Test void takerFeesAndDepthAreAppliedPerLevel() {
        Book b = new Book();
        b.snapshot(List.of(new Level(.4, 10)), List.of(new Level(.5, 5), new Level(.6, 5)), NOW, NOW);
        Execution ex = b.walk(true, 10, .05, .6, false);
        assertThat(ex.buyCost()).isCloseTo(5.6225, within(.000002));
        assertThat(b.walk(true, 11, .05, .6, false)).isNull();
        assertThat(b.walk(true, 11, .05, .6, true).shares()).isEqualTo(10);
    }
    @Test void lossStopCancelsQuoteButDoesNotErasePositions() {
        PaperEngine e = engine(0); e.tick(NOW);
        e.state.cashflow(NOW, "LOSS", -101, "Test loss");
        e.state.positions.put("unknown", new Position("unknown", "event", "C YES", 100, 0, 5));
        e.tick(NOW + 1000);
        assertThat(e.state.status).isEqualTo("PAUSED"); assertThat(e.state.quote).isNull();
        assertThat(e.state.positions).containsKey("unknown"); e.state.reconcile();
    }
}
