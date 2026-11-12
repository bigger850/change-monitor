<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { adminApi } from '../api'
import { notify } from '../api/http'
import type { AdminSubscriptionVO, AdminUserVO, BlacklistVO, ScanTaskVO } from '../api/types'

type Tab = 'subscriptions' | 'blacklist' | 'tasks' | 'users'
const tabs = [
  { key: 'subscriptions', label: '订阅审核' },
  { key: 'blacklist', label: '黑名单' },
  { key: 'tasks', label: '扫描任务' },
  { key: 'users', label: '用户配额' }
] as const

const active = ref<Tab>('subscriptions')
const loading = ref(false)
const error = ref('')

const subs = ref<AdminSubscriptionVO[]>([])
const blacklist = ref<BlacklistVO[]>([])
const tasks = ref<ScanTaskVO[]>([])
const users = ref<AdminUserVO[]>([])

const subStatus = ref('PENDING')
const taskStatus = ref('')
const rejectId = ref('')
const rejectReason = ref('')
const blUrl = ref('')
const blReason = ref('')
const quotaId = ref('')
const qSub = ref(0)
const qDaily = ref(0)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    if (active.value === 'subscriptions') {
      const d = await adminApi.subscriptions({ page: 1, size: 100, status: subStatus.value || undefined })
      subs.value = d?.list ?? []
    } else if (active.value === 'blacklist') {
      blacklist.value = (await adminApi.blacklist()) ?? []
    } else if (active.value === 'tasks') {
      const d = await adminApi.scanTasks({ page: 1, size: 100, status: taskStatus.value || undefined })
      tasks.value = d?.list ?? []
    } else {
      const d = await adminApi.users({ page: 1, size: 100 })
      users.value = d?.list ?? []
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载管理数据失败'
  } finally {
    loading.value = false
  }
}
onMounted(load)
watch(active, load)

/** 操作成功后重新拉取，绝不本地改状态 / 算配额 */
async function act(fn: () => Promise<unknown>, ok: string): Promise<void> {
  try {
    await fn()
    notify(ok, 'success')
    await load()
  } catch (e) {
    notify(e instanceof Error ? e.message : '操作失败')
  }
}

function approve(row: AdminSubscriptionVO): void {
  void act(() => adminApi.approve(row.id), '审核已通过')
}
function confirmReject(row: AdminSubscriptionVO): void {
  if (!rejectReason.value.trim()) {
    notify('请填写驳回原因')
    return
  }
  const reason = rejectReason.value.trim()
  void act(() => adminApi.reject(row.id, reason), '已驳回')
  rejectId.value = ''
  rejectReason.value = ''
}
function addBlacklist(): void {
  if (!blUrl.value.trim()) {
    notify('请填写域名模式')
    return
  }
  // 契约：后端 BlacklistReq 字段为 domainPattern
  const domainPattern = blUrl.value.trim()
  const reason = blReason.value.trim() || undefined
  void act(() => adminApi.addBlacklist({ domainPattern, reason }), '已加入黑名单')
  blUrl.value = ''
  blReason.value = ''
}
function removeBlacklist(row: BlacklistVO): void {
  void act(() => adminApi.deleteBlacklist(row.id), '已移除')
}
function retry(row: ScanTaskVO): void {
  void act(() => adminApi.retryScanTask(row.id), '已重投任务')
}
function openQuota(row: AdminUserVO): void {
  quotaId.value = row.id
  qSub.value = row.quotaSubscription ?? 0
  qDaily.value = row.quotaScanDaily ?? 0
}
function saveQuota(row: AdminUserVO): void {
  // 契约：后端 QuotaReq 字段为 quotaSubscription / quotaScanDaily
  const quotaSubscription = Number(qSub.value) || 0
  const quotaScanDaily = Number(qDaily.value) || 0
  void act(() => adminApi.updateQuota(row.id, { quotaSubscription, quotaScanDaily }), '配额已更新')
  quotaId.value = ''
}
function fmt(t?: string): string {
  return t ? t.replace('T', ' ').slice(0, 19) : '—'
}
</script>

<template>
  <div>
    <div class="page-head"><h2>管理端</h2></div>

    <div class="tabs">
      <button
        v-for="t in tabs"
        :key="t.key"
        :class="{ active: active === t.key }"
        @click="active = t.key"
      >{{ t.label }}</button>
    </div>

    <div class="panel card">
      <div v-if="error" class="error-box">{{ error }}</div>
      <div v-if="loading" class="loading">加载中…</div>

      <template v-else-if="!error">
        <!-- 订阅审核 -->
        <template v-if="active === 'subscriptions'">
          <div class="toolbar">
            <select v-model="subStatus" class="select">
              <option value="">全部</option>
              <option value="PENDING">待审核</option>
              <option value="ABNORMAL">异常</option>
              <option value="ACTIVE">运行中</option>
            </select>
            <button class="btn btn-sm" @click="load">查询</button>
          </div>
          <table v-if="subs.length" class="table">
            <thead><tr><th>名称</th><th>URL</th><th>状态</th><th>归属</th><th>最近扫描</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in subs" :key="row.id">
                <td>{{ row.name }}</td>
                <td class="ellipsis mono" :title="row.url">{{ row.url }}</td>
                <td><span class="tag tag-blue">{{ row.status }}</span></td>
                <td>{{ row.ownerEmail || '—' }}</td>
                <td class="mono">{{ fmt(row.lastScanAt) }}</td>
                <td>
                  <div class="ops">
                    <button class="btn btn-sm" @click="approve(row)">通过</button>
                    <template v-if="rejectId === row.id">
                      <input v-model="rejectReason" class="input" style="width: 150px" placeholder="驳回原因" />
                      <button class="btn btn-sm btn-danger" @click="confirmReject(row)">确认</button>
                      <button class="btn btn-sm" @click="rejectId = ''">取消</button>
                    </template>
                    <button v-else class="btn btn-sm btn-danger" @click="rejectId = row.id; rejectReason = ''">驳回</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty">暂无订阅</div>
        </template>

        <!-- 黑名单 -->
        <template v-else-if="active === 'blacklist'">
          <div class="inline-form">
            <input v-model="blUrl" class="input" style="width: 280px" placeholder="域名或模式，如 example.com / *.gov.cn" />
            <input v-model="blReason" class="input" style="width: 200px" placeholder="原因（可选）" />
            <button class="btn btn-primary btn-sm" @click="addBlacklist">+ 加入黑名单</button>
          </div>
          <table v-if="blacklist.length" class="table">
            <thead><tr><th>ID</th><th>域名模式</th><th>原因</th><th>时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in blacklist" :key="row.id">
                <td class="mono">{{ row.id }}</td>
                <td class="ellipsis mono" :title="row.domainPattern">{{ row.domainPattern }}</td>
                <td>{{ row.reason || '—' }}</td>
                <td class="mono">{{ fmt(row.createdAt) }}</td>
                <td><button class="btn btn-sm btn-danger" @click="removeBlacklist(row)">移除</button></td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty">暂无黑名单记录</div>
        </template>

        <!-- 扫描任务 -->
        <template v-else-if="active === 'tasks'">
          <div class="toolbar">
            <select v-model="taskStatus" class="select">
              <option value="">全部状态</option>
              <option value="FAILED">失败</option>
              <option value="SUCCESS">成功</option>
              <option value="RUNNING">运行中</option>
            </select>
            <button class="btn btn-sm" @click="load">查询</button>
          </div>
          <table v-if="tasks.length" class="table">
            <thead><tr><th>任务 ID</th><th>订阅 ID</th><th>状态</th><th>失败原因</th><th>时间</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in tasks" :key="row.id">
                <td class="mono">{{ row.id }}</td>
                <td class="mono">{{ row.subscriptionId }}</td>
                <td><span :class="['tag', row.status === 'FAILED' ? 'tag-red' : 'tag-green']">{{ row.status }}</span></td>
                <td class="ellipsis" :title="row.errorMsg || ''">{{ row.errorMsg || '—' }}</td>
                <td class="mono">{{ fmt(row.createdAt) }}</td>
                <td><button class="btn btn-sm" @click="retry(row)">重试</button></td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty">暂无扫描任务</div>
        </template>

        <!-- 用户配额 -->
        <template v-else>
          <table v-if="users.length" class="table">
            <thead><tr><th>ID</th><th>用户名</th><th>邮箱</th><th>角色</th><th>订阅数配额</th><th>日扫描配额</th><th>操作</th></tr></thead>
            <tbody>
              <tr v-for="row in users" :key="row.id">
                <td class="mono">{{ row.id }}</td>
                <td>{{ row.username }}</td>
                <td>{{ row.email || '—' }}</td>
                <td>{{ row.role || '—' }}</td>
                <td>{{ row.quotaSubscription ?? '—' }}</td>
                <td>{{ row.quotaScanDaily ?? '—' }}</td>
                <td>
                  <div class="ops">
                    <template v-if="quotaId === row.id">
                      <input v-model.number="qSub" class="input" style="width: 90px" type="number" min="0" />
                      <input v-model.number="qDaily" class="input" style="width: 90px" type="number" min="0" />
                      <button class="btn btn-sm btn-primary" @click="saveQuota(row)">保存</button>
                      <button class="btn btn-sm" @click="quotaId = ''">取消</button>
                    </template>
                    <button v-else class="btn btn-sm" @click="openQuota(row)">调整</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-else class="empty">暂无用户</div>
        </template>
      </template>
    </div>
  </div>
</template>
