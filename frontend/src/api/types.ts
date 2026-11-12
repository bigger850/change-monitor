// 依据 02-接口契约.md 定义的请求/响应类型。
// 契约未逐字段给出的 VO，按语义约定最小结构，实际永远以后端响应为准。

/** 统一响应体 { code, message, data, traceId } */
export interface R<T> {
  code: number
  message: string
  data: T
  traceId: string
}

/** 统一分页结构，全部由后端处理 */
export interface Page<T> {
  total: number
  page: number
  size: number
  list: T[]
}

export interface PageQuery {
  page: number
  size: number
  sort?: string
}

/* ---------------- 认证 ---------------- */
export interface LoginReq {
  email: string
  password: string
}
export interface RegisterReq {
  email: string
  password: string
}
export interface AuthVO {
  accessToken: string
  refreshToken: string
  expiresIn?: number
}
export interface UserVO {
  id: string
  email: string
  role?: string
  subscriptionQuota?: number
  dailyScanQuota?: number
}

/* ---------------- 订阅 ---------------- */
// 状态字段为后端事实来源，前端只做展示与操作意图采集
export type SubscriptionStatus = 'PENDING' | 'ACTIVE' | 'PAUSED' | 'ABNORMAL' | 'TERMINATED'
export interface SubscriptionQuery extends PageQuery {
  status?: string
}
export interface SubscriptionCreateReq {
  name: string
  url: string
  selectorType: string
  selectorExpr: string
  keyword?: string
  checkIntervalSec: number
  notifyChannels: string[]
}
export interface SubscriptionVO {
  id: string
  name: string
  url: string
  status: string
  checkIntervalSec: number
  lastScanAt?: string
  lastChangeAt?: string
  failCount: number
  nextScanAt?: string
  // 详情接口返回的规则字段（编辑回填用）
  selectorType?: string
  selectorExpr?: string
  keyword?: string
  notifyChannels?: string[]
}

/* ---------------- 变更事件 ---------------- */
export interface EventQuery extends PageQuery {
  subscriptionId?: string
  keyword?: string
}
export interface ChangeEventVO {
  eventNo: string
  subscriptionId: string
  changeType: string
  diffSummary: string
  notifyStatus: string
  readFlag: boolean
  createdAt: string
}

/* ---------------- 通知渠道 ---------------- */
export interface ChannelItem {
  type: string
  enabled: boolean
  target?: string
}
export interface FrequencyCap {
  maxPerHour: number
  maxPerDay: number
}
export interface NotificationChannelVO {
  channels: ChannelItem[]
  frequencyCap: FrequencyCap
}

/* ---------------- 数据大盘（只消费聚合结果） ---------------- */
export interface DashboardOverviewVO {
  subscriptionTotal: number
  scanToday: number
  changeToday: number
  successRate: number
  p95CostMs: number
  queueBacklog: number
}
export interface TrendPoint {
  bucket: string
  scanCount: number
  changeCount: number
}
export interface DashboardTrendVO {
  points: TrendPoint[]
}

/* ---------------- 管理端 ---------------- */
export interface AdminSubscriptionVO extends SubscriptionVO {
  ownerEmail?: string
  reviewStatus?: string
}
export interface BlacklistVO {
  id: string
  url: string
  reason?: string
  createdAt?: string
}
export interface ScanTaskVO {
  id: string
  subscriptionId: string
  status: string
  failReason?: string
  createdAt?: string
}
export interface AdminUserVO {
  id: string
  email: string
  role?: string
  subscriptionQuota: number
  dailyScanQuota: number
}
export interface QuotaReq {
  subscriptionQuota?: number
  dailyScanQuota?: number
}
