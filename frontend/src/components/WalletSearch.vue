<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'

const router = useRouter()
const query = ref('')
const busy = ref(false)
const error = ref<string | null>(null)

async function submit() {
  const q = query.value.trim()
  if (!q || busy.value) return
  busy.value = true
  error.value = null
  try {
    const address = await api.resolve(q)
    await router.push({ name: 'wallet', params: { address } })
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <form class="w-full" @submit.prevent="submit">
    <label for="wallet-q" class="sr-only">Wallet address, profile URL or username</label>
    <div class="flex flex-col gap-2 sm:flex-row">
      <input
        id="wallet-q"
        v-model="query"
        type="text"
        autocomplete="off"
        spellcheck="false"
        placeholder="0x… address, polymarket.com/@user or username"
        class="min-w-0 flex-1 rounded-lg border border-slate-700 bg-slate-900 px-4 py-2.5 text-slate-100 placeholder:text-slate-500 focus:border-sky-500 focus:outline-none"
      />
      <button
        type="submit"
        :disabled="busy || !query.trim()"
        class="rounded-lg bg-sky-600 px-5 py-2.5 font-medium text-white hover:bg-sky-500 disabled:opacity-50"
      >
        {{ busy ? 'Looking up…' : 'Analyze' }}
      </button>
    </div>
    <p v-if="error" class="mt-2 text-sm text-[#f0a0a0]" role="alert">{{ error }}</p>
  </form>
</template>
