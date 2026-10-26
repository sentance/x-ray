# Akroll paper runner

Open **Paper Trading** (`/paper`) in the X-Ray app, review the limits, and select **Start paper test**. No API keys, wallet connection or funded account are needed. The browser saves session links locally. Keep those links private: the random session UUID grants access to that simulation. There is no public session listing.

The application must keep running to collect data. Closing the browser does not stop the server-side test. **Pause** cancels the paper quote and preserves inventory. A server restart invalidates the old quote and pauses the test until you explicitly resume; downtime never generates fills. A failed or partial hedge retains the actual simulated NO holdings and prevents resuming that session. Export and review it before starting a new test. The loss stop can overshoot after an adverse move, missing bid, or failed hedge.

## Frozen policy `akroll-pair-v1`

This is a first, conservative implementation of the mechanism inferred from the public wallet. It does not copy that trader's private orders or promise the same returns. The initial implementation converts **two NO legs** at a time; it does not search larger baskets or quote both sides at extreme probabilities.

Default simulated cash is $1,000; maximum commitment $400; paper loss stop $100; entry cash loss allowance $2 before residual sales; hedge price allowance 1¢ per share; conversion cost assumption 5¢; execution latency 1 second. These are paper policy defaults, not an agreed live loss tolerance. The form validates and freezes them for each new test. Change a policy by creating a new session, preserving the original evaluation.

1. Read the first 100 public reward markets, sorted by current pool allocation. Inspect at most 12 event slugs; retain at most four supported events and 160 initial stream assets. This is bounded discovery, not an exhaustive search. There is no final-volume filter. Rescan every four minutes, cancelling quotes during the refresh.
2. Only CTF (`v1`) negative-risk events with complete question metadata are supported. Verify the deployed adapter's `getMarketData(bytes32)` via read-only Polygon `eth_call`: initialized oracle, undetermined outcome, question count, conversion fee. Match each question ID and index to its event. Unknown rules, incomplete mappings and Protocol V2 positions are rejected. Proofs are latest-state reads, not atomic with the books.
3. Direct orders in Other, placeholders and unnamed outcomes are excluded. Residual claims in those outcomes remain in inventory with zero liquidation mark. Named residuals need supported fees, minimum order size, and current bid depth to sell. Unknown fee curves reject the event; supported taker fees use `shares × rate × p × (1−p)` at each visible price level, rounding up to five decimals conservatively. The current CTF exchange charges fees in collateral; maker fee is zero. The public book aggregates orders, so per-match rounding and actual execution can differ. See the [fee schedule](https://docs.polymarket.com/trading/fees) and [current exchange fee contract](https://github.com/Polymarket/ctf-exchange-v2/blob/main/src/exchange/mixins/Fees.sol).
4. Subscribe to public book snapshots, depth changes and identifiable trades. Rest one post-only NO bid at an existing bid price, behind all visible size at that price. Use size-cutoff book midpoint, active reward dates, minimum size, maximum spread and interior midpoint `(0.10, 0.90)` to screen approximate single-sided eligibility. This is not an authenticated reward-score check.
5. Select the first passing pair in the returned event/market order. Reserve the entire initiating fill, hedge at its slippage limit with a worst-case fee provision, and conversion cost. Keep at least 10% of initial bankroll in cash. Require depth for the full hedge and an entry cash loss within policy without relying on residual proceeds or rewards. Cancel when hedge depth, price limits, metadata or quote eligibility deteriorate.
6. Only a subsequent, identifiable SELL trade at the exact quote price consumes the modeled queue. Ignore pre-quote, duplicate, late, unidentifiable, wrong-side and different-price trades. Cancellations never reduce queue ahead; replenishments pessimistically go ahead. Quotes expire after 30 seconds or earlier on stale data. This is a simulated queue model, not a claim of a real fill; hidden liquidity, matching priority and undetected stream gaps can differ.
7. Debit only the maker quantity actually modeled as filled. Cancel its unfilled remainder. Wait the configured latency and require new depth before a taker hedge. Respect the hedge limit, fees, minimum size, actual cash and a 15-second hedge window. A partial hedge remains debited and held; it is never rolled back into an imaginary successful basket. A maker partial below hedge minimum pauses with that inventory.
8. After a complete hedge, convert the equal NO quantities. For two legs, credit one unit of collateral per share net of the adapter fee, and the corresponding net YES quantities in all other outcomes. Conversion principal is its own cash ledger entry. Deduct the configured conversion cost separately. The conversion step is modeled; no transaction is submitted. Sell named residuals only against depth received after the conversion plus execution latency, consuming available depth. Unsold and below-minimum quantities remain visible.
9. Separate cash, executable liquidation marks, taker fees and conversion costs. Round debits up and credits down to collateral micro-units and reconcile every checkpoint. Reward, maker rebate and taker rebate credits stay **zero**: a public pool is not the paper account's income. The optional UI incentive scenario is an explicit user assumption per 24 hours of approximately eligible quoting; it never changes cash, reserves or loss controls. Report the zero-incentive result and break-even incentive requirement first.

Books expire after 15 seconds without an update; delayed trades over five seconds old do not fill quotes. A feed disconnect, heartbeat failure or processing backlog cancels the queue and requires fresh snapshots. Unexpected tick/lifecycle changes pause the run. Undetectable missing messages remain a limitation of the public feed, which has no sequence guarantee. Capture ends after 30 days, or after two million WebSocket frames in one runner invocation. Retained assets can extend the initial subscription up to 256 assets; exceeding that bound pauses for review. At most three sessions run simultaneously in one application process. The initial deployment assumes one backend replica.

## Evidence and validation

PostgreSQL stores a session checkpoint and its immutable input/decision batch in one transaction. Evidence contains discovery responses, adapter proof, WebSocket inputs, hedge snapshots, quote/cancel decisions and executions, with receipt timestamps. **Export snapshot** downloads the frozen settings, current state and full cash ledger. Raw evidence is paginated:

```
GET /api/paper/{session-id}/evidence?after=0
```

Each page contains at most 500 records ordered by `seq`; continue with `after` equal to the last sequence number until the page is empty. The `payload` field is serialized JSON. Stop the test before exporting a fixed evaluation set. Checkpoint times and event exchange times are distinct; capture and model limitations are retained in this protocol. No historical profitability claim is made from these tests.

Normal verification:

```sh
cd backend
./gradlew test
cd ../frontend
npm run test:unit -- --run
npm run build
```

The backend requires JDK 25 and a local Docker daemon for temporary PostgreSQL test containers. To additionally test current public discovery, adapter proof, order books and a bounded real WebSocket session (without orders):

```sh
cd backend
XRAY_PAPER_PUBLIC_SMOKE=true ./gradlew test --tests 'com.xray.paper.*'
```

This implementation starts a new prospective experiment. Earlier weather screens and the wallet audit are not out-of-sample validation of its quote policy. At least 30 fresh calendar days and execution/reward evidence are needed before considering a live implementation; the paper test cannot establish an authenticated incentive payout.
