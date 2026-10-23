package com.xray.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.xray.TestcontainersConfiguration;
import com.xray.client.PolymarketClient;
import com.xray.client.PolymarketDtos.Activity;
import com.xray.ingest.SyncJobRepository.Status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WalletSyncServiceTest {

	private static final String WALLET = "0x00000000000000000000000000000000000000aa";

	@MockitoBean
	PolymarketClient polymarket;

	@Autowired
	WalletSyncService sync;

	@Autowired
	SyncJobRepository jobs;

	@Autowired
	JdbcClient jdbc;

	/** Mimics the real endpoint: ascending, start inclusive, offset capped. */
	private void serveActivity(List<Activity> all) {
		when(polymarket.activityAscending(eq(WALLET), anyLong(), anyInt())).thenAnswer(inv -> {
			long start = inv.getArgument(1);
			int offset = inv.getArgument(2);
			if (offset > PolymarketClient.MAX_ACTIVITY_OFFSET) {
				throw new IllegalArgumentException("max historical activity offset exceeded");
			}
			return all.stream()
				.filter(a -> a.timestamp() >= start)
				.sorted(Comparator.comparingLong(Activity::timestamp))
				.skip(offset)
				.limit(PolymarketClient.ACTIVITY_PAGE_SIZE)
				.toList();
		});
		when(polymarket.profile(anyString())).thenReturn(Optional.empty());
		when(polymarket.dailyPnl(anyString())).thenReturn(List.of());
		when(polymarket.openPositions(anyString(), anyInt(), anyInt())).thenReturn(List.of());
		when(polymarket.events(anyList())).thenReturn(List.of());
	}

	private static Activity trade(long ts, int i) {
		return new Activity(WALLET, ts, "0xc" + (i % 7), "TRADE", 1.0 + i, 0.5, "0xtx" + i, 0.5, "a", "BUY", 0, "t",
				"s", "e", "Yes");
	}

	private long storedRows() {
		return jdbc.sql("SELECT count(*) FROM activity WHERE address = ?").param(WALLET).query(Long.class).single();
	}

	private void syncAndWait() {
		long id = sync.start(WALLET).id();
		await().atMost(Duration.ofSeconds(30))
			.until(() -> jobs.find(id).orElseThrow().status() != Status.RUNNING);
		assertThat(jobs.find(id).orElseThrow().status()).isEqualTo(Status.DONE);
	}

	@Test
	void walksPastOffsetCapWithoutLosingOrDuplicatingRows() {
		List<Activity> all = new ArrayList<>();
		int n = 0;
		// 12,000 rows: runs of 700 at one timestamp straddle page and window boundaries,
		// and every 1000th row is an exact duplicate fill of its predecessor.
		for (long ts = 1000; n < 12_000; ts++) {
			int runLength = ts % 3 == 0 ? 700 : 40;
			for (int k = 0; k < runLength && n < 12_000; k++, n++) {
				all.add(n % 1000 == 999 ? trade(ts, n - 1) : trade(ts, n));
			}
		}
		serveActivity(all);

		syncAndWait();
		assertThat(storedRows()).isEqualTo(12_000);

		all.add(trade(999_999, 99_999));
		syncAndWait();
		assertThat(storedRows()).isEqualTo(12_001);
	}
}
