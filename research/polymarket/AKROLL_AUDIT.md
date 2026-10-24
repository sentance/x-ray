# akroll: a promising mechanism, with a misleading headline

Wallet: [0x8bac34bb7b51b4d63b128eb711d76706efdc35ca](https://polymarket.com/profile/0x8bac34bb7b51b4d63b128eb711d76706efdc35ca). Public evidence captured on 2026-10-24. Trading history runs from October 12 through October 24. This is a wallet audit and replication specification, not a live bot or a forecast of future returns.

## Money reconciliation

The complete activity walk contains 4,749 records: 3,975 trades, 658 conversions, 71 merges, 43 reward/rebate payments and two deposits. There are no recorded withdrawals. All cursor walks completed. Positions and the taker-only trade feed were also fully paginated.

| Item | Amount |
|---|---:|
| Deposits | $1,011.682071 |
| Cash now | $2,769.627099 |
| Unique public position marks | $321.175300 |
| Cash + marked positions | $3,090.802399 |
| Cash surplus after net funding | $1,757.945028 |
| Cash + marks − net funding | **$2,079.120328** |
| REWARD payments | $2,367.297800 |
| Maker rebates | $205.721900 |
| Taker rebates | $107.191800 |
| Combined incentives | **$2,680.211500** |
| Cash + marks − funding − incentives | **−$601.091172** |

The activity ledger reconstructs the Polygon pUSD collateral balance to the micro-unit, independently checked with `balanceOf`. The RPC cash snapshot was read at `latest`, not pinned to the API snapshot block; a later historical `eth_call` returned HTTP 403. Snapshot timing remains a limitation. Position marks are API estimates, not guaranteed liquidation proceeds or valuations of every possible unlisted claim.

The API's `OPEN` result includes 332 positions also returned under `REDEEMABLE`. Their $8.365 overlap must not be counted twice. The first commentary estimate of $2,087 profit double-counted those marks; the corrected estimate is $2,079.12. Public statuses combined contain 618 unique position tokens.

Cash accounting, in collateral units:

```
  1,011.682071 deposits
−398,211.621632 buys
+375,086.943880 conversion collateral returned
+ 18,503.993680 merge collateral returned
+  3,698.417600 sells
+  2,680.211500 incentives
=  2,769.627099 cash
```

Returned conversion collateral is recycled principal, not all profit. Separately reported fees are not subtracted again from this ledger: the cash flows already match the collateral balance. Fees may also affect received share quantities. Gas paid from outside the wallet is not captured as its collateral outflow.

The [linked X-Ray report](https://x-ray.aragorn.fyi/wallet/0x8bac34bb7b51b4d63b128eb711d76706efdc35ca) displays $87,566.13 economic P&L. That number is **unreconciled** with the cash-and-public-position estimate, a difference of $85,487.01. The present audit does not establish which upstream valuation/basis calculation creates that gap. It should not be presented as withdrawable profit or used to size a copied strategy. Its “holds to resolution” classification also misses the observed conversion recycling.

## What the trader actually does

- 3,113 of 3,180 buys are NO; NO accounts for 97.74% of buy cash.
- 775 of 795 sells are YES.
- The complete taker feed matches 3,246 fills. The other 729 fills are absent from that complete feed and are inferred maker fills: 723 buys and six sells. Unfilled orders are not public.
- It converts bundles of 1–20 NO outcomes. The median gap from the latest related NO fill to conversion is 12.5 seconds; 646 of 658 conversions occur within 60 seconds of that latest fill. This is not a measurement of the initiating maker order's holding time.

The evidence supports a **passive initiating fill followed by active basket hedging and capital recycling**. It does not reveal the exact quote-placement, inventory-selection or reward-score policy.

For a negative-risk event, converting equal quantities of `k` NO outcomes returns collateral proportional to `k − 1`, plus YES positions in the other outcomes, subject to the adapter's conversion fees. The audited activity's cash amounts imply integer input counts consistently. Inspected Polygon receipts independently confirm conversion credits of $2,627.46 and $2,610.36, and a $269.5734 reward payment. The latest single-NO conversion credits zero cash and produces complementary YES tokens. See the [official conversion contract](https://github.com/Polymarket/neg-risk-ctf-adapter/blob/main/src/NegRiskAdapter.sol).

A clean NBA cycle is particularly revealing:

1. Buy 145.97 NO shares in each of 19 outcomes, for $2,630.379400. Eighteen fills match the taker feed and one is inferred maker.
2. Convert that bundle, receiving $2,627.460000 collateral and YES tokens for the other outcomes.
3. Sell eight resulting YES positions for $3.065370.

Cash difference: **+$0.145970**, before valuing the remaining tokens and any externally paid gas. A 0.1¢ worse price on just one 145.97-share leg would erase that cash margin. Copying delayed public fills is therefore a different trade from earning the initiating maker fill.

Several events are augmented negative-risk events with unnamed placeholders and an explicit Other outcome. Those are not ordinary complete baskets of named candidates. The official [negative-risk documentation](https://docs.polymarket.com/concepts/negative-risk) advises trading named outcomes and ignoring placeholders until named/resolved. Do not value a placeholder's default 50¢ display as an executable bid.

## Incentives are essential to the observed result

The large REWARD payments arrive around midnight UTC, consistent with the documented [liquidity rewards program](https://docs.polymarket.com/programs/liquidity-rewards); smaller incentive payments are not individually attributed to programs here. Maker and taker rebates are identified separately in the public feed. The private reward-per-market breakdown and the wallet's live orders/score are unavailable.

Current public reward settings provide another check: 20 of the 25 highest-allocation markets queried are markets this wallet traded. Examples include Fed October outcomes (200-share minimum), Serbia's two leading named outcomes (150 shares), and Israeli election outcomes (100 shares). Those minimum sizes can fit a $1,000 paper account in selected small baskets, but minimum quote size alone does not establish total hedge funding or eligibility. The displayed $500–$1,373 daily allocations in these examples belong to the whole market pool, not this trader or a replica.

Holding the historical fills and position marks fixed, a simple incentive sensitivity is:

| Fraction of observed incentives retained | Marked net result |
|---|---:|
| 0% | −$601.09 |
| 25% | +$68.96 |
| 50% | +$739.01 |
| 100% | +$2,079.12 |

Break-even is about 22.4% of observed incentives under those fixed assumptions. This is **not a future backtest**: competition, eligibility, fills, prices, gas and inventory could all change simultaneously.

## Reproducibility verdict

This is a more concrete lead than the rejected generic weather rules: the wallet started with approximately the same funding as the proposed $1,000 account and its cash ledger reconciles. The mechanism is worth paper replication. Its historical return, exact orders, and reward share cannot be copied from the public fill feed.

The next implementation should be a rewards-aware maker/hedger with negative-risk conversion accounting. It must measure quote eligibility, actual hedge depth, capital held during partial fills, conversion fees, latency, gas/relayer costs, and unwanted residual positions. Profitability must be assessed after incentives actually received; a configured reward pool is not earned income. A proposed test is in [AKROLL_REPLICA_PROTOCOL.md](AKROLL_REPLICA_PROTOCOL.md).

Evidence: [machine-readable audit](akroll-audit.json). Raw public pages, transaction proofs, current reward settings and source manifest remain in gitignored `data/akroll/`.

To regenerate the audit from those saved sources:

```sh
python3 research/polymarket/audit_math.py --cache research/polymarket/data/akroll
python3 -m unittest discover -s research/polymarket -p 'test_*.py'
```

Forty-seven targeted tests pass. No trading account was connected, no real orders were placed, and the live X-Ray application was not modified or deployed by this audit.
