<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
import { dashboardApi } from '../api'
import type { DashboardOverviewVO, TrendPoint } from '../api/types'

const loading = ref(false)
const error = ref('')
const overview = ref<DashboardOverviewVO | null>(null)
const chartEl = ref<HTMLDivElement | null>(null)
let chart: ReturnType<typeof echarts.init> | null = null
let timer: number | undefined

function num(v?: number): string {
  return v == null ? '—' : v.toLocaleString()
}
function pct(v?: number): string {
  return v == null ? '—' : `${(v * 100).toFixed(1)}%`
}
function cost(v?: number): string {
  return v == null ? '—' : `${v} ms`
}

function renderChart(points: TrendPoint[]): void {
  if (!chartEl.value) return
  if (!chart) chart = echarts.init(chartEl.value)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['扫描量', '变更数'] },
    grid: { left: 56, right: 20, top: 40, bottom: 36 },
    // 后端 TrendPointVO 的日期字段是 statDate
    xAxis: { type: 'category', boundaryGap: false, data: points.map((p) => p.statDate) },
    yAxis: { type: 'value' },
    series: [
      { name: '扫描量', type: 'line', smooth: true, data: points.map((p) => p.scanCount) },
      { name: '变更数', type: 'line', smooth: true, data: points.map((p) => p.changeCount) }
    ]
  })
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    // 大盘只消费 /dashboard/* 聚合结果，不拉原始流水自行统计
    const [ov, trend] = await Promise.all([dashboardApi.overview(), dashboardApi.trend('day')])
    overview.value = ov
    renderChart(trend ?? [])
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载大盘失败'
  } finally {
    loading.value = false
  }
}

function onResize(): void {
  chart?.resize()
}

onMounted(() => {
  void load()
  window.addEventListener('resize', onResize)
  timer = window.setInterval(() => void load(), 30000)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (timer) window.clearInterval(timer)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div>
    <div class="page-head">
      <h2>监控大盘</h2>
      <div class="toolbar" style="margin: 0">
        <span class="muted">每 30s 刷新聚合指标</span>
        <button class="btn btn-sm" @click="load">立即刷新</button>
      </div>
    </div>

    <div v-if="error" class="error-box">{{ error }}</div>

    <div class="stat-grid">
      <div class="stat card"><div class="label">订阅总数</div><div class="value">{{ num(overview?.subscriptionTotal) }}</div></div>
      <div class="stat card"><div class="label">今日扫描量</div><div class="value">{{ num(overview?.scanToday) }}</div></div>
      <div class="stat card"><div class="label">今日变更数</div><div class="value">{{ num(overview?.changeToday) }}</div></div>
      <div class="stat card"><div class="label">成功率</div><div class="value">{{ pct(overview?.successRate) }}</div></div>
      <div class="stat card"><div class="label">P95 耗时</div><div class="value">{{ cost(overview?.p95CostMs) }}</div></div>
      <div class="stat card"><div class="label">队列堆积</div><div class="value">{{ num(overview?.queueBacklog) }}</div></div>
    </div>

    <div class="panel card">
      <h3 style="font-size: 14px; margin-bottom: 12px">扫描 / 变更趋势</h3>
      <div v-if="loading && !overview" class="loading">加载中…</div>
      <div ref="chartEl" class="chart"></div>
    </div>
  </div>
</template>
