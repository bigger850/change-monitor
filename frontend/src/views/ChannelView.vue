<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { channelApi } from '../api'
import { notify } from '../api/http'
import type { ChannelItem, FrequencyCap, NotificationChannelVO } from '../api/types'

const CHANNEL_TYPES = [
  { value: 'SITE', label: '站内' },
  { value: 'EMAIL', label: '邮件' }
]

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const loaded = ref(false)

const channels = ref<ChannelItem[]>([])
const frequencyCap = reactive<FrequencyCap>({ maxPerHour: 0, maxPerDay: 0 })
const newType = ref('EMAIL')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const d = await channelApi.get()
    channels.value = (d?.channels ?? []).map((c) => ({ ...c }))
    frequencyCap.maxPerHour = d?.frequencyCap?.maxPerHour ?? 0
    frequencyCap.maxPerDay = d?.frequencyCap?.maxPerDay ?? 0
    loaded.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载通知渠道失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)

function addChannel(): void {
  if (channels.value.some((c) => c.type === newType.value)) {
    notify('该渠道已存在')
    return
  }
  channels.value.push({ type: newType.value, enabled: true, target: '' })
}
function removeChannel(index: number): void {
  channels.value.splice(index, 1)
}

async function save(): Promise<void> {
  saving.value = true
  const req: NotificationChannelVO = {
    channels: channels.value.map((c) => ({ ...c })),
    frequencyCap: {
      maxPerHour: Number(frequencyCap.maxPerHour) || 0,
      maxPerDay: Number(frequencyCap.maxPerDay) || 0
    }
  }
  try {
    await channelApi.update(req)
    notify('已保存', 'success')
    await load() // 配置以后端响应为准
  } catch (e) {
    notify(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <div class="page-head"><h2>通知渠道</h2></div>

    <div class="panel card">
      <div v-if="error" class="error-box">{{ error }}</div>
      <div v-if="loading" class="loading">加载中…</div>
      <template v-else-if="loaded && !error">
        <h3 style="font-size: 14px; margin-bottom: 12px">渠道</h3>
        <table v-if="channels.length" class="table">
          <thead>
            <tr><th>类型</th><th>启用</th><th>接收目标</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in channels" :key="c.type">
              <td><span class="tag tag-blue">{{ c.type }}</span></td>
              <td><input v-model="c.enabled" type="checkbox" /></td>
              <td><input v-model="c.target" class="input" placeholder="如邮箱地址 / 站点内默认" /></td>
              <td><button class="btn btn-sm btn-danger" @click="removeChannel(i)">移除</button></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">后端暂无渠道配置，可在下方添加</div>

        <div class="inline-form" style="margin-top: 14px">
          <select v-model="newType" class="select" style="width: 140px">
            <option v-for="t in CHANNEL_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
          </select>
          <button class="btn btn-sm" @click="addChannel">+ 添加渠道</button>
        </div>

        <h3 style="font-size: 14px; margin: 20px 0 12px">频率上限</h3>
        <div class="form-grid" style="max-width: 420px">
          <label class="field"><span>每小时最多通知数</span><input v-model.number="frequencyCap.maxPerHour" class="input" type="number" min="0" /></label>
          <label class="field"><span>每天最多通知数</span><input v-model.number="frequencyCap.maxPerDay" class="input" type="number" min="0" /></label>
        </div>

        <div style="margin-top: 8px">
          <button class="btn btn-primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存配置' }}</button>
        </div>
      </template>
    </div>
  </div>
</template>
