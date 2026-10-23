<script setup lang="ts">
import { computed } from 'vue'
import type { Share } from '@/api/types'
import { pct, usd } from '@/lib/format'

/** Horizontal share-of-volume bars, with each row's estimated P&L as text beside it. */
const props = defineProps<{ shares: Share[] }>()

const rows = computed(() => props.shares.filter((s) => s.volume > 0))
</script>

<template>
  <ul class="space-y-2.5">
    <li v-for="s in rows" :key="s.name" class="text-sm">
      <div class="mb-1 flex items-baseline justify-between gap-3">
        <span class="truncate text-slate-200">{{ s.name }}</span>
        <span class="shrink-0 tabular-nums text-slate-400">
          {{ pct(s.share) }} · {{ usd(s.volume) }} vol ·
          <span class="text-slate-200">{{ usd(s.pnl, { signed: true }) }}</span>
        </span>
      </div>
      <div class="h-1.5 rounded-full bg-slate-800">
        <div class="h-1.5 rounded-full bg-[#3987e5]" :style="{ width: `${Math.max(s.share * 100, 0.5)}%` }" />
      </div>
    </li>
    <li v-if="!rows.length" class="text-sm text-slate-500">No trades yet.</li>
  </ul>
</template>
