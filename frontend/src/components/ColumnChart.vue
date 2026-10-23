<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { axisLabel, chart, tooltip, tooltipRow } from '@/charts/echarts'

/**
 * Single-series column chart. With `polarity`, positive columns take the gain hue and negative ones the loss hue,
 * so a month's sign reads from color as well as from which side of the baseline it sits.
 */
const props = withDefaults(
  defineProps<{
    labels: string[]
    values: number[]
    format: (v: number) => string
    valueLabel: string
    polarity?: boolean
    height?: string
    /** A horizontal reference line, e.g. the 50% a coin flip would score. */
    reference?: { value: number; label: string }
  }>(),
  { polarity: false, height: 'h-56' },
)

const option = computed(() => ({
  grid: { left: 8, right: 8, top: 16, bottom: 8, containLabel: true },
  tooltip: {
    ...tooltip,
    trigger: 'item',
    formatter: (p: { name: string; value: number; color: string }) =>
      `${p.name}<br/>${tooltipRow(props.format(p.value), props.valueLabel, p.color)}`,
  },
  xAxis: {
    type: 'category',
    data: props.labels,
    axisLine: { lineStyle: { color: chart.axis } },
    axisTick: { show: false },
    axisLabel,
  },
  yAxis: {
    type: 'value',
    axisLabel: { ...axisLabel, formatter: (v: number) => props.format(v) },
    splitLine: { lineStyle: { color: chart.grid, width: 1 } },
  },
  series: [
    {
      type: 'bar',
      barMaxWidth: 24,
      data: props.values.map((v) => {
        const negative = props.polarity && v < 0
        return {
          value: v,
          itemStyle: {
            color: negative ? chart.loss : chart.gain,
            // Round the data end only; the baseline end stays square.
            borderRadius: negative ? [0, 0, 4, 4] : [4, 4, 0, 0],
          },
        }
      }),
      emphasis: { itemStyle: { opacity: 0.85 } },
      markLine: props.reference
        ? {
            silent: true,
            symbol: 'none',
            lineStyle: { color: chart.muted, width: 1, type: 'solid' },
            label: { color: chart.muted, fontSize: 10, position: 'insideEndTop', formatter: props.reference.label },
            data: [{ yAxis: props.reference.value }],
          }
        : undefined,
    },
  ],
}))
</script>

<template>
  <!-- vue-echarts' unlayered CSS sets height: 100%, which beats Tailwind's layered h-*; size a wrapper instead. -->
  <div :class="[height, 'w-full']"><VChart :option="option" autoresize /></div>
</template>
