<script setup lang="ts">
import { computed } from 'vue'

// Status tone for each analyzer verdict. Tone always ships with an icon and the verdict text, never color alone.
const TONES: Record<string, 'good' | 'warning' | 'critical' | 'info' | 'neutral'> = {
  Skill: 'good',
  'Probably skill': 'good',
  "Can't tell yet": 'warning',
  'Looks like luck': 'critical',
  'No edge': 'critical',
  'Too little history': 'neutral',
  'Still working': 'good',
  Weakening: 'warning',
  'Mostly gone': 'critical',
  'Stopped working': 'critical',
  'Never worked': 'critical',
  Inactive: 'neutral',
  'No data': 'neutral',
  // Binance test findings describe behaviour, not quality.
  'Trades after spot moves': 'info',
  'Ahead of spot': 'info',
  'No lag pattern': 'neutral',
  'Not enough data': 'neutral',
}

const STYLES = {
  good: { icon: '✓', cls: 'border-[#0ca30c]/40 bg-[#0ca30c]/10 text-[#0ca30c]' },
  warning: { icon: '!', cls: 'border-[#fab219]/40 bg-[#fab219]/10 text-[#fab219]' },
  critical: { icon: '✕', cls: 'border-[#e66767]/40 bg-[#e66767]/10 text-[#f0a0a0]' },
  info: { icon: 'ℹ', cls: 'border-sky-500/40 bg-sky-500/10 text-sky-200' },
  neutral: { icon: '–', cls: 'border-slate-700 bg-slate-800/60 text-slate-300' },
}

const props = defineProps<{ verdict: string }>()
const style = computed(() => STYLES[TONES[props.verdict] ?? 'neutral'])
</script>

<template>
  <span
    class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-sm font-medium"
    :class="style.cls"
  >
    <span aria-hidden="true" class="font-bold">{{ style.icon }}</span>{{ verdict }}
  </span>
</template>
