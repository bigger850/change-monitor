<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '../api'
import { saveTokens } from '../api/auth'
import { notify } from '../api/http'

const route = useRoute()
const router = useRouter()
const mode = ref<'login' | 'register'>('login')
const email = ref('')
const password = ref('')
const submitting = ref(false)
const errorMsg = ref('')

async function submit(): Promise<void> {
  errorMsg.value = ''
  if (!email.value.trim() || !password.value) {
    errorMsg.value = '请输入邮箱和密码'
    return
  }
  submitting.value = true
  try {
    const vo =
      mode.value === 'login'
        ? await authApi.login({ email: email.value.trim(), password: password.value })
        : await authApi.register({ email: email.value.trim(), password: password.value })
    // 注册若未直接下发 token，则切回登录；登录态以后端为准
    if (!vo || !vo.accessToken) {
      mode.value = 'login'
      notify('注册成功，请登录', 'success')
      return
    }
    saveTokens(vo.accessToken, vo.refreshToken || '')
    notify(mode.value === 'login' ? '登录成功' : '注册成功', 'success')
    void router.push((route.query.redirect as string) || '/dashboard')
  } catch (e) {
    errorMsg.value = e instanceof Error ? e.message : '操作失败'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card card">
      <h1>网页变更监控</h1>
      <p class="muted">订阅网页变更，实时接收通知</p>
      <div class="login-tabs">
        <button :class="['tab', { active: mode === 'login' }]" @click="mode = 'login'">登录</button>
        <button :class="['tab', { active: mode === 'register' }]" @click="mode = 'register'">注册</button>
      </div>
      <form @submit.prevent="submit">
        <label class="field">
          <span>邮箱</span>
          <input v-model="email" class="input" type="email" autocomplete="username" placeholder="you@example.com" />
        </label>
        <label class="field">
          <span>密码</span>
          <input v-model="password" class="input" type="password" autocomplete="current-password" placeholder="请输入密码" />
        </label>
        <div v-if="errorMsg" class="error-text">{{ errorMsg }}</div>
        <button class="btn btn-primary btn-block" type="submit" :disabled="submitting">
          {{ submitting ? '处理中…' : mode === 'login' ? '登录' : '注册' }}
        </button>
      </form>
    </div>
  </div>
</template>
