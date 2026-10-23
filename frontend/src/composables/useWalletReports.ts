import { onBeforeUnmount, ref, shallowRef, watch, type Ref } from 'vue'
import { api, ApiError } from '@/api/client'
import type { DecayReport, Overview, PnlReport, SkillReport, StrategyReport, SyncStatus } from '@/api/types'

const STATUS_POLL_MS = 3_000
// Reports on a syncing wallet are recomputed from partial data on every request (overview and strategy take
// tens of seconds on big wallets), so refresh them sparingly and never overlap two rounds.
const REPORT_REFRESH_MS = 60_000

/**
 * Loads a wallet's sync status and reports. A wallet that was never analyzed gets a sync started automatically;
 * while a sync runs, the status is polled and the reports are refreshed from the partial data.
 */
export function useWalletReports(address: Ref<string>) {
  const status = shallowRef<SyncStatus | null>(null)
  const overview = shallowRef<Overview | null>(null)
  const pnl = shallowRef<PnlReport | null>(null)
  const strategy = shallowRef<StrategyReport | null>(null)
  const skill = shallowRef<SkillReport | null>(null)
  const decay = shallowRef<DecayReport | null>(null)
  const error = ref<string | null>(null)
  const loadingReports = ref(false)

  let timer: ReturnType<typeof setTimeout> | undefined
  let lastReportLoad = 0
  let generation = 0

  function hasData(s: SyncStatus | null): boolean {
    if (!s) return false
    if (s.wallet.historyComplete) return true
    // P&L arrives before the activity walk; earlier phases have nothing to report on.
    return !!s.job && ['ACTIVITY', 'POSITIONS', 'MARKETS'].includes(s.job.phase ?? '')
  }

  let reportsInFlight = false
  let finalLoadQueued = false

  /**
   * Requests every report; each one is shown as soon as it arrives, so the cheap ones don't wait on the slow.
   * `final` marks the load after a sync ends: it must not be dropped if a refresh round is still running.
   */
  async function loadReports(gen: number, final = false) {
    if (reportsInFlight) {
      if (final) finalLoadQueued = true
      return
    }
    reportsInFlight = true
    loadingReports.value = true
    lastReportLoad = Date.now()
    const a = address.value
    const put =
      <T>(target: { value: T | null }) =>
      (value: T) => {
        if (gen === generation) target.value = value
      }
    await Promise.allSettled([
      api.pnl(a).then(put(pnl)),
      api.decay(a).then(put(decay)),
      api.skill(a).then(put(skill)),
      api.strategy(a).then(put(strategy)),
      api.overview(a).then(put(overview)),
    ])
    if (gen !== generation) return
    reportsInFlight = false
    loadingReports.value = false
    if (finalLoadQueued) {
      finalLoadQueued = false
      void loadReports(gen, true)
    }
  }

  async function tick(gen: number) {
    try {
      const s = await api.syncStatus(address.value)
      if (gen !== generation) return
      const wasRunning = status.value?.job?.status === 'RUNNING'
      status.value = s
      const running = s.job?.status === 'RUNNING'
      const stale = running && Date.now() - lastReportLoad > REPORT_REFRESH_MS
      // Load on first sight, on every refresh interval while syncing, and once more when the sync ends.
      // Not awaited: status polling carries on while the slow reports compute.
      const finished = wasRunning && !running
      if (hasData(s) && (lastReportLoad === 0 || stale || finished)) void loadReports(gen, finished)
      if (running) timer = setTimeout(() => tick(gen), STATUS_POLL_MS)
    } catch (e) {
      if (gen !== generation) return
      error.value = e instanceof Error ? e.message : String(e)
    }
  }

  async function start(gen: number) {
    try {
      status.value = await api.startSync(address.value)
      if (gen === generation) timer = setTimeout(() => tick(gen), STATUS_POLL_MS)
    } catch (e) {
      if (gen === generation) error.value = e instanceof Error ? e.message : String(e)
    }
  }

  async function load() {
    clearTimeout(timer)
    const gen = ++generation
    status.value = overview.value = pnl.value = strategy.value = skill.value = decay.value = null
    error.value = null
    lastReportLoad = 0
    reportsInFlight = finalLoadQueued = false
    loadingReports.value = false
    try {
      const s = await api.syncStatus(address.value)
      if (gen !== generation) return
      status.value = s
      if (!s.wallet.lastSyncedAt && s.job?.status !== 'RUNNING' && !s.wallet.historyComplete) {
        await start(gen)
      } else {
        await tick(gen)
      }
    } catch (e) {
      if (gen !== generation) return
      if (e instanceof ApiError && e.status === 404) await start(gen)
      else error.value = e instanceof Error ? e.message : String(e)
    }
  }

  /** Fetches anything new since the last sync (the backend resumes from its checkpoint). */
  async function refresh() {
    clearTimeout(timer)
    error.value = null
    await start(generation)
  }

  watch(address, load, { immediate: true })
  onBeforeUnmount(() => {
    generation++
    clearTimeout(timer)
  })

  return { status, overview, pnl, strategy, skill, decay, error, loadingReports, refresh }
}
