package com.xray.paper;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.xray.TestcontainersConfiguration;
import static com.xray.paper.PaperModel.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaperSessionTest {
    @Autowired PaperService service;
    @Autowired PaperRepository repository;
    @MockitoBean PaperMarketData data;

    @Test void runsPausesAndRestoresDurableCashAndEvidenceWithoutUsingLiveEndpoints() throws Exception {
        when(data.discover(any(), any())).thenReturn(new PaperMarketData.Scan(List.of(), Map.of("No fixture candidates", 1)));
        var started = service.create(Settings.defaults());
        UUID id = started.state().id;
        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(service.view(id).state().lastScanAt).isPositive());
            service.pause(id);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(service.view(id).active()).isFalse());
            var restored = repository.find(id).orElseThrow();
            assertThat(restored.cash).isEqualTo(1000); restored.reconcile();
            assertThat(restored.quote).isNull();
            assertThat(service.evidence(id, 0)).anyMatch(e -> e.type().equals("POLICY"));
            var resumed = service.start(id);
            assertThat(resumed.active()).isTrue();
        } finally {
            service.pause(id);
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(service.view(id).active()).isFalse());
        }
    }
    @Test void processRestartInvalidatesQuotesAndNeverInventsDowntimeFills() {
        State s = new State(UUID.randomUUID(), Settings.defaults(), System.currentTimeMillis());
        s.status = "RUNNING"; s.quote = new Quote(); s.quote.reserve = 200;
        repository.save(s, List.of());
        var v = service.view(s.id);
        assertThat(v.state().status).isEqualTo("PAUSED");
        assertThat(v.state().quote).isNull();
        assertThat(v.state().fills).isZero(); assertThat(v.reserved()).isZero();
    }
    @Test void refusesToResumeAnUnhedgedSession() {
        State s = new State(UUID.randomUUID(), Settings.defaults(), System.currentTimeMillis());
        s.positions.put("n", new Position("n", "event", "Named NO", 5, 0, 5));
        repository.save(s, List.of());
        assertThatThrownBy(() -> service.start(s.id)).hasMessageContaining("Unhedged inventory");
    }
}
