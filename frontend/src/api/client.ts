import type {
  DecayReport,
  Overview,
  PnlReport,
  SkillReport,
  StrategyReport,
  SyncStatus,
  Wallet,
} from './types'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, { headers: { Accept: 'application/json' }, ...init })
  if (!res.ok) {
    // Spring error bodies carry the ResponseStatusException reason in `message`.
    const body = await res.json().catch(() => null)
    throw new ApiError(res.status, body?.message || body?.error || `Request failed (${res.status})`)
  }
  return res.json() as Promise<T>
}

const wallet = (address: string) => `/wallets/${encodeURIComponent(address)}`

export const api = {
  resolve: (q: string) =>
    request<{ address: string }>(`/wallets/resolve?q=${encodeURIComponent(q)}`).then((r) => r.address),
  recent: () => request<Wallet[]>('/wallets/recent'),
  startSync: (address: string) => request<SyncStatus>(`${wallet(address)}/sync`, { method: 'POST' }),
  syncStatus: (address: string) => request<SyncStatus>(`${wallet(address)}/sync`),
  overview: (address: string) => request<Overview>(`${wallet(address)}/overview`),
  pnl: (address: string) => request<PnlReport>(`${wallet(address)}/pnl`),
  strategy: (address: string) => request<StrategyReport>(`${wallet(address)}/strategy`),
  skill: (address: string) => request<SkillReport>(`${wallet(address)}/skill`),
  decay: (address: string) => request<DecayReport>(`${wallet(address)}/decay`),
}
