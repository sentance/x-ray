<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { api } from '@/api/client'
import type { Wallet } from '@/api/types'
import WalletSearch from '@/components/WalletSearch.vue'
import { ago, date, num, shortAddress, walletName } from '@/lib/format'

const recent = ref<Wallet[] | null>(null)
const recentError = ref<string | null>(null)

onMounted(async () => {
  try {
    recent.value = await api.recent()
  } catch (e) {
    recentError.value = e instanceof Error ? e.message : String(e)
  }
})

const FEATURES = [
  { title: 'Lifetime P&L', text: 'Cumulative profit, fees, rebates and month-by-month edge.' },
  { title: 'Strategy profile', text: 'What it trades, entry prices, exits and timing, labelled by playbook.' },
  { title: 'Skill or luck', text: 'Could a coin-flipper have produced this record? Win streaks, t-stats, drawdowns.' },
  { title: 'Still working?', text: 'Recent 30/60/90-day results against the lifetime record.' },
]
</script>

<template>
  <div class="mx-auto max-w-3xl pt-6 sm:pt-12">
    <h1 class="text-3xl font-semibold tracking-tight text-slate-50 sm:text-4xl">
      Reverse-engineer a Polymarket wallet
    </h1>
    <p class="mt-3 text-slate-400">
      Paste any public wallet to see how it makes money, whether the edge is real, and whether it still works.
    </p>
    <div class="mt-6">
      <WalletSearch />
    </div>

    <ul class="mt-8 grid gap-3 sm:grid-cols-2">
      <li v-for="f in FEATURES" :key="f.title" class="rounded-lg border border-slate-800 bg-slate-900/60 p-4">
        <div class="text-sm font-medium text-slate-100">{{ f.title }}</div>
        <div class="mt-1 text-sm text-slate-400">{{ f.text }}</div>
      </li>
    </ul>

    <section class="mt-10">
      <h2 class="text-sm font-medium uppercase tracking-wide text-slate-500">Recently analyzed</h2>
      <p v-if="recentError" class="mt-3 text-sm text-slate-500">Couldn't load recent wallets: {{ recentError }}</p>
      <p v-else-if="recent && !recent.length" class="mt-3 text-sm text-slate-500">
        Nothing yet. Analyze a wallet above and it will show up here.
      </p>
      <ul v-else-if="recent" class="mt-3 divide-y divide-slate-800 rounded-lg border border-slate-800">
        <li v-for="w in recent" :key="w.address">
          <RouterLink
            :to="{ name: 'wallet', params: { address: w.address } }"
            class="flex items-center justify-between gap-4 px-4 py-3 hover:bg-slate-900"
          >
            <div class="min-w-0">
              <div class="truncate font-medium text-slate-100">{{ walletName(w) }}</div>
              <div class="text-xs text-slate-500">{{ shortAddress(w.address) }}</div>
            </div>
            <div class="shrink-0 text-right text-xs text-slate-400">
              <div class="tabular-nums">{{ num(w.activityCount) }} activities since {{ date(w.oldestTs) }}</div>
              <div>synced {{ ago(w.lastSyncedAt) }}</div>
            </div>
          </RouterLink>
        </li>
      </ul>
      <p v-else class="mt-3 text-sm text-slate-500">Loading…</p>
    </section>
  </div>
</template>
