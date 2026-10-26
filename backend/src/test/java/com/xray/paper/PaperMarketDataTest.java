package com.xray.paper;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaperMarketDataTest {
    @Test void decodesVerifiedAdapterLayoutAndRejectsDeterminedOrUnknownMarket() {
        String prepared = "0x0400000064" + "00".repeat(7) + "11".repeat(20);
        assertThat(PaperMarketData.decodeMarketData(prepared)).containsExactly(4, 100);
        assertThatThrownBy(() -> PaperMarketData.decodeMarketData("0x0401000064" + "00".repeat(7) + "11".repeat(20))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaperMarketData.decodeMarketData("0x" + "00".repeat(32))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsUnknownFeeCurvesAndPlaceholderNames() {
        var json = JsonMapper.shared();
        assertThat(PaperMarketData.feeRate(json.readTree("{\"feesEnabled\":false}"))).isZero();
        assertThat(PaperMarketData.feeRate(json.readTree("{\"feeSchedule\":{\"rate\":0.05,\"exponent\":1,\"takerOnly\":true}}"))).isEqualTo(.05);
        assertThatThrownBy(() -> PaperMarketData.feeRate(json.readTree("{\"feeSchedule\":{\"rate\":0.05,\"exponent\":2,\"takerOnly\":true}}"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(PaperMarketData.named("Other")).isFalse();
        assertThat(PaperMarketData.named("Party Z")).isFalse();
        assertThat(PaperMarketData.named("Democrats Sweep")).isTrue();
    }
    @Test void stateRoundTripPreservesFrozenPolicyAndCashLedger() {
        var json = JsonMapper.shared();
        var state = new PaperModel.State(java.util.UUID.randomUUID(), PaperModel.Settings.defaults(), 123);
        state.cashflow(124, "MAKER_BUY", -12.345678, "Simulated");
        var restored = json.readValue(json.writeValueAsString(state), PaperModel.State.class);
        assertThat(restored.cash).isEqualTo(987.654322);
        assertThat(restored.settings).isEqualTo(state.settings); restored.reconcile();
    }
    @Test void unrelatedGlobalLifecycleMessagesDoNotStopOurSession() {
        var json = JsonMapper.shared();
        var groups = java.util.List.of(new PaperModel.Group("risk", "our-event", "Our Event", 0, 1, java.util.List.of()));
        assertThat(PaperService.relevantLifecycle(json.readTree("{\"event_type\":\"new_market\",\"event_message\":{\"slug\":\"elsewhere\"}}"), java.util.Set.of("our-token"), groups)).isFalse();
        assertThat(PaperService.relevantLifecycle(json.readTree("{\"event_type\":\"new_market\",\"event_message\":{\"slug\":\"our-event\"}}"), java.util.Set.of("our-token"), groups)).isTrue();
        assertThat(PaperService.relevantLifecycle(json.readTree("{\"event_type\":\"tick_size_change\",\"asset_id\":\"our-token\"}"), java.util.Set.of("our-token"), groups)).isTrue();
    }
}
