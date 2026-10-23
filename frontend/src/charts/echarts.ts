// Makes <x-vue-echarts> a block box filling its parent; charts are sized by a wrapper div.
import 'vue-echarts/style.css'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'

use([CanvasRenderer, LineChart, BarChart, GridComponent, TooltipComponent, MarkLineComponent])

// Dark chart tokens (dataviz reference palette, dark steps; validated on the slate-900 card surface).
export const chart = {
  surface: '#0f172a',
  series: '#3987e5',
  /** Second categorical slot (validated next to series on the card surface). */
  series2: '#d95926',
  gain: '#3987e5',
  loss: '#e66767',
  textPrimary: '#f0efec',
  textSecondary: '#c3c2b7',
  muted: '#898781',
  grid: '#1e293b',
  axis: '#334155',
}

export const axisLabel = { color: chart.muted, fontSize: 11 }

export const tooltip = {
  backgroundColor: '#020617',
  borderColor: 'rgba(255,255,255,0.10)',
  borderWidth: 1,
  padding: [8, 10],
  textStyle: { color: chart.textSecondary, fontSize: 12 },
  extraCssText: 'border-radius:6px;box-shadow:none;',
}

/** Tooltip row: value first (strong), label after; label text is escaped since it can come from API data. */
export function tooltipRow(value: string, label: string, color?: string): string {
  const key = color
    ? `<span style="display:inline-block;width:10px;height:2px;margin-right:6px;vertical-align:middle;background:${color}"></span>`
    : ''
  return `${key}<b style="color:${chart.textPrimary}">${escapeHtml(value)}</b> <span>${escapeHtml(label)}</span>`
}

export function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)
}
