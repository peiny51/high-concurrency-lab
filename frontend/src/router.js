import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import InventoryView from './views/InventoryView.vue'
import CacheView from './views/CacheView.vue'
import CacheAsideView from './views/CacheAsideView.vue'
import CachePenetrationView from './views/CachePenetrationView.vue'
import CacheBreakdownView from './views/CacheBreakdownView.vue'
import CacheAvalancheView from './views/CacheAvalancheView.vue'
import RateLimitView from './views/RateLimitView.vue'

// Map each business case to its own page using browser-history navigation.
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: HomeView },
    { path: '/inventory', component: InventoryView },
    { path: '/rate-limit', component: RateLimitView },
    { path: '/cache', component: CacheView },
    { path: '/cache/aside', component: CacheAsideView },
    { path: '/cache/penetration', component: CachePenetrationView },
    { path: '/cache/breakdown', component: CacheBreakdownView },
    { path: '/cache/avalanche', component: CacheAvalancheView },
  ],
})

export default router
