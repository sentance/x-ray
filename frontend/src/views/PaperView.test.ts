import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PaperView from './PaperView.vue'
import { paperApi, type PaperView as View } from '@/api/paper'

vi.mock('@/api/paper', () => ({ paperApi: { create: vi.fn(), view: vi.fn(), resume: vi.fn(), pause: vi.fn() } }))
afterEach(() => { vi.clearAllMocks(); localStorage.clear() })
const sid = '1a6ca470-3c76-4bf0-b7be-e42bc05aa367'
function fixture(): View {
  return {
    active: true, equity: 1000, pnl: 0, reserved: 0, available: 1000, incentiveNote: 'Pools are not earned income.',
    state: {
      id: sid, policy: 'akroll-pair-v1', status: 'RUNNING', message: 'Scanning for a qualifying pair', feed: 'Connected',
      settings: { bankroll: 1000, maxCommitted: 400, maxLoss: 100, maxCycleLoss: 2, slippage: .01, gasPerConversion: .05, hedgeDelayMs: 1000 },
      createdAt: 1234, updatedAt: 1234, lastScanAt: 1234, lastFeedAt: 1234, quoteMillis: 0,
      cash: 1000, fees: 0, gas: 0, rewards: 0, makerRebates: 0, takerRebates: 0, liquidationValue: 0, fills: 0, conversions: 0,
      quote: null, pending: null, groups: [], positions: {}, ledger: [], skipped: {},
    },
  }
}
async function render(path: string) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/paper/:id?', component: PaperView }] })
  await router.push(path); await router.isReady()
  const wrapper = mount(PaperView, { global: { plugins: [router] } }); await flushPromises()
  return { wrapper, router }
}
describe('paper session controls', () => {
  it('starts with the frozen $1000 policy and retains the session URL', async () => {
    vi.mocked(paperApi.create).mockResolvedValue(fixture()); vi.mocked(paperApi.view).mockResolvedValue(fixture())
    const { wrapper, router } = await render('/paper')
    try {
      await wrapper.get('form').trigger('submit'); await flushPromises()
      expect(paperApi.create).toHaveBeenCalledWith(expect.objectContaining({ bankroll: 1000, hedgeDelayMs: 1000 }))
      expect(router.currentRoute.value.path).toBe(`/paper/${sid}`)
      expect(wrapper.text()).toContain('Incentives: $0 credited')
      expect(localStorage.getItem('xray-paper-sessions')).toContain(sid)
    } finally { wrapper.unmount() }
  })
  it('shows API failures without claiming a session started', async () => {
    vi.mocked(paperApi.create).mockRejectedValue(new Error('Paper runner capacity reached'))
    const { wrapper } = await render('/paper')
    try {
      await wrapper.get('form').trigger('submit'); await flushPromises()
      expect(wrapper.get('[role="alert"]').text()).toContain('capacity reached')
      expect(wrapper.find('form').exists()).toBe(true)
    } finally { wrapper.unmount() }
  })
  it('pauses the existing test and preserves inventory', async () => {
    const running = fixture(); running.state.positions.n = { token: 'n', groupId: 'event', name: 'Candidate NO', shares: 5, feeRate: 0, minSize: 5, sellable: true }
    vi.mocked(paperApi.view).mockResolvedValue(running)
    vi.mocked(paperApi.pause).mockResolvedValue({ ...running, active: false, state: { ...running.state, status: 'PAUSED' } })
    const { wrapper } = await render(`/paper/${sid}`)
    try {
      await wrapper.findAll('button').find(b => b.text() === 'Pause')!.trigger('click'); await flushPromises()
      expect(paperApi.pause).toHaveBeenCalledWith(sid)
      expect(wrapper.text()).toContain('Candidate NO')
      expect(wrapper.text()).toContain('Resume with fresh data')
    } finally { wrapper.unmount() }
  })
})
