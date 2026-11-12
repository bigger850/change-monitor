<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { subscriptionApi } from '../api'
import { notify } from '../api/http'
import type { SubscriptionCreateReq, SubscriptionVO } from '../api/types'

const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'PENDING', label: '待审核' },
  { value: 'ACTIVE', label: '运行中' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'ABNORMAL', label: '异常' },
  { value: 'TERMINATED', label: '已终止' }
]
const SELECTOR_TYPES = ['CSS', 'XPATH']
const CHANNEL_TYPES = [
  { value: 'SITE', label: '站内' },
  { value: 'EMAIL', label: '邮件' }
]

interface FormModel {
  name: string
  url: string
  selectorType: string
  selectorExpr: string
  keyword: string
  checkIntervalSec: number
  notifyChannels: string[]
}
function emptyForm(): FormModel {
  return {
    name: '',
    url: '',
    selectorType: 'CSS',
    selectorExpr: '',
    keyword: '',
    checkIntervalSec: 1800,
    notifyChannels: ['SITE']
  }
}

const rows = ref<SubscriptionVO[]>([])
const loading = ref(false)
const error = ref('')
const statusFilter = ref('')
const page = ref(1)
const size = 20
const total = ref(0)
const busyId = ref('')

const showModal = ref(false)
const editingId = ref<string | null>(null)
const detailLoading = ref(false)
const saving = ref(false)
const formError = ref('')
const form = reactive<FormModel>(emptyForm())

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await subscriptionApi.list({
      page: page.value,
      size,
      status: statusFilter.value || undefined
    })
    rows.value = data.list ?? []
    total.value = data.total ?? 0
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载订阅失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(statusFilter, () => {
  page.value = 1
  void load()
})

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

function openCreate(): void {
  editingId.value = null
  Object.assign(form, emptyForm())
  formError.value = ''
  showModal.value = true
}

async function openEdit(row: SubscriptionVO): Promise<void> {
  editingId.value = row.id
  Object.assign(form, emptyForm())
  formError.value = ''
  showModal.value = true
  detailLoading.value = true
  try {
    // 规则回填以后端详情为准
    const d = await subscriptionApi.get(row.id)
    form.name = d.name ?? ''
    form.url = d.url ?? ''
    form.selectorType = d.selectorType ?? 'CSS'
    form.selectorExpr = d.selectorExpr ?? ''
    form.keyword = d.keyword ?? ''
    form.checkIntervalSec = d.checkIntervalSec ?? 1800
    form.notifyChannels = [...(d.notifyChannels ?? ['SITE'])]
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '加载订阅详情失败'
  } finally {
    detailLoading.value = false
  }
}

async function save(): Promise<void> {
  formError.value = ''
  if (!form.name.trim() || !form.url.trim() || !form.selectorExpr.trim()) {
    formError.value = '请填写名称、URL 与选择器表达式'
    return
  }
  saving.value = true
  const req: SubscriptionCreateReq = {
    name: form.name.trim(),
    url: form.url.trim(),
    selectorType: form.selectorType,
    selectorExpr: form.selectorExpr.trim(),
    keyword: form.keyword.trim() || undefined,
    checkIntervalSec: Number(form.checkIntervalSec) || 1800,
    notifyChannels: [...form.notifyChannels]
  }
  try {
    if (editingId.value) await subscriptionApi.update(editingId.value, req)
    else await subscriptionApi.create(req)
    notify(editingId.value ? '已保存' : '已创建', 'success')
    showModal.value = false
    await load() // 状态以后端响应为准
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

/** 统一操作：成功后重新拉取列表，绝不本地改状态 */
async function runAction(
  row: SubscriptionVO,
  fn: () => Promise<unknown>,
  okMsg: string
): Promise<void> {
  busyId.value = row.id
  try {
    await fn()
    notify(okMsg, 'success')
    await load()
  } catch (e) {
    notify(e instanceof Error ? e.message : '操作失败')
  } finally {
    busyId.value = ''
  }
}

function toggleTarget(s: string): string | null {
  if (s === 'ACTIVE') return 'PAUSED'
  if (s === 'PAUSED' || s === 'ABNORMAL') return 'ACTIVE'
  return null
}
function toggleLabel(s: string): string {
  if (s === 'ACTIVE') return '暂停'
  if (s === 'PAUSED') return '启用'
  if (s === 'ABNORMAL') return '恢复'
  return ''
}
function statusClass(s: string): string {
  const map: Record<string, string> = {
    ACTIVE: 'tag-green',
    PAUSED: 'tag-gray',
    ABNORMAL: 'tag-red',
    PENDING: 'tag-amber',
    TERMINATED: 'tag-gray'
  }
  return map[s] ?? 'tag'
}
function fmt(t?: string): string {
  return t ? t.replace('T', ' ').slice(0, 19) : '—'
}
function remove(row: SubscriptionVO): void {
  if (!window.confirm(`确认删除订阅「${row.name}」？`)) return
  void runAction(row, () => subscriptionApi.remove(row.id), '已删除')
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2>我的订阅</h2>
      <button class="btn btn-primary" @click="openCreate">+ 新建订阅</button>
    </div>

    <div class="panel card">
      <div class="toolbar">
        <select v-model="statusFilter" class="select">
          <option v-for="o in STATUS_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>
        <span class="spacer" />
        <button class="btn btn-sm" @click="load">刷新</button>
      </div>

      <div v-if="error" class="error-box">{{ error }}</div>
      <div v-if="loading" class="loading">加载中…</div>
      <template v-else-if="!error">
        <table v-if="rows.length" class="table">
          <thead>
            <tr>
              <th>名称</th><th>URL</th><th>状态</th><th>间隔(s)</th>
              <th>失败次数</th><th>最近扫描</th><th>下次扫描</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ row.name }}</td>
              <td class="ellipsis mono" :title="row.url">{{ row.url }}</td>
              <td><span :class="['tag', statusClass(row.status)]">{{ row.status }}</span></td>
              <td>{{ row.checkIntervalSec }}</td>
              <td>{{ row.failCount }}</td>
              <td class="mono">{{ fmt(row.lastScanAt) }}</td>
              <td class="mono">{{ fmt(row.nextScanAt) }}</td>
              <td>
                <div class="ops">
                  <button class="btn btn-sm" :disabled="busyId === row.id" @click="openEdit(row)">编辑</button>
                  <button
                    v-if="toggleTarget(row.status)"
                    class="btn btn-sm"
                    :disabled="busyId === row.id"
                    @click="runAction(row, () => subscriptionApi.updateStatus(row.id, toggleTarget(row.status)!), '已更新')"
                  >{{ toggleLabel(row.status) }}</button>
                  <button
                    class="btn btn-sm"
                    :disabled="busyId === row.id"
                    @click="runAction(row, () => subscriptionApi.scan(row.id), '已触发扫描')"
                  >扫描</button>
                  <button class="btn btn-sm btn-danger" :disabled="busyId === row.id" @click="remove(row)">删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else class="empty">暂无订阅数据</div>
        <div class="pager">
          <span>共 {{ total }} 条 · 第 {{ page }} 页</span>
          <button class="btn btn-sm" :disabled="page <= 1" @click="prevPage">上一页</button>
          <button class="btn btn-sm" :disabled="page * size >= total" @click="nextPage">下一页</button>
        </div>
      </template>
    </div>

    <div v-if="showModal" class="modal-mask" @click.self="showModal = false">
      <div class="modal card">
        <h3>{{ editingId ? '编辑订阅' : '新建订阅' }}</h3>
        <div v-if="detailLoading" class="loading">加载详情…</div>
        <form v-else @submit.prevent="save">
          <div class="form-grid">
            <label class="field"><span>名称</span><input v-model="form.name" class="input" placeholder="如：竞品价格页" /></label>
            <label class="field"><span>检查间隔（秒）</span><input v-model.number="form.checkIntervalSec" class="input" type="number" min="30" /></label>
            <label class="field full"><span>URL</span><input v-model="form.url" class="input" placeholder="https://example.com/price" /></label>
            <label class="field"><span>选择器类型</span>
              <select v-model="form.selectorType" class="select">
                <option v-for="t in SELECTOR_TYPES" :key="t" :value="t">{{ t }}</option>
              </select>
            </label>
            <label class="field"><span>选择器表达式</span><input v-model="form.selectorExpr" class="input" placeholder="#price" /></label>
            <label class="field full"><span>关键词（可选）</span><input v-model="form.keyword" class="input" placeholder="降价" /></label>
            <div class="field full">
              <span>通知渠道</span>
              <div class="check-row">
                <label v-for="c in CHANNEL_TYPES" :key="c.value">
                  <input v-model="form.notifyChannels" type="checkbox" :value="c.value" /> {{ c.label }}
                </label>
              </div>
            </div>
          </div>
          <div v-if="formError" class="error-text">{{ formError }}</div>
          <div class="modal-foot">
            <button class="btn" type="button" @click="showModal = false">取消</button>
            <button class="btn btn-primary" type="submit" :disabled="saving || detailLoading">
              {{ saving ? '保存中…' : '保存' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </div>
</template>
