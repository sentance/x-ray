// Mirrors the backend records (com.xray.*). Timestamps: Wallet/Totals use epoch seconds, PnL points use epoch ms.

export interface Wallet {
  address: string
  name: string | null
  pseudonym: string | null
  newestTs: number | null
  oldestTs: number | null
  activityCount: number
  historyComplete: boolean
  lastSyncedAt: string | null
  syncVersion: number
}

export type SyncPhase = 'QUEUED' | 'PROFILE' | 'PNL' | 'ACTIVITY' | 'POSITIONS' | 'MARKETS'

export interface SyncJob {
  id: number
  address: string
  status: 'RUNNING' | 'DONE' | 'FAILED'
  phase: SyncPhase | null
  fetched: number
  expected: number | null
  startedAt: string
  finishedAt: string | null
  error: string | null
}

export interface SyncStatus {
  wallet: Wallet
  job: SyncJob | null
}

export interface Profile {
  name?: string | null
  pseudonym?: string | null
  bio?: string | null
  profileImage?: string | null
  createdAt?: string | null
  verifiedBadge?: boolean
}

export interface Totals {
  pnl: number
  realizedPnl: number
  unrealizedPnl: number
  volume: number
  fees: number
  makerRebate: number
  takerRebate: number
  rewards: number
  trades: number
  profitPerDollar: number
  firstTs: number | null
  lastTs: number | null
}

export interface Label {
  name: string
  kind: 'STRATEGY' | 'TRAIT'
  confidence: number
  evidence: string[]
}

export interface Overview {
  wallet: Wallet
  profile: Profile | null
  totals: Totals
  openPositionsValue: number
  summary: string
  labels: Label[]
  skillVerdict: string
  decayVerdict: string
}

export interface PnlPoint {
  ts: number
  pnl: number
  fees: number
  volume: number
}

export interface PnlMonth {
  month: string
  pnl: number
  volume: number
  fees: number
  rebates: number
  trades: number
  profitPerDollar: number
}

export interface Marker {
  date: string
  label: string
}

export interface PnlReport {
  totals: Totals
  series: PnlPoint[]
  months: PnlMonth[]
  markers: Marker[]
}

export interface Share {
  name: string
  trades: number
  volume: number
  share: number
  pnl: number
}

export interface Bucket {
  label: string
  count: number
  usdc: number
}

export interface MarketResult {
  conditionId: string
  title: string
  slug: string | null
  category: string | null
  marketType: string
  volume: number
  pnl: number
  bothSides: boolean
  pairCost: number | null
  resolved: boolean
  trades: number
}

export interface StrategyProfile {
  activity: {
    trades: number
    buys: number
    sells: number
    buyUsdc: number
    sellUsdc: number
    markets: number
    activeDays: number
    tradesPerActiveDay: number
    incomeUsdc: number
  }
  categories: Share[]
  marketTypes: Share[]
  cryptoAssets: Share[]
  bothSides: {
    markets: number
    shareOfMarkets: number
    shareOfVolume: number
    avgPairCost: number | null
    pairsBelowOneShare: number
  }
  exits: {
    sellToBuyRatio: number
    heldToResolutionShare: number
    mergeToBuyRatio: number
    redeemUsdc: number
  }
  entryPrices: {
    buckets: Bucket[]
    favouriteShare: number
    longshotShare: number
    avgEntryPrice: number
  }
  expiry: { buckets: Bucket[]; medianMinutes: number | null }
  tradeSizes: { p10: number; p50: number; p90: number; p99: number; mean: number }
  /** UTC; dayOfWeek is Monday-first. */
  timing: { hourOfDay: number[]; dayOfWeek: number[]; activeHours: number; medianGapSeconds: number | null }
  topWinners: MarketResult[]
  topLosers: MarketResult[]
  estimatedMarketPnl: number
}

export interface StrategyReport {
  profile: StrategyProfile
  classification: { summary: string; labels: Label[] }
}

export interface SkillReport {
  activeDays: number
  greenDays: number
  greenDayPct: number
  greenDaysPValue: number
  longestWinStreak: number
  streakCoinFlipProbability: number
  meanDailyPnl: number
  dailyStdDev: number
  sharpeAnnualized: number
  tStat: number
  tStatPValue: number
  maxDrawdown: number
  maxDrawdownPct: number
  pnlWithoutBest5Days: number
  top5MarketShare: number | null
  marketsTraded: number
  marketWinRate: number
  verdict: string
  reasons: string[]
}

export interface DecayWindow {
  label: string
  pnl: number
  volume: number
  profitPerDollar: number
  greenDayPct: number
  feeShare: number
  trades: number
  activeDays: number
}

export interface DecayReport {
  windows: DecayWindow[]
  monthly: { month: string; profitPerDollar: number; feeShare: number }[]
  verdict: string
  explanation: string
}

export type LeaderboardPeriod = 'DAY' | 'WEEK' | 'MONTH' | 'ALL'
export type LeaderboardOrder = 'PNL' | 'VOL'

export interface LeaderboardTag {
  /** FULL: from the wallet's synced history. QUICK: from its most recent ~1,000 activities. */
  source: 'FULL' | 'QUICK'
  summary: string
  labels: Label[]
  sampleTrades: number | null
  sampleFromTs: number | null
  sampleToTs: number | null
}

export interface LeaderboardRow {
  rank: number
  address: string
  name: string | null
  profileImage: string | null
  verified: boolean
  pnl: number
  volume: number
  tag: LeaderboardTag | null
  tagPending: boolean
}

export interface Leaderboard {
  rows: LeaderboardRow[]
  pending: number
}
