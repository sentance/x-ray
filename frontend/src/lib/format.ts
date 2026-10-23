const compact = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 })
const whole = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 })

/** $1.2M, -$3.4K, $512. */
export function usd(value: number | null | undefined, opts: { signed?: boolean } = {}): string {
  if (value == null || Number.isNaN(value)) return '—'
  const abs = Math.abs(value)
  const body = abs >= 10_000 ? compact.format(abs) : abs >= 100 ? whole.format(abs) : abs.toFixed(2)
  const sign = value < 0 ? '−' : opts.signed && value > 0 ? '+' : ''
  return `${sign}$${body}`
}

export function num(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '—'
  return Math.abs(value) >= 10_000 ? compact.format(value) : whole.format(value)
}

export function pct(value: number | null | undefined, digits = 0): string {
  if (value == null || Number.isNaN(value)) return '—'
  return `${(value * 100).toFixed(digits)}%`
}

/** Profit per dollar traded, shown in cents: 6.1¢. */
export function cents(value: number | null | undefined): string {
  if (value == null || Number.isNaN(value)) return '—'
  return `${value < 0 ? '−' : ''}${Math.abs(value * 100).toFixed(1)}¢`
}

/** p-values: "< 0.001" below that, otherwise three decimals. */
export function pValue(value: number): string {
  return value < 0.001 ? '< 0.001' : value.toFixed(3)
}

export function date(epochSeconds: number | null | undefined): string {
  if (epochSeconds == null) return '—'
  return new Date(epochSeconds * 1000).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' })
}

export function ago(iso: string | null | undefined): string {
  if (!iso) return 'never'
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.round(minutes / 60)
  if (hours < 48) return `${hours}h ago`
  return `${Math.round(hours / 24)}d ago`
}

export function shortAddress(address: string): string {
  return `${address.slice(0, 6)}…${address.slice(-4)}`
}

export function duration(seconds: number | null | undefined): string {
  if (seconds == null) return '—'
  if (seconds < 90) return `${Math.round(seconds)}s`
  if (seconds < 5400) return `${Math.round(seconds / 60)}m`
  if (seconds < 172_800) return `${(seconds / 3600).toFixed(1)}h`
  return `${(seconds / 86_400).toFixed(1)}d`
}

export function walletName(w: { name: string | null; pseudonym: string | null; address: string }): string {
  return w.name || w.pseudonym || shortAddress(w.address)
}
