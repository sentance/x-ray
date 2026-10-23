<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { axisLabel, chart, tooltip, tooltipRow } from '@/charts/echarts'
import type { BinanceWindow } from '@/api/types'
import { pct } from '@/lib/format'

/** Aligned share per window, by buys and by shares, against the 50% a coin flip would score. */
const props = defineProps<{ windows: BinanceWindow[] }>()

const SERIES = [
  { name: 'by buys', color: chart.series, value: (w: BinanceWindow) => w.alignedShare },
  { name: 'by shares', color: chart.series2, value: (w: BinanceWindow) => w.sharesAligned },
]

const shortLabel = (label: string) =>
  label === 'Since the window opened'
    ? 'since open'
    : label.replace(' min before', 'm before').replace(' min after', 'm after')

const option = computed(() => ({
  grid: { left: 8, right: 8, top: 16, bottom: 8, containLabel: true },
  tooltip: {
    ...tooltip,
    trigger: 'axis',
    axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(255,255,255,0.04)' } },
    formatter: (params: { dataIndex: number }[]) => {
      const w = props.windows[params[0]?.dataIndex ?? 0]
      if (!w) return ''
      return [w.label, ...SERIES.map((s) => tooltipRow(pct(s.value(w)), `aligned ${s.name}`, s.color))].join('<br/>')
    },
  },
  xAxis: {
    type: 'category',
    data: props.windows.map((w) => shortLabel(w.label)),
    axisLine: { lineStyle: { color: chart.axis } },
    axisTick: { show: false },
    axisLabel,
  },
  yAxis: {
    type: 'value',
    min: 0,
    max: 1,
    axisLabel: { ...axisLabel, formatter: (v: number) => pct(v) },
    splitLine: { lineStyle: { color: chart.grid, width: 1 } },
  },
  series: SERIES.map((s, i) => ({
    type: 'bar',
    name: s.name,
    barMaxWidth: 16,
    // The 2px surface gap between neighbouring bars comes from barGap.
    barGap: '15%',
    data: props.windows.map((w) => s.value(w)),
    itemStyle: { color: s.color, borderRadius: [4, 4, 0, 0] },
    emphasis: { itemStyle: { opacity: 0.85 } },
    markLine:
      i === 0
        ? {
            silent: true,
            symbol: 'none',
            lineStyle: { color: chart.muted, width: 1, type: 'solid' },
            label: { color: chart.muted, fontSize: 10, position: 'insideEndTop', formatter: 'coin flip' },
            data: [{ yAxis: 0.5 }],
          }
        : undefined,
  })),
}))
</script>

<template>
  <div>
    <ul class="mb-1 flex gap-4 text-xs text-slate-400">
      <li v-for="s in SERIES" :key="s.name" class="flex items-center gap-1.5">
        <span class="inline-block size-2.5 rounded-sm" :style="{ background: s.color }" aria-hidden="true" />
        Aligned {{ s.name }}
      </li>
    </ul>
    <div class="h-52 w-full"><VChart :option="option" autoresize /></div>
  </div>
</template>
