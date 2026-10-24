import { ApiError } from './client'

export interface StreamHandlers {
  onText: (text: string) => void
  /** The model declined; any text received so far must be discarded. */
  onRefusal: (message: string) => void
  onError: (message: string) => void
  onDone: () => void
}

/**
 * POSTs to a server-sent-events endpoint and dispatches its events. EventSource can't POST, so this reads the
 * response body directly. Events: `text`, `done`, `refusal`, `error`, each with a JSON payload.
 */
export async function postStream(path: string, body: unknown, handlers: StreamHandlers, signal?: AbortSignal) {
  const res = await fetch(`/api${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify(body ?? {}),
    signal,
  })
  if (!res.ok || !res.body) {
    const err = await res.json().catch(() => null)
    throw new ApiError(res.status, err?.message || err?.error || `Request failed (${res.status})`)
  }

  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  let finished = false
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += value
    let end: number
    while ((end = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const raw = buffer.slice(0, end)
      buffer = buffer.slice(end).replace(/^\r?\n\r?\n/, '')
      finished = dispatch(raw, handlers) || finished
    }
  }
  if (!finished) handlers.onError('The answer was cut off. Try again.')
}

/** Handles one event block; returns whether it ended the stream. */
function dispatch(raw: string, handlers: StreamHandlers): boolean {
  let event = 'message'
  const data: string[] = []
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''))
  }
  const payload = data.length ? JSON.parse(data.join('\n')) : {}
  switch (event) {
    case 'text':
      handlers.onText(payload.text ?? '')
      return false
    case 'done':
      handlers.onDone()
      return true
    case 'refusal':
      handlers.onRefusal(payload.message ?? 'The AI declined to answer.')
      return true
    case 'error':
      handlers.onError(payload.message ?? 'Something went wrong.')
      return true
    default:
      return false
  }
}
