package com.xray.paper;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import com.xray.TestcontainersConfiguration;

/** Full public-feed smoke, with a deliberately unfundable quote cap; no hypothetical or real orders. */
@EnabledIfEnvironmentVariable(named = "XRAY_PAPER_PUBLIC_SMOKE", matches = "true")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaperLiveSessionTest {
    @Autowired PaperService service;
    @Autowired PaperMarketData data;
    @Test void liveRunnerReceivesPublicBooksAndCheckpointsThem() throws Exception {
        var started = service.create(new PaperModel.Settings(1000, 10, 100, 0, .01, .05, 1000));
        var id = started.state().id;
        try {
            await().atMost(Duration.ofSeconds(240)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
                var v = service.view(id);
                assertThat(v.active()).withFailMessage("Runner stopped: %s; skipped: %s", v.state().message, v.state().skipped).isTrue();
                assertThat(v.state().lastFeedAt).withFailMessage("No feed: %s; feed=%s; groups=%s; skipped=%s",
                        v.state().message, v.state().feed, v.state().groups.size(), v.state().skipped).isPositive();
                assertThat(service.evidence(id, 0)).anyMatch(e -> e.type().equals("WS")
                        && "book".equals(tools.jackson.databind.json.JsonMapper.shared().readTree(e.payload()).path("event_type").asText()));
            });
            var outcome = service.view(id).state().groups.stream().flatMap(g -> g.outcomes().stream())
                    .filter(PaperModel.Outcome::tradable).findFirst().orElseThrow();
            var book = data.book(outcome.no(), n -> { });
            assertThat(book.path("asset_id").asText()).isEqualTo(outcome.no());
            assertThat(book.path("bids").isArray()).isTrue();
        } finally {
            service.pause(id);
            await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> assertThat(service.view(id).active()).isFalse());
        }
    }
}
