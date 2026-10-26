export interface PaperSettings {
  bankroll: number
  maxCommitted: number
  maxLoss: number
  maxCycleLoss: number
  slippage: number
  gasPerConversion: number
  hedgeDelayMs: number
}
export interface PaperOutcome {
  conditionId: string; name: string; yes: string; no: string; feeRate: number
  minSize: number; tick: number; rewardMin: number; rewardSpread: number
  dailyPool: number; rewardAsset: string; rewardUntil: number; endAt: number; tradable: boolean
}
export interface PaperGroup {
  id: string; slug: string; title: string; conversionFeeBps: number; verifiedAt: number; outcomes: PaperOutcome[]
}
export interface PaperEntry { at: number; type: string; amount: number; detail: string }
export interface PaperPosition {
  token: string; groupId: string; name: string; shares: number; feeRate: number; minSize: number; sellable: boolean
}
export interface PaperState {
  id: string; policy: string; status: string; message: string; feed: string; settings: PaperSettings
  createdAt: number; updatedAt: number; lastScanAt: number; lastFeedAt: number; quoteMillis: number
  cash: number; fees: number; gas: number; rewards: number; makerRebates: number; takerRebates: number
  liquidationValue: number; fills: number; conversions: number
  quote: null | { name: string; token: string; hedgeToken: string; price: number; size: number; queueAhead: number; reserve: number; createdAt: number; expiresAt: number }
  pending: null | { shares: number; filledAt: number; hedgeAt: number }
  groups: PaperGroup[]; positions: Record<string, PaperPosition>; ledger: PaperEntry[]; skipped: Record<string, number>
}
export interface PaperView {
  state: PaperState; equity: number; pnl: number; reserved: number; available: number; active: boolean; incentiveNote: string
}
async function request<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api/paper${path}`, body === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  if (!res.ok) {
    const data = await res.json().catch(() => null)
    throw new Error(data?.message || `Paper request failed (${res.status})`)
  }
  return res.json() as Promise<T>
}
export const paperApi = {
  defaults: () => request<PaperSettings>('/defaults'),
  create: (settings: PaperSettings) => request<PaperView>('', settings),
  view: (id: string) => request<PaperView>(`/${encodeURIComponent(id)}`),
  resume: (id: string) => request<PaperView>(`/${encodeURIComponent(id)}/resume`, {}),
  pause: (id: string) => request<PaperView>(`/${encodeURIComponent(id)}/pause`, {}),
}
