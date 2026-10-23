<script setup lang="ts">
defineProps<{ label: string; value: string; hint?: string }>()
</script>

<template>
  <div class="rounded-lg border border-slate-800 bg-slate-900 px-4 py-3">
    <div class="text-xs text-slate-400">{{ label }}</div>
    <div class="mt-1 text-xl font-semibold text-slate-50">{{ value }}</div>
    <div v-if="hint" class="mt-0.5 text-xs text-slate-500">{{ hint }}</div>
  </div>
</template>
