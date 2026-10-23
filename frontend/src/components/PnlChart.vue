<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { axisLabel, chart, tooltip, tooltipRow } from '@/charts/echarts'
import type { Marker, PnlPoint } from '@/api/types'
import { usd } from '@/lib/format'

const props = defineProps<{ series: PnlPoint[]; markers: Marker[] }>()

const option = computed(() => {
  const first = props.series[0]?.ts ?? 0
  const last = props.series.at(-1)?.ts ?? 0
  // Only draw rule-change markers that fall inside the wallet's history.
  const markers = props.markers
    .map((m) => ({ ts: Date.parse(`${m.date}T00:00:00Z`), label: m.label }))
    .filter((m) => m.ts >= first && m.ts <= last)
  return {
    grid: { left: 8, right: 16, top: 28, bottom: 8, containLabel: true },
    tooltip: {
      ...tooltip,
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: chart.axis, width: 1 } },
      formatter: (params: { value: [number, number] }[]) => {
        const p = params[0]
        if (!p) return ''
        const d = new Date(p.value[0]).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' })
        return `${d}<br/>${tooltipRow(usd(p.value[1], { signed: true }), 'cumulative P&L', chart.series)}`
      },
    },
    xAxis: {
      type: 'time',
      axisLine: { lineStyle: { color: chart.axis } },
      axisTick: { show: false },
      axisLabel,
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      axisLabel: { ...axisLabel, formatter: (v: number) => usd(v) },
      splitLine: { lineStyle: { color: chart.grid, width: 1 } },
    },
    series: [
      {
        type: 'line',
        data: props.series.map((p) => [p.ts, p.pnl]),
        showSymbol: false,
        symbolSize: 8,
        lineStyle: { width: 2, color: chart.series, cap: 'round', join: 'round' },
        itemStyle: { color: chart.series, borderColor: chart.surface, borderWidth: 2 },
        areaStyle: { color: chart.series, opacity: 0.1 },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: chart.muted, width: 1, type: 'solid', opacity: 0.6 },
          label: { color: chart.muted, fontSize: 10, formatter: (p: { name: string }) => p.name },
          data: markers.map((m) => ({ xAxis: m.ts, name: m.label })),
        },
      },
    ],
  }
})
</script>

<template>
  <!-- Sized wrapper: see ColumnChart. -->
  <div class="h-72 w-full"><VChart :option="option" autoresize /></div>
</template>
