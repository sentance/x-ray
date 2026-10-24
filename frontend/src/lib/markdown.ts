import DOMPurify from 'dompurify'
import { marked } from 'marked'

/** Claude's Markdown as sanitized HTML: the text is model output, so it never reaches the DOM unsanitized. */
export function renderMarkdown(text: string): string {
  const html = marked.parse(text, { async: false, gfm: true, breaks: false })
  return DOMPurify.sanitize(html, { USE_PROFILES: { html: true } })
}
