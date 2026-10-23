<script setup lang="ts">
import type { LeaderboardRow } from '@/api/types'
import { date, num } from '@/lib/format'

const props = defineProps<{ row: LeaderboardRow }>()

function sampleHint(): string | undefined {
  const t = props.row.tag
  if (!t || t.source !== 'QUICK' || !t.sampleTrades) return undefined
  const span = t.sampleFromTs && t.sampleToTs ? ` (${date(t.sampleFromTs)} – ${date(t.sampleToTs)})` : ''
  return `From its last ${num(t.sampleTrades)} trades${span}. Open the wallet for a full analysis.`
}
</script>

<template>
  <template v-if="row.tag">
    <div class="flex flex-wrap gap-1.5" :title="row.tag.summary">
      <span
        v-for="l in row.tag.labels"
        :key="l.name"
        :class="[
          'rounded-full px-2 py-0.5 text-xs',
          l.kind === 'STRATEGY' ? 'bg-sky-500/15 text-sky-200' : 'bg-slate-800 text-slate-300',
        ]"
        >{{ l.name }}</span
      >
      <span v-if="!row.tag.labels.length" class="text-xs text-slate-500">{{ row.tag.summary }}</span>
    </div>
    <div class="mt-1 text-xs text-slate-500">
      <span v-if="row.tag.source === 'FULL'">Full history</span>
      <span v-else :title="sampleHint()">Recent trades</span>
    </div>
  </template>
  <span v-else-if="row.tagPending" class="text-xs text-slate-500">Tagging…</span>
  <span v-else class="text-xs text-slate-500">Couldn't tag; open the wallet to analyze it.</span>
</template>
