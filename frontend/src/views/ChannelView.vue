<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { channelApi } from '../api'
import { notify } from '../api/http'
import type { ChannelConfigVO } from '../api/types'

const CHANNEL_TYPES = [
  { value: 'SITE', label: '站内' },
  { value: 'EMAIL', label: '邮件' }
]

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const loaded = ref(false)

/** 后端按「每个渠道一条配置」存储，GET 直接返回数组 */
const channels = ref<ChannelConfigVO[]>([])
const newChannel = ref('EMAIL')

function labelOf(channel: string): string {
  return CHANNEL_TYPES.find((t) => t.value === channel)?.label ?? channel
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const list = await channelApi.get()
    // 只做展示层拷贝，字段与状态以后端为准
    channels.value = (list ?? []).map((c) => ({ ...c }))
    loaded.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载通知渠道失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)

function addChannel(): void {
  if (channels.value.some((c) => c.channel === newChannel.value)) {
    notify('该渠道已存在')
    return
  }
  channels.value.push({ channel: newChannel.value, enabled: true, target: '', freqLimitPerDay: 0 })
}

function removeChannel(index: number): void {
  // 仅从表单移除，保存时后端按提交内容重建该用户的渠道配置
  channels.value.splice(index, 1)
}

async function save(): Promise<void> {
  saving.value = true
  try {
    // 后端 PUT 一次只接受一条配置，逐条提交；全部成功后再以后端返回为准刷新
    for (const c of channels.value) {
      await channelApi.update({
        channel: c.channel,
        target: c.target ?? '',
        enabled: !!c.enabled,
        freqLimitPerDay: Number(c.freqLimitPerDay) || 0
      })
    }
    notify('已保存', 'success')
    await load()
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
        <h3 style="font-size: 14px; margin-bottom: 12px">渠道配置</h3>
        <table v-if="channels.length" class="table">
          <thead>
            <tr><th>渠道</th><th>启用</th><th>接收目标</th><th>每日上限</th><th>最近发送</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in channels" :key="c.channel">
              <td><span class="tag tag-blue">{{ labelOf(c.channel) }}</span></td>
              <td><input v-model="c.enabled" type="checkbox" /></td>
              <td><input v-model="c.target" class="input" placeholder="邮箱地址 / 站内留空" /></td>
              <td>
                <input
                  v-model.number="c.freqLimitPerDay"
                  class="input"
                  style="width: 100px"
                  type="number"
                  min="0"
                />
              </td>
              <td class="mono">{{ c.lastSentAt ? c.lastSentAt.replace('T', ' ').slice(0, 19) : '—' }}</td>
              <td><button class="btn btn-sm btn-danger" @click="removeChannel(i)">移除</button></td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">后端暂无渠道配置，可在下方添加</div>

        <div class="inline-form" style="margin-top: 14px">
          <select v-model="newChannel" class="select" style="width: 140px">
            <option v-for="t in CHANNEL_TYPES" :key="t.value" :value="t.value">{{ t.label }}</option>
          </select>
          <button class="btn btn-sm" @click="addChannel">+ 添加渠道</button>
        </div>

        <div style="margin-top: 16px">
          <button class="btn btn-primary" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存配置' }}
          </button>
        </div>
      </template>
    </div>
  </div>
</template>
