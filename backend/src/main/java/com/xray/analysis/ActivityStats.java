package com.xray.analysis;

import java.util.List;

import com.xray.analysis.ActivityStatsRepository.Bucket;
import com.xray.analysis.ActivityStatsRepository.SizePercentiles;
import com.xray.analysis.ActivityStatsRepository.Timing;
import com.xray.analysis.ActivityStatsRepository.TypeTotal;

/**
 * The activity aggregates a {@link StrategyProfile} is built from. Computed in SQL over a wallet's full stored
 * history ({@link ActivityStatsRepository#forWallet}) or in memory over a sample of recent activity
 * ({@link SampleStats}).
 */
public interface ActivityStats {

	List<TypeTotal> typeTotals();

	Timing timing();

	/** Buy volume by entry price. */
	List<Bucket> entryPriceBuckets();

	/** Buy volume by how long before the market's end date it was placed. */
	List<Bucket> timeToExpiryBuckets();

	Double medianSecondsToExpiry();

	SizePercentiles tradeSizes();
}
