<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { authApi } from '../api'
import { clearToken } from '../api/auth'
import { toasts } from '../api/http'
import type { UserVO } from '../api/types'

const router = useRouter()
const user = ref<UserVO | null>(null)
const userError = ref('')

const navs = [
  { to: '/dashboard', label: '监控大盘', icon: '▤' },
  { to: '/subscriptions', label: '我的订阅', icon: '◆' },
  { to: '/events', label: '变更时间线', icon: '◷' },
  { to: '/notification-channels', label: '通知渠道', icon: '✉' },
  { to: '/admin', label: '管理端', icon: '⚙' }
]

onMounted(async () => {
  try {
    user.value = await authApi.me()
  } catch (e) {
    userError.value = e instanceof Error ? e.message : '获取用户信息失败'
  }
})

async function logout(): Promise<void> {
  try {
    await authApi.logout()
  } catch {
    // 注销接口失败也清理本地登录态；权限以后端为准
  }
  clearToken()
  void router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        变更监控
        <span class="brand-sub">CHANGE MONITOR</span>
      </div>
      <nav class="nav">
        <RouterLink
          v-for="n in navs"
          :key="n.to"
          :to="n.to"
          class="nav-item"
          active-class="active"
        >
          <span class="nav-icon">{{ n.icon }}</span>{{ n.label }}
        </RouterLink>
      </nav>
      <div class="sidebar-foot">P0 · 前端呈现层</div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">网页变更订阅与实时通知平台</div>
        <div class="topbar-right">
          <span v-if="user" class="user-chip">
            {{ user.email }}<em v-if="user.role"> · {{ user.role }}</em>
          </span>
          <span v-else-if="userError" class="muted">{{ userError }}</span>
          <button class="btn btn-ghost btn-sm" @click="logout">退出</button>
        </div>
      </header>
      <main class="content">
        <RouterView />
      </main>
    </div>

    <div class="toast-wrap">
      <div v-for="t in toasts" :key="t.id" :class="['toast', t.type]">{{ t.text }}</div>
    </div>
  </div>
</template>
