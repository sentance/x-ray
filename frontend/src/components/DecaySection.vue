<script setup lang="ts">
import type { DecayReport } from '@/api/types'
import { cents, num, pct, usd } from '@/lib/format'
import Card from './Card.vue'
import ColumnChart from './ColumnChart.vue'
import VerdictBadge from './VerdictBadge.vue'

defineProps<{ report: DecayReport }>()
</script>

<template>
  <Card title="Still working?" :subtitle="report.explanation">
    <template #aside><VerdictBadge :verdict="report.verdict" /></template>
    <div class="grid gap-6 lg:grid-cols-2">
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead class="text-left text-xs text-slate-500">
            <tr>
              <th class="pb-2 font-normal">Window</th>
              <th class="pb-2 text-right font-normal">P&amp;L</th>
              <th class="pb-2 text-right font-normal">Per $1</th>
              <th class="pb-2 text-right font-normal">Green days</th>
              <th class="hidden pb-2 text-right font-normal sm:table-cell">Fees / gross</th>
              <th class="hidden pb-2 text-right font-normal sm:table-cell">Trades</th>
            </tr>
          </thead>
          <tbody class="tabular-nums">
            <tr v-for="w in report.windows" :key="w.label" class="border-t border-slate-800">
              <td class="py-2 text-slate-300">{{ w.label }}</td>
              <td class="py-2 text-right text-slate-100">{{ usd(w.pnl, { signed: true }) }}</td>
              <td class="py-2 text-right text-slate-100">{{ cents(w.profitPerDollar) }}</td>
              <td class="py-2 text-right text-slate-400">{{ pct(w.greenDayPct) }}</td>
              <td class="hidden py-2 text-right text-slate-400 sm:table-cell">{{ pct(w.feeShare) }}</td>
              <td class="hidden py-2 text-right text-slate-400 sm:table-cell">{{ num(w.trades) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="report.monthly.length">
        <h3 class="mb-1 text-xs font-medium uppercase tracking-wide text-slate-500">Profit per $1 traded, by month</h3>
        <ColumnChart
          :labels="report.monthly.map((m) => m.month)"
          :values="report.monthly.map((m) => m.profitPerDollar)"
          :format="(v) => cents(v)"
          value-label="per $1 traded"
          polarity
          height="h-48"
        />
      </div>
    </div>
  </Card>
</template>
