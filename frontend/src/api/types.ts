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

/* ---------------- 认证 ----------------
   契约对齐后端 ApiDtos：登录用 username，注册额外要求 email。 */
export interface LoginReq {
  username: string
  password: string
}
export interface RegisterReq {
  username: string
  email: string
  password: string
}
export interface AuthVO {
  accessToken: string
  refreshToken: string
  expiresIn?: number
}
/** 对齐后端 ApiDtos.UserVO */
export interface UserVO {
  id: string
  username: string
  email: string | null
  phone?: string | null
  role?: string
  status?: string
  quotaSubscription?: number
  quotaScanDaily?: number
  lastLoginAt?: string | null
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
  /** 管理端列表才填充 */
  ownerEmail?: string
}

/* ---------------- 变更事件 ---------------- */
export interface EventQuery extends PageQuery {
  subscriptionId?: string
  keyword?: string
}
export interface ChangeEventVO {
  /** 数字主键，已读/详情接口用它；eventNo 是业务事件号，仅用于展示与 SSE 续传 */
  id: string
  eventNo: string
  subscriptionId: string
  changeType: string
  diffSummary: string
  confirmed?: boolean
  notifyStatus: string
  readFlag: boolean
  createdAt: string
}

/* ---------------- 通知渠道 ----------------
   后端按「每个渠道一条配置」存储，GET 返回数组，PUT 单条更新。 */
export interface ChannelConfigVO {
  channel: string
  target?: string | null
  enabled: boolean
  freqLimitPerDay?: number | null
  lastSentAt?: string | null
}
export interface ChannelConfigReq {
  channel: string
  target?: string | null
  enabled?: boolean
  freqLimitPerDay?: number | null
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
/** 对齐后端 TrendPointVO；/dashboard/trend 直接返回数组 */
export interface TrendPoint {
  statDate: string
  scanCount: number
  changeCount: number
  successCount?: number
}
export type DashboardTrendVO = TrendPoint[]

/* ---------------- 管理端 ---------------- */
/** 管理端订阅列表返回的就是 SubscriptionVO */
export type AdminSubscriptionVO = SubscriptionVO
export interface BlacklistVO {
  id: string
  domainPattern: string
  reason?: string | null
  status?: string
  createdBy?: string | null
  createdAt?: string
}
export interface ScanTaskVO {
  id: string
  subscriptionId: string
  userId?: string
  triggerType?: string
  batchNo?: string | null
  status: string
  httpStatus?: number | null
  costMs?: number | null
  errorCode?: string | null
  /** 失败原因字段名是 errorMsg，不是 failReason */
  errorMsg?: string | null
  startAt?: string | null
  endAt?: string | null
  createdAt?: string
}
/** 管理端用户列表返回的就是 UserVO，直接复用 */
export type AdminUserVO = UserVO
export interface QuotaReq {
  quotaSubscription: number
  quotaScanDaily: number
}
