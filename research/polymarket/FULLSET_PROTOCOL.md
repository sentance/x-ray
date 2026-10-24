# Exhaustive weather-bin basket — exploratory forward screen

Fixed 2026-10-24 before reading basket returns. Universe: today's complete daily highest-temperature events with supported AWC rules, ordinary negative-risk status, and integer temperature bins that form one exhaustive, non-overlapping partition. Exclude augmented/other outcomes, partial universes, unknown fees, inconsistent station/source/unit, and non-accepting markets.

Hypothesis: buying equal YES shares of every bin can sometimes cost less than the one winning share set pays. Unlike the earlier YES+NO pair check, this compares different market books within one event.

Size: ten YES shares per bin. Conditional basket payout: $10 if the event resolves consistently to one bin. Consume actual ask depth and current fees on every leg; require every minimum size. Entry margin: total cost <=$9.90, preserving at least 1¢ per complete set. Capture all outcomes and rejected baskets, not just positive ones. No rewards or assumed midpoint fills.

For a positive basket, re-read event metadata and all YES books. Revalidate rules, partition, token IDs, accepting-orders state and fees; require the complete recheck book-request window <=2 seconds. A batch is not atomic. Conditional surplus is not realized P&L: failed legs, partial execution, stale quotes, source disputes, and settlement costs remain unresolved. A ten-share basket costs up to $9.90 in aggregate, not $9.90 per bin.

This tool is a read-only quote screen. It does not submit orders or count simulated fills. Repeated observations of one event are not independent profit opportunities.

```sh
python3 research/polymarket/fullsets.py --date 2026-10-24
```
