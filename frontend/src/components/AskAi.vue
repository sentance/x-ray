<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '@/api/client'
import { postStream } from '@/api/stream'
import { renderMarkdown } from '@/lib/markdown'
import Card from './Card.vue'

/** @param ready whether the wallet's sync has finished; the AI only sees finished reports */
const props = defineProps<{ address: string; ready: boolean }>()

interface Turn {
  role: 'user' | 'assistant'
  text: string
}

const SUGGESTIONS = [
  'Is this edge likely to last?',
  'What would make this strategy stop working?',
  'Could someone else copy this, and what would it take?',
]

const enabled = ref<boolean | null>(null)
const narrative = ref('')
const turns = ref<Turn[]>([])
const question = ref('')
const streaming = ref<'narrative' | 'chat' | null>(null)
const pendingText = ref('')
const error = ref<string | null>(null)
let abort: AbortController | null = null
const input = ref<HTMLTextAreaElement | null>(null)

watch(
  () => props.address,
  async (address) => {
    abort?.abort()
    narrative.value = ''
    turns.value = []
    error.value = null
    try {
      const r = await api.narrative(address)
      if (address !== props.address) return
      enabled.value = r.enabled
      narrative.value = r.text
    } catch {
      enabled.value = null
    }
  },
  { immediate: true },
)
onBeforeUnmount(() => abort?.abort())

function message(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof DOMException && e.name === 'AbortError') return ''
  return e instanceof Error ? e.message : String(e)
}

async function explain() {
  if (streaming.value) return
  streaming.value = 'narrative'
  pendingText.value = ''
  error.value = null
  abort = new AbortController()
  try {
    await postStream(
      `/wallets/${props.address}/narrative`,
      {},
      {
        onText: (t) => (pendingText.value += t),
        onDone: () => (narrative.value = pendingText.value),
        onRefusal: (m) => (error.value = m),
        onError: (m) => (error.value = m),
      },
      abort.signal,
    )
  } catch (e) {
    error.value = message(e) || null
  } finally {
    streaming.value = null
    pendingText.value = ''
  }
}

async function ask(text = question.value) {
  const q = text.trim()
  if (!q || streaming.value) return
  const history = turns.value.slice()
  turns.value = [...history, { role: 'user', text: q }]
  question.value = ''
  streaming.value = 'chat'
  pendingText.value = ''
  error.value = null
  abort = new AbortController()
  let answered = false
  try {
    await postStream(
      `/wallets/${props.address}/chat`,
      { turns: history, question: q },
      {
        onText: (t) => (pendingText.value += t),
        onDone: () => {
          answered = true
          turns.value = [...turns.value, { role: 'assistant', text: pendingText.value }]
        },
        onRefusal: (m) => (error.value = m),
        onError: (m) => (error.value = m),
      },
      abort.signal,
    )
  } catch (e) {
    error.value = message(e) || null
  } finally {
    if (!answered) {
      // Keep the conversation well-formed: drop the unanswered question and give it back for a retry.
      turns.value = history
      question.value = q
    }
    streaming.value = null
    pendingText.value = ''
    await nextTick()
    if (!answered) input.value?.focus()
  }
}

function onKey(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    ask()
  }
}

const narrativeHtml = computed(() =>
  renderMarkdown(streaming.value === 'narrative' ? pendingText.value : narrative.value),
)
</script>

<template>
  <Card title="Ask AI" subtitle="A written explanation of this wallet's strategy, and answers to your questions">
    <p v-if="enabled === false" class="text-sm text-slate-400">
      Ask AI isn't set up on this server (it needs an OpenAI API key).
    </p>
    <p v-else-if="!ready" class="text-sm text-slate-400">
      Available once the sync finishes, so the AI works from the complete history.
    </p>

    <template v-else-if="enabled">
      <div v-if="narrative || streaming === 'narrative'">
        <p v-if="streaming === 'narrative' && !pendingText" class="text-sm text-slate-400" role="status">
          Reading the reports…
        </p>
        <!-- eslint-disable-next-line vue/no-v-html -- sanitized in renderMarkdown -->
        <div class="md text-sm text-slate-200" aria-live="polite" v-html="narrativeHtml" />
      </div>
      <button
        v-else
        type="button"
        class="rounded-lg bg-sky-600 px-4 py-2 font-medium text-white hover:bg-sky-500 disabled:opacity-50"
        :disabled="!!streaming"
        @click="explain"
      >
        Explain this strategy
      </button>

      <div v-if="turns.length || streaming === 'chat'" class="mt-5 space-y-4 border-t border-slate-800 pt-4">
        <template v-for="(t, i) in turns" :key="i">
          <p v-if="t.role === 'user'" class="ml-auto w-fit max-w-[85%] rounded-lg bg-slate-800 px-3 py-2 text-sm text-slate-100">
            {{ t.text }}
          </p>
          <!-- eslint-disable-next-line vue/no-v-html -- sanitized in renderMarkdown -->
          <div v-else class="md text-sm text-slate-200" v-html="renderMarkdown(t.text)" />
        </template>
        <template v-if="streaming === 'chat'">
          <p v-if="!pendingText" class="text-sm text-slate-400" role="status">Thinking…</p>
          <!-- eslint-disable-next-line vue/no-v-html -- sanitized in renderMarkdown -->
          <div v-else class="md text-sm text-slate-200" aria-live="polite" v-html="renderMarkdown(pendingText)" />
        </template>
      </div>

      <p v-if="error" class="mt-3 text-sm text-[#f0a0a0]" role="alert">{{ error }}</p>

      <div class="mt-4">
        <div v-if="!turns.length && !streaming" class="mb-2 flex flex-wrap gap-2">
          <button
            v-for="s in SUGGESTIONS"
            :key="s"
            type="button"
            class="rounded-full border border-slate-700 px-3 py-1 text-xs text-slate-300 hover:border-slate-500 hover:text-white"
            @click="ask(s)"
          >
            {{ s }}
          </button>
        </div>
        <form class="flex gap-2" @submit.prevent="ask()">
          <label for="ask-ai" class="sr-only">Ask the AI about this wallet</label>
          <textarea
            id="ask-ai"
            ref="input"
            v-model="question"
            rows="1"
            maxlength="2000"
            placeholder="Ask about this wallet…"
            class="min-h-10 flex-1 resize-y rounded-lg border border-slate-700 bg-slate-950 px-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-sky-500 focus:outline-none"
            @keydown="onKey"
          />
          <button
            type="submit"
            :disabled="!!streaming || !question.trim()"
            class="rounded-lg bg-sky-600 px-4 py-2 text-sm font-medium text-white hover:bg-sky-500 disabled:opacity-50"
          >
            Ask
          </button>
        </form>
        <p class="mt-2 text-xs text-slate-500">
          The AI only sees the reports on this page. It describes what the wallet did; it isn't trading advice.
        </p>
      </div>
    </template>
  </Card>
</template>

<style scoped>
.md :deep(h1),
.md :deep(h2),
.md :deep(h3) {
  margin: 1rem 0 0.375rem;
  font-weight: 600;
  color: var(--color-slate-50);
}
.md :deep(h1) {
  font-size: 1.05rem;
}
.md :deep(h2),
.md :deep(h3) {
  font-size: 0.95rem;
}
.md :deep(p) {
  margin: 0.5rem 0;
  line-height: 1.6;
}
.md :deep(ul),
.md :deep(ol) {
  margin: 0.5rem 0;
  padding-left: 1.25rem;
}
.md :deep(ul) {
  list-style: disc;
}
.md :deep(ol) {
  list-style: decimal;
}
.md :deep(li) {
  margin: 0.2rem 0;
}
.md :deep(strong) {
  color: var(--color-slate-50);
}
.md :deep(code) {
  border-radius: 0.25rem;
  background: var(--color-slate-800);
  padding: 0.05rem 0.3rem;
  font-size: 0.85em;
}
.md :deep(table) {
  margin: 0.5rem 0;
  border-collapse: collapse;
}
.md :deep(th),
.md :deep(td) {
  border-bottom: 1px solid var(--color-slate-800);
  padding: 0.25rem 0.75rem 0.25rem 0;
  text-align: left;
}
.md :deep(:is(h1, h2, h3, p, ul, ol):first-child) {
  margin-top: 0;
}
</style>
