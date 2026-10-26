<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { paperApi, type PaperSettings, type PaperView } from '@/api/paper'
import { usd } from '@/lib/format'

const route = useRoute(), router = useRouter()
const settings = ref<PaperSettings>({ bankroll: 1000, maxCommitted: 400, maxLoss: 100, maxCycleLoss: 2, slippage: .01, gasPerConversion: .05, hedgeDelayMs: 1000 })
const view = ref<PaperView | null>(null), error = ref(''), busy = ref(false), loading = ref(false)
const saved = ref<string[]>([])
try { saved.value = JSON.parse(localStorage.getItem('xray-paper-sessions') || '[]').filter((v: unknown) => typeof v === 'string' && /^[a-f0-9-]{36}$/.test(v)).slice(0, 20) } catch { /* Storage can be unavailable. */ }
const id = computed(() => typeof route.params.id === 'string' ? route.params.id : '')
const state = computed(() => view.value?.state)
const scenarioPerDay = ref(0)
const hypotheticalIncentives = computed(() => Math.max(0, Number(scenarioPerDay.value) || 0) * (state.value?.quoteMillis ?? 0) / 86400000)
const ledger = computed(() => [...(state.value?.ledger ?? [])].reverse().slice(0, 100))
const positions = computed(() => Object.values(state.value?.positions ?? {}))
const pools = computed(() => (state.value?.groups ?? []).flatMap(g => g.outcomes.filter(o => o.dailyPool > 0).map(o => ({ ...o, event: g.title }))))
let timer: ReturnType<typeof setTimeout> | undefined, generation = 0
function remember(value: string) {
  saved.value = [value, ...saved.value.filter(v => v !== value)].slice(0, 20)
  try { localStorage.setItem('xray-paper-sessions', JSON.stringify(saved.value)) } catch { /* URL still retains session access. */ }
}
async function load(gen: number) {
  if (!id.value) return
  loading.value = true
  try {
    const result = await paperApi.view(id.value)
    if (gen !== generation) return
    view.value = result; error.value = ''; remember(result.state.id)
  } catch (e) { if (gen === generation) error.value = e instanceof Error ? e.message : String(e) }
  finally {
    if (gen === generation) {
      loading.value = false
      timer = setTimeout(() => load(gen), 2500)
    }
  }
}
watch(id, () => { clearTimeout(timer); view.value = null; error.value = ''; load(++generation) }, { immediate: true })
onBeforeUnmount(() => { ++generation; clearTimeout(timer) })
async function action(kind: 'create' | 'resume' | 'pause') {
  busy.value = true; error.value = ''
  try {
    const result = kind === 'create' ? await paperApi.create({ ...settings.value }) : await paperApi[kind](id.value)
    remember(result.state.id)
    if (kind === 'create') await router.push(`/paper/${result.state.id}`)
    else view.value = result
  } catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false }
}
function exportSnapshot() {
  if (!view.value) return
  const url = URL.createObjectURL(new Blob([JSON.stringify(view.value, null, 2)], { type: 'application/json' }))
  const a = document.createElement('a'); a.href = url; a.download = `paper-${id.value}.json`; a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
const when = (at: number) => at ? new Date(at).toLocaleString() : '—'
const qty = (n: number) => n.toLocaleString(undefined, { maximumFractionDigits: 6 })
</script>

<template>
  <div class="space-y-6">
    <header>
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h1 class="text-2xl font-semibold text-white">Akroll · Paper Trading</h1>
        <span class="rounded-full bg-sky-950 px-3 py-1 text-sm text-sky-300">Simulated money · public data</span>
      </div>
      <p class="mt-2 max-w-3xl text-slate-400">Rest a NO bid, model a maker fill behind the visible queue, then buy a related NO and convert the pair. Named residual YES shares sell into available bids. No wallet or real orders are connected.</p>
    </header>
    <p v-if="error" role="alert" class="rounded-lg border border-red-900 bg-red-950/40 p-4 text-red-300">{{ error }}</p>
    <form v-if="!id" class="rounded-xl border border-slate-800 bg-slate-900 p-5" @submit.prevent="action('create')">
      <h2 class="mb-4 text-lg font-medium text-white">Start a forward paper test</h2>
      <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <label class="text-sm text-slate-300">Starting cash ($)<input v-model.number="settings.bankroll" type="number" min="100" max="100000" step="1" required class="mt-1 w-full rounded border border-slate-700 bg-slate-950 p-2" /></label>
        <label class="text-sm text-slate-300">Maximum committed per cycle ($)<input v-model.number="settings.maxCommitted" type="number" min="10" :max="settings.bankroll * .9" required class="mt-1 w-full rounded border border-slate-700 bg-slate-950 p-2" /></label>
        <label class="text-sm text-slate-300">Paper loss stop ($)<input v-model.number="settings.maxLoss" type="number" min="1" :max="settings.bankroll" required class="mt-1 w-full rounded border border-slate-700 bg-slate-950 p-2" /></label>
        <label class="text-sm text-slate-300">Entry cash loss allowance before residual sales ($)<input v-model.number="settings.maxCycleLoss" type="number" min="0" :max="settings.maxLoss" step="0.01" required class="mt-1 w-full rounded border border-slate-700 bg-slate-950 p-2" /></label>
        <label class="text-sm text-slate-300">Hedge price allowance ($ per share)<input v-model.number="settings.slippage" type="number" min="0.001" max="0.05" step="0.001" required class="mt-1 w-full rounded border border-slate-700 bg-slate-950 p-2" /></label>
        <label class="text-sm text-slate-300">Conversion cost assumption ($)<input v-model.number="settings.gasPerConversion" type="number" min="0" max="10" step="0.01" required class="mt-1 w-full rounded border border-slate-700 bg-slate-950 p-2" /></label>
        <label class="text-sm text-slate-300">Hedge latency (milliseconds)<input v-model.number="settings.hedgeDelayMs" type="number" min="1000" max="30000" step="100" required class="mt-1 w-full rounded border border-slate-700 bg-slate-950 p-2" /></label>
      </div>
      <p class="my-4 text-sm text-slate-400">These settings freeze when the test starts. The $100 loss stop applies only to simulated money. Pools and estimated future rewards do not increase cash or offset losses.</p>
      <button :disabled="busy" class="rounded-lg bg-sky-600 px-5 py-2 font-medium text-white disabled:opacity-50">{{ busy ? 'Starting…' : 'Start paper test' }}</button>
    </form>
    <template v-if="view && state">
      <section class="rounded-xl border border-slate-800 bg-slate-900 p-5">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <div><strong class="text-white">{{ view.active ? 'Running' : state.status }}</strong><span class="ml-3 text-sm text-slate-400">{{ state.feed }} · {{ state.policy }}</span></div>
          <div class="flex flex-wrap gap-3 text-sm">
            <button v-if="view.active" :disabled="busy" class="rounded border border-slate-600 px-3 py-1.5 disabled:opacity-50" @click="action('pause')">Pause</button>
            <button v-else :disabled="busy" class="rounded bg-sky-600 px-3 py-1.5 text-white disabled:opacity-50" @click="action('resume')">Resume with fresh data</button>
            <button class="rounded border border-slate-600 px-3 py-1.5" @click="exportSnapshot">Export snapshot</button>
            <RouterLink to="/paper" class="rounded border border-slate-600 px-3 py-1.5">New test</RouterLink>
          </div>
        </div>
        <p class="mt-3 text-slate-300" aria-live="polite">{{ state.message }}</p>
        <p class="mt-2 text-xs text-slate-500">Started {{ when(state.createdAt) }} · Last checkpoint {{ when(state.updatedAt) }} · Last feed event {{ when(state.lastFeedAt) }}{{ loading ? ' · Refreshing' : '' }}</p>
      </section>
      <div class="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <div v-for="stat in [{label: 'Cash', value: usd(state.cash)}, {label: 'Reserved for quote + hedge', value: usd(view.reserved)}, {label: 'Executable inventory mark', value: usd(state.liquidationValue)}, {label: 'P&L, incentives excluded', value: usd(view.pnl)}]" :key="stat.label" class="rounded-xl border border-slate-800 bg-slate-900 p-4"><div class="text-sm text-slate-400">{{ stat.label }}</div><div class="mt-1 text-xl text-white">{{ stat.value }}</div></div>
      </div>
      <p class="text-sm text-slate-400">{{ state.fills }} simulated maker fills · {{ state.conversions }} conversions · {{ usd(state.fees) }} taker fees · {{ usd(state.gas) }} conversion costs · {{ (state.quoteMillis / 60000).toFixed(1) }} minutes of eligible quote time. Missing, stale, unnamed and below-minimum inventory marks are zero. A loss stop may overshoot after a failed hedge.</p>
      <section v-if="state.quote" class="rounded-xl border border-sky-900 bg-sky-950/30 p-5">
        <h2 class="font-medium text-sky-200">Resting paper quote: {{ state.quote.name }} NO</h2>
        <p class="mt-2 text-sm text-slate-300">{{ qty(state.quote.size) }} shares at {{ state.quote.price.toFixed(4) }} · {{ qty(state.quote.queueAhead) }} shares ahead · expires {{ when(state.quote.expiresAt) }}.</p>
        <p class="mt-1 text-sm text-slate-400">Only identifiable sell trades at this price consume the queue. Cancellations do not count as fills.</p>
      </section>
      <section v-if="state.pending" class="rounded-xl border border-amber-900 bg-amber-950/30 p-5 text-amber-200">Hedge pending for {{ qty(state.pending.shares) }} shares. Maker cash is already debited. Inventory stays visible if the hedge cannot complete.</section>
      <section v-if="positions.length" class="rounded-xl border border-slate-800 bg-slate-900 p-5">
        <h2 class="mb-3 font-medium text-white">Retained inventory</h2>
        <div v-for="p in positions" :key="p.token" class="flex justify-between gap-4 border-t border-slate-800 py-2 text-sm"><span>{{ p.name }}{{ p.sellable ? '' : ' · excluded from trading / marks' }}</span><span>{{ qty(p.shares) }} shares</span></div>
      </section>
      <section class="rounded-xl border border-slate-800 bg-slate-900 p-5">
        <h2 class="font-medium text-white">Incentives: $0 credited</h2>
        <p class="my-2 text-sm text-slate-400">{{ view.incentiveNote }} Quoting eligibility is an approximation; the paper account has no authenticated reward score. Break-even incentives needed to cover current losses: {{ usd(Math.max(0, -view.pnl)) }}.</p>
        <label class="block text-sm text-slate-400">Optional assumption: $ earned per 24 hours of eligible quoting<input v-model.number="scenarioPerDay" type="number" min="0" max="1000" step="0.1" class="ml-3 w-28 rounded border border-slate-700 bg-slate-950 p-2" /></label>
        <p v-if="scenarioPerDay > 0" class="my-2 text-sm text-amber-200">Hypothetical incentives {{ usd(hypotheticalIncentives) }} · P&amp;L under your assumption {{ usd(view.pnl + hypotheticalIncentives) }}. This scenario is excluded from the ledger and has no verified reward score.</p>
        <div class="max-h-64 overflow-auto"><table v-if="pools.length" class="w-full text-left text-sm"><thead class="text-slate-500"><tr><th class="py-2">Named outcome</th><th>Market pool / day</th><th>Minimum shares</th></tr></thead><tbody><tr v-for="p in pools" :key="p.conditionId" class="border-t border-slate-800"><td class="py-2"><span class="text-slate-500">{{ p.event }} · </span>{{ p.name }}</td><td>{{ usd(p.dailyPool) }}</td><td>{{ p.rewardMin }}</td></tr></tbody></table><p v-else class="text-sm text-slate-500">No verified allocations in the current universe.</p></div>
      </section>
      <details class="rounded-xl border border-slate-800 bg-slate-900 p-5"><summary class="cursor-pointer font-medium text-white">Frozen policy and skipped candidates</summary><pre class="mt-3 overflow-auto text-xs text-slate-400">{{ JSON.stringify(state.settings, null, 2) }}</pre><div v-for="(count, reason) in state.skipped" :key="reason" class="mt-1 text-sm text-slate-400">{{ reason }}: {{ count }}</div><p class="mt-3 text-sm text-slate-500">Public discovery checks at most 12 events from the first 100 reward markets, with at most 4 events and 160 subscribed assets. A test can correctly produce no trades.</p></details>
      <section class="rounded-xl border border-slate-800 bg-slate-900 p-5">
        <h2 class="mb-3 font-medium text-white">Cash ledger · latest 100 entries</h2>
        <div class="max-h-96 overflow-auto"><table class="w-full text-left text-sm"><thead class="text-slate-500"><tr><th class="py-2">Time</th><th>Action</th><th>Cash change</th><th>Details</th></tr></thead><tbody><tr v-for="(entry, i) in ledger" :key="`${entry.at}-${i}`" class="border-t border-slate-800"><td class="whitespace-nowrap py-2 pr-3 text-slate-500">{{ when(entry.at) }}</td><td class="pr-3">{{ entry.type }}</td><td class="whitespace-nowrap pr-3">{{ usd(entry.amount) }}</td><td class="text-slate-400">{{ entry.detail }}</td></tr></tbody></table></div>
        <p class="mt-3 text-xs text-slate-500">The snapshot includes the complete cash ledger and settings. Raw timestamped public inputs are available from /api/paper/{{ id }}/evidence?after=0 in pages of 500; continue after the last sequence number. Keep this session URL private: it grants access to the paper test.</p>
      </section>
    </template>
    <p v-else-if="id && loading" class="text-slate-400">Loading paper session…</p>
    <section v-if="!id && saved.length" class="space-y-2"><h2 class="font-medium text-white">Tests saved in this browser</h2><RouterLink v-for="sid in saved" :key="sid" :to="`/paper/${sid}`" class="block text-sm text-sky-400 hover:underline">{{ sid }}</RouterLink></section>
  </div>
</template>
