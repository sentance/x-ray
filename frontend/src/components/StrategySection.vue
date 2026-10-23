<script setup lang="ts">
import { computed } from 'vue'
import type { StrategyReport } from '@/api/types'
import { cents, duration, num, pct, usd } from '@/lib/format'
import Card from './Card.vue'
import ColumnChart from './ColumnChart.vue'
import MarketTable from './MarketTable.vue'
import ShareBars from './ShareBars.vue'

const props = defineProps<{ report: StrategyReport }>()
const p = computed(() => props.report.profile)

const HOURS = Array.from({ length: 24 }, (_, h) => String(h).padStart(2, '0'))
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

const facts = computed(() => {
  const { activity, exits, bothSides, entryPrices, tradeSizes, timing } = p.value
  return [
    { label: 'Trades per active day', value: num(activity.tradesPerActiveDay) },
    { label: 'Median trade', value: usd(tradeSizes.p50), hint: `p90 ${usd(tradeSizes.p90)}` },
    { label: 'Median gap between trades', value: duration(timing.medianGapSeconds) },
    { label: 'Average entry price', value: cents(entryPrices.avgEntryPrice) },
    { label: 'Held to resolution', value: pct(exits.heldToResolutionShare), hint: 'of buy volume never sold' },
    { label: 'Sold before resolution', value: pct(exits.sellToBuyRatio), hint: 'sell $ / buy $' },
    { label: 'Both sides bought', value: pct(bothSides.shareOfMarkets), hint: `${num(bothSides.markets)} markets` },
    {
      label: 'Avg pair cost',
      value: bothSides.avgPairCost == null ? '—' : `$${bothSides.avgPairCost.toFixed(3)}`,
      hint: 'YES + NO, below $1 locks profit',
    },
  ]
})
</script>

<template>
  <div class="grid gap-4 lg:grid-cols-2">
    <Card title="How it trades" subtitle="Classifier evidence behind the labels above">
      <dl class="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
        <div v-for="f in facts" :key="f.label">
          <dt class="text-xs text-slate-500">{{ f.label }}</dt>
          <dd class="text-slate-100">
            {{ f.value }} <span v-if="f.hint" class="text-xs text-slate-500">{{ f.hint }}</span>
          </dd>
        </div>
      </dl>
      <div v-if="report.classification.labels.length" class="mt-4 space-y-3 border-t border-slate-800 pt-4">
        <div v-for="l in report.classification.labels" :key="l.name">
          <div class="text-sm font-medium text-slate-100">
            {{ l.name }} <span class="font-normal text-slate-500">· {{ pct(l.confidence) }} confidence</span>
          </div>
          <ul class="mt-1 list-disc space-y-0.5 pl-5 text-sm text-slate-400">
            <li v-for="e in l.evidence" :key="e">{{ e }}</li>
          </ul>
        </div>
      </div>
    </Card>

    <Card title="What it trades" subtitle="Share of volume, with estimated P&L per group">
      <h3 class="mb-2 text-xs font-medium uppercase tracking-wide text-slate-500">Categories</h3>
      <ShareBars :shares="p.categories" />
      <h3 class="mb-2 mt-5 text-xs font-medium uppercase tracking-wide text-slate-500">Market types</h3>
      <ShareBars :shares="p.marketTypes" />
      <template v-if="p.cryptoAssets.some((s) => s.volume > 0)">
        <h3 class="mb-2 mt-5 text-xs font-medium uppercase tracking-wide text-slate-500">Crypto assets</h3>
        <ShareBars :shares="p.cryptoAssets" />
      </template>
    </Card>

    <Card title="Entry prices" :subtitle="`Buy volume by price paid · ${pct(p.entryPrices.favouriteShare)} at ≥ 0.90`">
      <ColumnChart
        :labels="p.entryPrices.buckets.map((b) => b.label)"
        :values="p.entryPrices.buckets.map((b) => b.usdc)"
        :format="(v) => usd(v)"
        value-label="bought"
      />
    </Card>

    <Card title="When it trades" :subtitle="`Trades by hour (UTC) · active in ${p.timing.activeHours} of 24 hours`">
      <ColumnChart :labels="HOURS" :values="p.timing.hourOfDay" :format="(v) => num(v)" value-label="trades" />
      <div class="mt-3 grid grid-cols-7 gap-1 text-center text-xs">
        <div v-for="(d, i) in DAYS" :key="d">
          <div class="text-slate-500">{{ d }}</div>
          <div class="tabular-nums text-slate-300">{{ num(p.timing.dayOfWeek[i]) }}</div>
        </div>
      </div>
    </Card>

    <Card title="Best markets" subtitle="Estimated from cash flows; open markets valued at cost">
      <MarketTable :markets="p.topWinners" />
    </Card>
    <Card title="Worst markets">
      <MarketTable :markets="p.topLosers" />
    </Card>
  </div>
</template>
