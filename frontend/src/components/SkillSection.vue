<script setup lang="ts">
import { computed } from 'vue'
import type { SkillReport } from '@/api/types'
import { num, pValue, pct, usd } from '@/lib/format'
import Card from './Card.vue'
import VerdictBadge from './VerdictBadge.vue'

const props = defineProps<{ report: SkillReport }>()

const stats = computed(() => {
  const r = props.report
  return [
    {
      label: 'Green days',
      value: `${pct(r.greenDayPct)} (${num(r.greenDays)} of ${num(r.activeDays)})`,
      hint: `coin-flip chance p ${pValue(r.greenDaysPValue)}`,
    },
    {
      label: 'Longest winning streak',
      value: `${r.longestWinStreak} days`,
      hint: `coin-flip chance p ${pValue(r.streakCoinFlipProbability)}`,
    },
    { label: 'Mean daily P&L', value: usd(r.meanDailyPnl, { signed: true }), hint: `σ ${usd(r.dailyStdDev)}` },
    { label: 't-statistic', value: r.tStat.toFixed(2), hint: `p ${pValue(r.tStatPValue)}` },
    { label: 'Sharpe (annualized)', value: r.sharpeAnnualized.toFixed(2) },
    { label: 'Max drawdown', value: usd(r.maxDrawdown), hint: `${pct(r.maxDrawdownPct)} from peak` },
    { label: 'P&L without best 5 days', value: usd(r.pnlWithoutBest5Days, { signed: true }) },
    {
      label: 'Top 5 markets’ share of profit',
      value: pct(r.top5MarketShare),
      hint: `${num(r.marketsTraded)} markets, ${pct(r.marketWinRate)} won`,
    },
  ]
})
</script>

<template>
  <Card title="Skill or luck" subtitle="Would a coin-flipper plausibly produce this record?">
    <template #aside><VerdictBadge :verdict="report.verdict" /></template>
    <ul class="mb-4 space-y-1 text-sm text-slate-300">
      <li v-for="r in report.reasons" :key="r">{{ r }}</li>
    </ul>
    <dl class="grid grid-cols-2 gap-x-4 gap-y-3 text-sm sm:grid-cols-4">
      <div v-for="s in stats" :key="s.label">
        <dt class="text-xs text-slate-500">{{ s.label }}</dt>
        <dd class="text-slate-100">{{ s.value }}</dd>
        <dd v-if="s.hint" class="text-xs text-slate-500">{{ s.hint }}</dd>
      </div>
    </dl>
  </Card>
</template>
