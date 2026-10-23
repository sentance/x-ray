<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <header class="border-b border-slate-800 bg-slate-900/60">
    <nav class="mx-auto flex max-w-6xl items-center gap-6 px-4 py-3">
      <RouterLink to="/" class="text-lg font-semibold tracking-tight text-white">
        Polymarket <span class="text-sky-400">X-Ray</span>
      </RouterLink>
      <RouterLink to="/leaderboard" class="text-sm text-slate-400 hover:text-white">
        Who's winning now
      </RouterLink>
    </nav>
  </header>
  <main class="mx-auto max-w-6xl px-4 py-6">
    <RouterView />
  </main>
</template>
