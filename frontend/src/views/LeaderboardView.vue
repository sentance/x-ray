<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { api } from '@/api/client'
import type { Leaderboard, LeaderboardOrder, LeaderboardPeriod, LeaderboardRow } from '@/api/types'
import { shortAddress, usd } from '@/lib/format'
import TagChips from '@/components/TagChips.vue'

const PERIODS: { value: LeaderboardPeriod; label: string }[] = [
  { value: 'DAY', label: 'Today' },
  { value: 'WEEK', label: 'This week' },
  { value: 'MONTH', label: 'This month' },
  { value: 'ALL', label: 'All time' },
]
const CATEGORIES = ['OVERALL', 'POLITICS', 'SPORTS', 'CRYPTO', 'CULTURE', 'MENTIONS', 'WEATHER', 'ECONOMICS', 'TECH', 'FINANCE']
const ORDERS: { value: LeaderboardOrder; label: string }[] = [
  { value: 'PNL', label: 'Profit' },
  { value: 'VOL', label: 'Volume' },
]
const UNTAGGED = 'No dominant pattern'
const POLL_MS = 4_000
const MAX_POLLS = 60

const route = useRoute()
const router = useRouter()

// Filters live in the URL so a view can be shared or bookmarked.
const period = computed<LeaderboardPeriod>(() =>
  PERIODS.some((p) => p.value === route.query.period) ? (route.query.period as LeaderboardPeriod) : 'WEEK',
)
const category = computed(() =>
  CATEGORIES.includes(String(route.query.category)) ? String(route.query.category) : 'OVERALL',
)
const orderBy = computed<LeaderboardOrder>(() => (route.query.orderBy === 'VOL' ? 'VOL' : 'PNL'))
const strategy = computed(() => (typeof route.query.strategy === 'string' ? route.query.strategy : null))

function setQuery(patch: Record<string, string | null>) {
  const query = { ...route.query, ...patch }
  for (const [k, v] of Object.entries(query)) if (v == null) delete query[k]
  router.replace({ query })
}

const board = shallowRef<Leaderboard | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0

async function load(gen: number, polls = 0) {
  loading.value = true
  try {
    const b = await api.leaderboard(period.value, category.value, orderBy.value)
    if (gen !== generation) return
    board.value = b
    error.value = null
    if (b.pending > 0 && polls < MAX_POLLS) timer = setTimeout(() => load(gen, polls + 1), POLL_MS)
  } catch (e) {
    if (gen === generation) error.value = e instanceof Error ? e.message : String(e)
  } finally {
    if (gen === generation) loading.value = false
  }
}

watch(
  [period, category, orderBy],
  () => {
    clearTimeout(timer)
    load(++generation)
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  generation++
  clearTimeout(timer)
})

function primaryStrategy(row: LeaderboardRow): string | null {
  if (!row.tag) return null
  return row.tag.labels.find((l) => l.kind === 'STRATEGY')?.name ?? UNTAGGED
}

/** How many wallets run each strategy, most common first; doubles as the strategy filter. */
const mix = computed(() => {
  const counts = new Map<string, number>()
  for (const row of board.value?.rows ?? []) {
    const s = primaryStrategy(row)
    if (s) counts.set(s, (counts.get(s) ?? 0) + 1)
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
})

const rows = computed(() =>
  (board.value?.rows ?? []).filter((r) => !strategy.value || primaryStrategy(r) === strategy.value),
)

function displayName(row: LeaderboardRow): string {
  // Wallets without a username get an address-derived placeholder name from Polymarket.
  if (!row.name || (row.name.startsWith('0x') && row.name.length > 20)) return shortAddress(row.address)
  return row.name
}
</script>

<template>
  <div class="space-y-5">
    <header>
      <h1 class="text-2xl font-semibold text-slate-50">Who's winning now</h1>
      <p class="mt-1 text-slate-400">
        Polymarket's top {{ board?.rows.length || 50 }} wallets, tagged by the strategy they appear to run.
      </p>
    </header>

    <div class="flex flex-wrap items-center gap-3">
      <div class="flex rounded-lg border border-slate-800 bg-slate-900 p-0.5" role="group" aria-label="Period">
        <button
          v-for="p in PERIODS"
          :key="p.value"
          type="button"
          :aria-pressed="period === p.value"
          :class="[
            'rounded-md px-3 py-1.5 text-sm',
            period === p.value ? 'bg-slate-700 text-white' : 'text-slate-400 hover:text-white',
          ]"
          @click="setQuery({ period: p.value })"
        >
          {{ p.label }}
        </button>
      </div>
      <label class="flex items-center gap-2 text-sm text-slate-400">
        Category
        <select
          :value="category"
          class="rounded-lg border border-slate-800 bg-slate-900 px-2 py-1.5 text-slate-100"
          @change="setQuery({ category: ($event.target as HTMLSelectElement).value, strategy: null })"
        >
          <option v-for="c in CATEGORIES" :key="c" :value="c">{{ c.charAt(0) + c.slice(1).toLowerCase() }}</option>
        </select>
      </label>
      <label class="flex items-center gap-2 text-sm text-slate-400">
        Rank by
        <select
          :value="orderBy"
          class="rounded-lg border border-slate-800 bg-slate-900 px-2 py-1.5 text-slate-100"
          @change="setQuery({ orderBy: ($event.target as HTMLSelectElement).value })"
        >
          <option v-for="o in ORDERS" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
      </label>
    </div>

    <p v-if="error" class="rounded-lg border border-[#e66767]/40 bg-[#e66767]/10 p-3 text-sm text-[#f0a0a0]" role="alert">
      {{ error }}
    </p>

    <section v-if="mix.length" aria-label="Strategy mix">
      <div class="mb-2 flex items-baseline justify-between gap-3">
        <h2 class="text-xs font-medium uppercase tracking-wide text-slate-500">Strategy mix</h2>
        <span v-if="board?.pending" class="text-xs text-slate-500" role="status">
          Tagging {{ board.pending }} more wallet{{ board.pending === 1 ? '' : 's' }}…
        </span>
      </div>
      <div class="flex flex-wrap gap-2">
        <button
          v-for="[name, count] in mix"
          :key="name"
          type="button"
          :aria-pressed="strategy === name"
          :class="[
            'rounded-full border px-3 py-1 text-sm',
            strategy === name
              ? 'border-sky-500 bg-sky-500/20 text-sky-100'
              : 'border-slate-800 bg-slate-900 text-slate-300 hover:border-slate-600',
          ]"
          @click="setQuery({ strategy: strategy === name ? null : name })"
        >
          {{ name }} <span class="tabular-nums text-slate-500">{{ count }}</span>
        </button>
        <button
          v-if="strategy"
          type="button"
          class="px-2 py-1 text-sm text-slate-400 hover:text-white"
          @click="setQuery({ strategy: null })"
        >
          Clear
        </button>
      </div>
    </section>

    <div :class="['overflow-x-auto rounded-xl border border-slate-800 transition-opacity', loading && board && 'opacity-80']">
      <table class="w-full text-sm">
        <thead class="bg-slate-900 text-left text-xs text-slate-500">
          <tr>
            <th class="w-10 px-3 py-2 font-normal">#</th>
            <th class="px-3 py-2 font-normal">Trader</th>
            <th class="hidden px-3 py-2 font-normal md:table-cell">Strategy</th>
            <th class="whitespace-nowrap px-3 py-2 text-right font-normal">P&amp;L</th>
            <th class="hidden px-3 py-2 text-right font-normal sm:table-cell">Volume</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-800">
          <tr v-for="row in rows" :key="row.address" class="hover:bg-slate-900/60">
            <td class="px-3 py-3 align-top tabular-nums text-slate-500">{{ row.rank }}</td>
            <td class="px-3 py-3 align-top">
              <RouterLink :to="{ name: 'wallet', params: { address: row.address } }" class="flex items-center gap-2.5">
                <img
                  v-if="row.profileImage"
                  :src="row.profileImage"
                  alt=""
                  class="size-8 shrink-0 rounded-full border border-slate-800 object-cover"
                  loading="lazy"
                />
                <span
                  v-else
                  class="flex size-8 shrink-0 items-center justify-center rounded-full bg-slate-800 text-xs text-slate-400"
                  aria-hidden="true"
                  >{{ displayName(row).charAt(0).toUpperCase() }}</span
                >
                <span class="min-w-0">
                  <span class="block max-w-56 truncate font-medium text-slate-100 hover:text-sky-300">{{
                    displayName(row)
                  }}</span>
                  <span class="block font-mono text-xs text-slate-500">{{ shortAddress(row.address) }}</span>
                </span>
              </RouterLink>
              <div class="mt-2 md:hidden"><TagChips :row="row" /></div>
            </td>
            <td class="hidden px-3 py-3 align-top md:table-cell"><TagChips :row="row" /></td>
            <td class="whitespace-nowrap px-3 py-3 text-right align-top tabular-nums text-slate-100">
              {{ usd(row.pnl, { signed: true }) }}
            </td>
            <td class="hidden whitespace-nowrap px-3 py-3 text-right align-top tabular-nums text-slate-400 sm:table-cell">
              {{ usd(row.volume) }}
            </td>
          </tr>
          <tr v-if="board && !rows.length">
            <td colspan="5" class="px-3 py-6 text-center text-slate-500">No wallets match.</td>
          </tr>
          <tr v-if="!board && !error">
            <td colspan="5" class="px-3 py-6 text-center text-slate-500">Loading the leaderboard…</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="text-xs text-slate-500">
      Tags marked "Recent trades" come from each wallet's last ~1,000 activities and can miss older patterns.
      Opening a wallet runs the full analysis.
    </p>
  </div>
</template>
