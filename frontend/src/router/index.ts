import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import AppLayout from '../layout/AppLayout.vue'
import { isLoggedIn } from '../api/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue') },
      { path: 'subscriptions', name: 'subscriptions', component: () => import('../views/SubscriptionView.vue') },
      { path: 'events', name: 'events', component: () => import('../views/EventView.vue') },
      {
        path: 'notification-channels',
        name: 'channels',
        component: () => import('../views/ChannelView.vue')
      },
      { path: 'admin', name: 'admin', component: () => import('../views/AdminView.vue') }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

// SPA history 模式；生产由 Nginx 做 history fallback（见 00-项目启动.md）
const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!isLoggedIn()) return { path: '/login', query: { redirect: to.fullPath } }
  return true
})

export default router
