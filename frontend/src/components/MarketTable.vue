<script setup lang="ts">
import type { MarketResult } from '@/api/types'
import { num, usd } from '@/lib/format'

defineProps<{ markets: MarketResult[] }>()
</script>

<template>
  <div class="overflow-x-auto">
    <table class="w-full text-sm">
      <thead class="text-left text-xs text-slate-500">
        <tr>
          <th class="pb-2 font-normal">Market</th>
          <th class="whitespace-nowrap pb-2 pl-3 text-right font-normal">Est. P&amp;L</th>
          <th class="hidden pb-2 pl-3 text-right font-normal sm:table-cell">Volume</th>
          <th class="hidden pb-2 pl-3 text-right font-normal sm:table-cell">Trades</th>
        </tr>
      </thead>
      <tbody class="tabular-nums">
        <tr v-for="m in markets" :key="m.conditionId" class="border-t border-slate-800">
          <td class="py-2 pr-3">
            <a
              v-if="m.slug"
              :href="`https://polymarket.com/market/${m.slug}`"
              target="_blank"
              rel="noopener"
              class="text-slate-200 hover:text-sky-300"
              >{{ m.title }}</a
            >
            <span v-else class="text-slate-200">{{ m.title }}</span>
            <div class="text-xs text-slate-500">
              {{ m.marketType }}<template v-if="!m.resolved"> · open</template>
            </div>
          </td>
          <td class="whitespace-nowrap py-2 pl-3 text-right text-slate-100">{{ usd(m.pnl, { signed: true }) }}</td>
          <td class="hidden whitespace-nowrap py-2 pl-3 text-right text-slate-400 sm:table-cell">{{ usd(m.volume) }}</td>
          <td class="hidden whitespace-nowrap py-2 pl-3 text-right text-slate-400 sm:table-cell">{{ num(m.trades) }}</td>
        </tr>
        <tr v-if="!markets.length">
          <td colspan="4" class="py-2 text-slate-500">None yet.</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
