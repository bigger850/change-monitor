<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '../api'
import { saveTokens } from '../api/auth'
import { notify } from '../api/http'

const route = useRoute()
const router = useRouter()
const mode = ref<'login' | 'register'>('login')
const username = ref('')
const email = ref('')
const password = ref('')
const submitting = ref(false)
const errorMsg = ref('')

const isRegister = computed(() => mode.value === 'register')

// 切换模式时清掉上次的报错，避免残留误导
watch(mode, () => {
  errorMsg.value = ''
})

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function validate(): string {
  if (!username.value.trim()) return isRegister.value ? '请输入用户名' : '请输入用户名或邮箱'
  if (isRegister.value) {
    if (!email.value.trim()) return '请输入邮箱'
    if (!EMAIL_RE.test(email.value.trim())) return '邮箱格式不正确'
  }
  if (!password.value) return '请输入密码'
  if (isRegister.value && password.value.length < 6) return '密码至少 6 位'
  return ''
}

async function submit(): Promise<void> {
  errorMsg.value = ''
  const invalid = validate()
  if (invalid) {
    errorMsg.value = invalid
    return
  }
  submitting.value = true
  try {
    if (mode.value === 'login') {
      // 登录：后端按 username 认证，登录态以后端返回的 token 为准
      const vo = await authApi.login({
        username: username.value.trim(),
        password: password.value
      })
      saveTokens(vo.accessToken, vo.refreshToken || '')
      notify('登录成功', 'success')
      void router.push((route.query.redirect as string) || '/dashboard')
    } else {
      // 注册：后端只返回用户信息，不下发 token，因此切回登录
      await authApi.register({
        username: username.value.trim(),
        email: email.value.trim(),
        password: password.value
      })
      mode.value = 'login'
      password.value = ''
      notify('注册成功，请登录', 'success')
    }
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
          <span>{{ isRegister ? '用户名' : '用户名 / 邮箱' }}</span>
          <input
            v-model="username"
            class="input"
            type="text"
            autocomplete="username"
            :placeholder="isRegister ? '请输入用户名' : '用户名或邮箱，如 admin'"
          />
        </label>
        <label v-if="isRegister" class="field">
          <span>邮箱</span>
          <input
            v-model="email"
            class="input"
            type="email"
            autocomplete="email"
            placeholder="you@example.com"
          />
        </label>
        <label class="field">
          <span>密码</span>
          <input
            v-model="password"
            class="input"
            type="password"
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
            :placeholder="isRegister ? '至少 6 位' : '请输入密码'"
          />
        </label>
        <div v-if="errorMsg" class="error-text">{{ errorMsg }}</div>
        <button class="btn btn-primary btn-block" type="submit" :disabled="submitting">
          {{ submitting ? '处理中…' : isRegister ? '注册' : '登录' }}
        </button>
      </form>
    </div>
  </div>
</template>
