<script setup lang="ts">
import { computed } from 'vue'
import type { SyncJob } from '@/api/types'
import { num } from '@/lib/format'

const PHASES: Record<string, string> = {
  QUEUED: 'Waiting for a free sync slot',
  PROFILE: 'Reading the profile',
  PNL: 'Loading the P&L history',
  ACTIVITY: 'Downloading trade history',
  POSITIONS: 'Valuing open positions',
  MARKETS: 'Looking up markets',
}

const props = defineProps<{ job: SyncJob }>()

// `expected` is an estimate and can be overtaken; past it, show the count with an indeterminate bar.
const expected = computed(() =>
  props.job.expected && props.job.fetched <= props.job.expected ? props.job.expected : null,
)
const fraction = computed(() =>
  props.job.phase === 'ACTIVITY' && expected.value ? props.job.fetched / expected.value : null,
)
</script>

<template>
  <div class="rounded-xl border border-sky-900/60 bg-sky-950/30 p-4" role="status" aria-live="polite">
    <div class="flex flex-wrap items-baseline justify-between gap-2 text-sm">
      <span class="font-medium text-sky-100">
        {{ PHASES[job.phase ?? ''] ?? 'Syncing' }}…
      </span>
      <span v-if="job.phase === 'ACTIVITY'" class="tabular-nums text-sky-200/80">
        {{ num(job.fetched) }}<template v-if="expected"> / {{ num(expected) }}</template> activities
      </span>
    </div>
    <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-sky-950">
      <div
        v-if="fraction != null"
        class="h-1.5 rounded-full bg-[#3987e5] transition-[width] duration-700"
        :style="{ width: `${fraction * 100}%` }"
      />
      <div v-else class="h-1.5 w-1/3 animate-pulse rounded-full bg-[#3987e5]/70" />
    </div>
    <p class="mt-2 text-xs text-sky-200/60">
      Large wallets take a few minutes. The reports below update from partial data as it arrives.
    </p>
  </div>
</template>
