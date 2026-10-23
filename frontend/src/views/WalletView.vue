<script setup lang="ts">
import { computed } from 'vue'
import { useWalletReports } from '@/composables/useWalletReports'
import { ago, cents, date, num, shortAddress, usd, walletName } from '@/lib/format'
import BinanceSection from '@/components/BinanceSection.vue'
import Card from '@/components/Card.vue'
import ColumnChart from '@/components/ColumnChart.vue'
import DecaySection from '@/components/DecaySection.vue'
import PnlChart from '@/components/PnlChart.vue'
import SkillSection from '@/components/SkillSection.vue'
import StatTile from '@/components/StatTile.vue'
import StrategySection from '@/components/StrategySection.vue'
import SyncProgress from '@/components/SyncProgress.vue'
import VerdictBadge from '@/components/VerdictBadge.vue'

const props = defineProps<{ address: string }>()
const address = computed(() => props.address.toLowerCase())

const { status, overview, pnl, strategy, skill, decay, error, loadingReports, refresh } = useWalletReports(address)

const job = computed(() => status.value?.job ?? null)
// The Binance test needs crypto buys; it runs by itself for wallets whose labels point at crypto price trading.
const hasCrypto = computed(() => strategy.value?.profile.cryptoAssets.some((s) => s.volume > 0) ?? false)
const cryptoTrader = computed(
  () =>
    strategy.value?.classification.labels.some((l) =>
      ['Crypto latency trader', 'Crypto price-ladder trader'].includes(l.name),
    ) ?? false,
)
const running = computed(() => job.value?.status === 'RUNNING')
const wallet = computed(() => overview.value?.wallet ?? status.value?.wallet ?? null)
const name = computed(() =>
  wallet.value ? walletName(wallet.value) : shortAddress(address.value),
)

const tiles = computed(() => {
  const o = overview.value
  if (!o) return []
  const t = o.totals
  return [
    { label: 'Lifetime P&L', value: usd(t.pnl, { signed: true }), hint: `${usd(t.realizedPnl, { signed: true })} realized` },
    { label: 'Volume', value: usd(t.volume), hint: `${num(t.trades)} trades` },
    { label: 'Profit per $1 traded', value: cents(t.profitPerDollar) },
    { label: 'Fees paid', value: usd(t.fees), hint: `${usd(t.makerRebate + t.takerRebate + t.rewards)} rebates & rewards` },
    { label: 'Open positions', value: usd(o.openPositionsValue), hint: `unrealized ${usd(t.unrealizedPnl, { signed: true })}` },
    { label: 'Active', value: date(t.firstTs), hint: `to ${date(t.lastTs)}` },
  ]
})
</script>

<template>
  <div class="space-y-5">
    <header class="flex flex-wrap items-start justify-between gap-4">
      <div class="flex min-w-0 items-center gap-3">
        <img
          v-if="overview?.profile?.profileImage"
          :src="overview.profile.profileImage"
          alt=""
          class="size-12 shrink-0 rounded-full border border-slate-800 object-cover"
        />
        <div class="min-w-0">
          <h1 class="truncate text-2xl font-semibold text-slate-50">{{ name }}</h1>
          <div class="flex flex-wrap items-center gap-x-3 text-sm text-slate-500">
            <span class="font-mono">{{ shortAddress(address) }}</span>
            <a
              :href="`https://polymarket.com/profile/${address}`"
              target="_blank"
              rel="noopener"
              class="hover:text-sky-300"
              >Polymarket profile ↗</a
            >
            <span v-if="wallet?.lastSyncedAt">synced {{ ago(wallet.lastSyncedAt) }}</span>
          </div>
        </div>
      </div>
      <button
        v-if="status && !running"
        type="button"
        class="rounded-lg border border-slate-700 px-3 py-1.5 text-sm text-slate-300 hover:border-slate-500 hover:text-white"
        @click="refresh"
      >
        Fetch new trades
      </button>
    </header>

    <p v-if="error" class="rounded-lg border border-[#e66767]/40 bg-[#e66767]/10 p-3 text-sm text-[#f0a0a0]" role="alert">
      {{ error }}
    </p>
    <SyncProgress v-if="job && running" :job="job" />
    <p
      v-else-if="job?.status === 'FAILED'"
      class="rounded-lg border border-[#e66767]/40 bg-[#e66767]/10 p-3 text-sm text-[#f0a0a0]"
      role="alert"
    >
      The last sync failed: {{ job.error || 'unknown error' }}. Fetch new trades to resume from where it stopped.
    </p>

    <section v-if="overview" class="space-y-4">
      <div class="rounded-xl border border-slate-800 bg-slate-900 p-4 sm:p-5">
        <div class="flex flex-wrap gap-2">
          <span
            v-for="l in overview.labels"
            :key="l.name"
            :class="[
              'rounded-full px-2.5 py-0.5 text-sm',
              l.kind === 'STRATEGY' ? 'bg-sky-500/15 text-sky-200' : 'bg-slate-800 text-slate-300',
            ]"
            >{{ l.name }}</span
          >
        </div>
        <p class="mt-3 text-slate-200">{{ overview.summary }}</p>
        <div class="mt-3 flex flex-wrap gap-x-6 gap-y-2 text-sm text-slate-400">
          <span>Skill or luck: <VerdictBadge :verdict="overview.skillVerdict" /></span>
          <span>Still working: <VerdictBadge :verdict="overview.decayVerdict" /></span>
        </div>
      </div>

      <div class="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <StatTile v-for="t in tiles" :key="t.label" v-bind="t" />
      </div>
    </section>
    <p v-else-if="loadingReports" class="rounded-xl border border-slate-800 bg-slate-900 p-4 text-sm text-slate-400">
      Working out the strategy summary… this takes a while on big wallets.
    </p>

    <Card v-if="pnl" title="Cumulative P&L" subtitle="Daily, from Polymarket's P&L history · vertical lines mark rule changes">
      <PnlChart v-if="pnl.series.length" :series="pnl.series" :markers="pnl.markers" />
      <p v-else class="text-sm text-slate-500">No P&L history yet.</p>
      <template v-if="pnl.months.length">
        <h3 class="mb-1 mt-5 text-xs font-medium uppercase tracking-wide text-slate-500">P&amp;L by month</h3>
        <ColumnChart
          :labels="pnl.months.map((m) => m.month)"
          :values="pnl.months.map((m) => m.pnl)"
          :format="(v) => usd(v, { signed: true })"
          value-label="P&L"
          polarity
          height="h-48"
        />
      </template>
    </Card>

    <DecaySection v-if="decay" :report="decay" />
    <SkillSection v-if="skill" :report="skill" />
    <BinanceSection v-if="hasCrypto" :address="address" :auto-run="cryptoTrader" />
    <StrategySection v-if="strategy" :report="strategy" />

    <p v-if="!status && !error" class="text-sm text-slate-500">Loading…</p>
  </div>
</template>
