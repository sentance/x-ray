<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import { api } from '@/api/client'
import type { BinanceStatus } from '@/api/types'
import { date, num, pValue, pct } from '@/lib/format'
import Card from './Card.vue'
import ColumnChart from './ColumnChart.vue'
import VerdictBadge from './VerdictBadge.vue'

const POLL_MS = 3_000

/** @param autoRun start the test without waiting for a click, e.g. for wallets tagged as latency traders */
const props = defineProps<{ address: string; autoRun: boolean }>()

const status = shallowRef<BinanceStatus | null>(null)
const error = shallowRef<string | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0

function follow(s: BinanceStatus, gen: number) {
  status.value = s
  if (s.state === 'RUNNING') timer = setTimeout(() => poll(gen), POLL_MS)
}

async function poll(gen: number): Promise<BinanceStatus | null> {
  try {
    const s = await api.binanceStatus(props.address)
    if (gen === generation) follow(s, gen)
    return s
  } catch (e) {
    if (gen === generation) error.value = e instanceof Error ? e.message : String(e)
    return null
  }
}

async function run() {
  clearTimeout(timer)
  error.value = null
  const gen = generation
  try {
    follow(await api.startBinance(props.address), gen)
  } catch (e) {
    if (gen === generation) error.value = e instanceof Error ? e.message : String(e)
  }
}

watch(
  () => props.address,
  async () => {
    clearTimeout(timer)
    const gen = ++generation
    status.value = null
    error.value = null
    const s = await poll(gen)
    if (gen === generation && props.autoRun && s?.state === 'NONE') await run()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  generation++
  clearTimeout(timer)
})

const report = computed(() => status.value?.report ?? null)
const chartWindows = computed(() => (report.value?.windows ?? []).filter((w) => w.trades > 0))
const shortLabel = (label: string) =>
  label === 'Since the window opened' ? 'since open' : label.replace(' min before', 'm before').replace(' min after', 'm after')
</script>

<template>
  <Card
    title="Binance test"
    subtitle="Does it buy the side Binance spot has already moved toward, or the side it moves to next?"
  >
    <template v-if="report" #aside><VerdictBadge :verdict="report.verdict" /></template>

    <p v-if="error" class="text-sm text-[#f0a0a0]" role="alert">{{ error }}</p>

    <div v-if="!status" class="text-sm text-slate-500">Checking…</div>

    <div v-else-if="status.state === 'NONE' || status.state === 'FAILED'" class="text-sm text-slate-300">
      <p>
        Compares every crypto buy with Binance's 1-minute prices around it. A latency bot buys after spot has already
        moved, before Polymarket's odds catch up; a predictive trader buys before the move.
      </p>
      <p v-if="status.state === 'FAILED'" class="mt-2 text-[#f0a0a0]">The last run failed: {{ status.error }}</p>
      <button
        type="button"
        class="mt-3 rounded-lg bg-sky-600 px-4 py-2 font-medium text-white hover:bg-sky-500"
        @click="run"
      >
        Run the Binance test
      </button>
    </div>

    <div v-else-if="status.state === 'RUNNING'" role="status" aria-live="polite" class="text-sm text-slate-300">
      <div class="flex justify-between">
        <span>Fetching Binance prices…</span>
        <span v-if="status.chunksNeeded" class="tabular-nums text-slate-400"
          >{{ status.chunksDone }} / {{ status.chunksNeeded }}</span
        >
      </div>
      <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-slate-800">
        <div
          v-if="status.chunksNeeded"
          class="h-1.5 rounded-full bg-[#3987e5] transition-[width] duration-700"
          :style="{ width: `${(status.chunksDone / status.chunksNeeded) * 100}%` }"
        />
        <div v-else class="h-1.5 w-1/3 animate-pulse rounded-full bg-[#3987e5]/70" />
      </div>
    </div>

    <template v-else-if="report">
      <p class="text-sm text-slate-200">{{ report.explanation }}</p>
      <p class="mt-1 text-xs text-slate-500">
        {{ num(report.directionalBuys) }} directional buys of {{ num(report.cryptoBuys) }} crypto buys ·
        {{ report.symbols.map((s) => s.replace('USDT', '')).join(', ') || '—' }} ·
        {{ date(report.fromTs) }} – {{ date(report.toTs) }}
      </p>

      <div v-if="chartWindows.length" class="mt-4 grid gap-6 lg:grid-cols-2">
        <div>
          <h3 class="mb-1 text-xs font-medium uppercase tracking-wide text-slate-500">
            Buys in the direction spot moved
          </h3>
          <ColumnChart
            :labels="chartWindows.map((w) => shortLabel(w.label))"
            :values="chartWindows.map((w) => w.alignedShare)"
            :format="(v) => pct(v)"
            value-label="of buys aligned with spot"
            :reference="{ value: 0.5, label: 'coin flip' }"
            height="h-52"
          />
        </div>
        <div class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead class="text-left text-xs text-slate-500">
              <tr>
                <th class="pb-2 font-normal">Spot move</th>
                <th class="pb-2 pl-3 text-right font-normal">Aligned</th>
                <th class="hidden pb-2 pl-3 text-right font-normal sm:table-cell">By $</th>
                <th class="pb-2 pl-3 text-right font-normal">Avg move</th>
                <th class="pb-2 pl-3 text-right font-normal">p</th>
              </tr>
            </thead>
            <tbody class="tabular-nums">
              <tr v-for="w in report.windows" :key="w.label" class="border-t border-slate-800">
                <td class="py-2 text-slate-300">
                  {{ w.label }} <span class="text-xs text-slate-500">· {{ num(w.trades) }}</span>
                </td>
                <td class="whitespace-nowrap py-2 pl-3 text-right text-slate-100">
                  {{ w.trades ? pct(w.alignedShare) : '—' }}
                </td>
                <td class="hidden whitespace-nowrap py-2 pl-3 text-right text-slate-400 sm:table-cell">
                  {{ w.trades ? pct(w.alignedVolumeShare) : '—' }}
                </td>
                <td class="whitespace-nowrap py-2 pl-3 text-right text-slate-400">
                  {{ w.trades ? `${w.meanSignedMoveBps >= 0 ? '+' : '−'}${Math.abs(w.meanSignedMoveBps).toFixed(1)} bp` : '—' }}
                </td>
                <td class="whitespace-nowrap py-2 pl-3 text-right text-slate-400">
                  {{ w.trades ? pValue(w.pValue) : '—' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <p class="mt-3 text-xs text-slate-500">
        Uses 1-minute candles, so the trade's own minute is left out: moves within seconds of a trade, where the
        fastest bots act, aren't visible. Up/Down markets settle on Chainlink prices, which track Binance closely.
      </p>
    </template>
  </Card>
</template>
