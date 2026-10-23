import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    {
      path: '/wallet/:address(0x[0-9a-fA-F]{40})',
      name: 'wallet',
      component: () => import('@/views/WalletView.vue'),
      props: true,
    },
    { path: '/leaderboard', name: 'leaderboard', component: () => import('@/views/LeaderboardView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  // Filter changes only touch the query; keep the reader's place for those.
  scrollBehavior: (to, from) => (to.path === from.path ? false : { top: 0 }),
})

export default router
