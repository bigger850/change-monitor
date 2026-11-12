<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { eventApi } from '../api'
import { getToken } from '../api/auth'
import { notify } from '../api/http'
import type { ChangeEventVO, EventQuery } from '../api/types'

const rows = ref<ChangeEventVO[]>([])
const loading = ref(false)
const error = ref('')
const page = ref(1)
const size = 20
const total = ref(0)
const keyword = ref('')
const subscriptionId = ref('')

const sseStatus = ref<'connecting' | 'open' | 'error'>('connecting')
const lastEventId = ref('')
let es: EventSource | null = null

const detail = ref<ChangeEventVO | null>(null)
const detailError = ref('')
const detailOpen = ref(false)

function query(): { subscriptionId?: string; keyword?: string } {
  return {
    subscriptionId: subscriptionId.value.trim() || undefined,
    keyword: keyword.value.trim() || undefined
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const params: EventQuery = { page: page.value, size, ...query() }
    const data = await eventApi.list(params)
    rows.value = data.list ?? []
    total.value = data.total ?? 0
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载变更时间线失败'
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = 1
  void load()
}
function prevPage(): void {
  if (page.value > 1) {
    page.value--
    void load()
  }
}
function nextPage(): void {
  if (page.value * size < total.value) {
    page.value++
    void load()
  }
}

async function markRead(row: ChangeEventVO): Promise<void> {
  try {
    // 契约路径 /change-events/{id}/read，VO 主键为 eventNo
    await eventApi.markRead(row.eventNo)
    notify('已标记已读', 'success')
    await load() // readFlag 以后端响应为准，不本地改状态
  } catch (e) {
    notify(e instanceof Error ? e.message : '标记失败')
  }
}

async function exportCsv(): Promise<void> {
  try {
    // 后端生成 CSV 文件流，前端只触发下载
    await eventApi.exportCsv(query())
    notify('导出已开始', 'success')
  } catch (e) {
    notify(e instanceof Error ? e.message : '导出失败')
  }
}

async function openDetail(row: ChangeEventVO): Promise<void> {
  detail.value = null
  detailError.value = ''
  detailOpen.value = true
  try {
    detail.value = await eventApi.get(row.eventNo)
  } catch (e) {
    detailError.value = e instanceof Error ? e.message : '加载详情失败'
  }
}

function connectSse(): void {
  // EventSource 不支持自定义请求头；JwtAuthFilter 只读取 ?token= 查询参数
  // 断线重连时浏览器自动带 Last-Event-ID，后端补推期间错过的已确认事件
  const token = getToken()
  es = new EventSource(`/api/v1/stream/events?token=${encodeURIComponent(token)}`)
  sseStatus.value = 'connecting'
  es.onopen = () => {
    sseStatus.value = 'open'
  }
  const onEvent = (e: MessageEvent): void => {
    if (e.lastEventId) lastEventId.value = e.lastEventId
    try {
      const evt = JSON.parse(e.data) as ChangeEventVO
      if (!evt || !evt.eventNo) return
      // 纯展示增量：把后端实时推送/断线补推的事件加入时间线，不本地判定变更、不改状态
      if (!rows.value.some((x) => x.eventNo === evt.eventNo)) rows.value.unshift(evt)
    } catch {
      // 忽略心跳帧 / 非 JSON 消息
    }
  }
  // 后端事件名为 "change"，命名事件不会触发 onmessage，必须显式监听
  es.addEventListener('change', onEvent)
  es.onmessage = onEvent
  es.onerror = () => {
    sseStatus.value = 'error'
  }
}

onMounted(() => {
  void load()
  connectSse()
})
onBeforeUnmount(() => {
  es?.close()
  es = null
})

function fmt(t?: string): string {
  return t ? t.replace('T', ' ').slice(0, 19) : '—'
}
function notifyClass(s: string): string {
  const map: Record<string, string> = { SENT: 'tag-green', FAILED: 'tag-red', PENDING: 'tag-amber' }
  return map[s] ?? 'tag'
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2>变更时间线</h2>
      <span class="tag" :class="sseStatus === 'open' ? 'tag-green' : sseStatus === 'error' ? 'tag-red' : 'tag-amber'">
        SSE {{ sseStatus }}<span v-if="lastEventId" class="mono"> · {{ lastEventId }}</span>
      </span>
    </div>

    <div class="panel card">
      <div class="toolbar">
        <input v-model="subscriptionId" class="input" placeholder="订阅 ID" @keyup.enter="search" />
        <input v-model="keyword" class="input" placeholder="关键词检索" @keyup.enter="search" />
        <button class="btn" @click="search">查询</button>
        <span class="spacer" />
        <button class="btn" @click="exportCsv">导出 CSV</button>
        <button class="btn btn-sm" @click="load">刷新</button>
      </div>

      <div v-if="error" class="error-box">{{ error }}</div>
      <div v-if="loading" class="loading">加载中…</div>
      <template v-else-if="!error">
        <table v-if="rows.length" class="table">
          <thead>
            <tr>
              <th>时间</th><th>事件号</th><th>订阅 ID</th><th>类型</th>
              <th>差异摘要</th><th>通知</th><th>已读</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.eventNo">
              <td class="mono">{{ fmt(row.createdAt) }}</td>
              <td class="mono">{{ row.eventNo }}</td>
              <td class="mono">{{ row.subscriptionId }}</td>
              <td>{{ row.changeType }}</td>
              <td class="ellipsis" :title="row.diffSummary">{{ row.diffSummary }}</td>
              <td><span :class="['tag', notifyClass(row.notifyStatus)]">{{ row.notifyStatus }}</span></td>
              <td><span :class="['tag', row.readFlag ? 'tag-green' : 'tag-gray']">{{ row.readFlag ? '已读' : '未读' }}</span></td>
              <td>
                <div class="ops">
                  <button class="btn btn-sm" @click="openDetail(row)">详情</button>
                  <button v-if="!row.readFlag" class="btn btn-sm" @click="markRead(row)">标记已读</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">暂无变更事件</div>
        <div class="pager">
          <span>共 {{ total }} 条 · 第 {{ page }} 页</span>
          <button class="btn btn-sm" :disabled="page <= 1" @click="prevPage">上一页</button>
          <button class="btn btn-sm" :disabled="page * size >= total" @click="nextPage">下一页</button>
        </div>
      </template>
    </div>

    <div v-if="detailOpen" class="modal-mask" @click.self="detailOpen = false">
      <div class="modal card">
        <h3>变更详情</h3>
        <div v-if="detailError" class="error-box">{{ detailError }}</div>
        <div v-else-if="!detail" class="loading">加载中…</div>
        <template v-else>
          <div class="field"><span>事件号</span><div class="mono">{{ detail.eventNo }}</div></div>
          <div class="field"><span>变更类型</span><div>{{ detail.changeType }}</div></div>
          <div class="field"><span>差异摘要</span><div>{{ detail.diffSummary }}</div></div>
          <div class="field"><span>通知状态</span><div>{{ detail.notifyStatus }}</div></div>
        </template>
        <div class="modal-foot">
          <button class="btn" @click="detailOpen = false">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>
