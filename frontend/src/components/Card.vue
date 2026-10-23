<script setup lang="ts">
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <section class="rounded-xl border border-slate-800 bg-slate-900 p-4 sm:p-5">
    <header class="mb-3 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
      <div>
        <h2 class="text-base font-semibold text-slate-50">{{ title }}</h2>
        <p v-if="subtitle" class="text-sm text-slate-400">{{ subtitle }}</p>
      </div>
      <slot name="aside" />
    </header>
    <slot />
  </section>
</template>
