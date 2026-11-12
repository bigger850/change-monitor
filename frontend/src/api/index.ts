import { api, downloadFile } from './http'
import type {
  AuthVO,
  LoginReq,
  RegisterReq,
  UserVO,
  Page,
  PageQuery,
  SubscriptionQuery,
  SubscriptionCreateReq,
  SubscriptionVO,
  EventQuery,
  ChangeEventVO,
  NotificationChannelVO,
  DashboardOverviewVO,
  DashboardTrendVO,
  TrendPoint,
  AdminSubscriptionVO,
  BlacklistVO,
  ScanTaskVO,
  AdminUserVO,
  QuotaReq
} from './types'

export const authApi = {
  login: (req: LoginReq) => api.post<AuthVO>('/auth/login', req),
  register: (req: RegisterReq) => api.post<AuthVO>('/auth/register', req),
  logout: () => api.post<void>('/auth/logout'),
  me: () => api.get<UserVO>('/auth/me'),
  refresh: (refreshToken: string) => api.post<AuthVO>('/auth/refresh', { refreshToken })
}

export const subscriptionApi = {
  list: (params: SubscriptionQuery) => api.get<Page<SubscriptionVO>>('/subscriptions', { params }),
  get: (id: string) => api.get<SubscriptionVO>(`/subscriptions/${id}`),
  create: (req: SubscriptionCreateReq) => api.post<SubscriptionVO>('/subscriptions', req),
  update: (id: string, req: Partial<SubscriptionCreateReq>) =>
    api.patch<SubscriptionVO>(`/subscriptions/${id}`, req),
  updateStatus: (id: string, status: string) =>
    api.patch<SubscriptionVO>(`/subscriptions/${id}/status`, { status }),
  remove: (id: string) => api.delete<void>(`/subscriptions/${id}`),
  scan: (id: string) => api.post<unknown>(`/subscriptions/${id}/scan`)
}

export const eventApi = {
  list: (params: EventQuery) => api.get<Page<ChangeEventVO>>('/change-events', { params }),
  get: (id: string) => api.get<ChangeEventVO>(`/change-events/${id}`),
  markRead: (id: string) => api.post<void>(`/change-events/${id}/read`),
  exportCsv: (params: { subscriptionId?: string; keyword?: string }) =>
    downloadFile(
      '/change-events/export',
      { subscriptionId: params.subscriptionId, keyword: params.keyword },
      'change-events.csv'
    )
}

export const channelApi = {
  get: () => api.get<NotificationChannelVO>('/notification-channels'),
  update: (req: NotificationChannelVO) =>
    api.put<NotificationChannelVO>('/notification-channels', req)
}

// 大盘只消费后端聚合结果，不拉原始流水自行统计
export const dashboardApi = {
  overview: () => api.get<DashboardOverviewVO>('/dashboard/overview'),
  trend: (granularity = 'day') =>
    api.get<DashboardTrendVO | TrendPoint[]>('/dashboard/trend', { params: { granularity } }),
  realtime: () => api.get<DashboardOverviewVO>('/dashboard/realtime')
}

export const adminApi = {
  subscriptions: (params: PageQuery & { status?: string }) =>
    api.get<Page<AdminSubscriptionVO>>('/admin/subscriptions', { params }),
  approve: (id: string) => api.post<void>(`/admin/subscriptions/${id}/approve`),
  reject: (id: string, reason: string) =>
    api.post<void>(`/admin/subscriptions/${id}/reject`, { reason }),
  blacklist: (params?: PageQuery) =>
    api.get<Page<BlacklistVO> | BlacklistVO[]>('/admin/blacklist', { params }),
  addBlacklist: (req: { url: string; reason?: string }) =>
    api.post<BlacklistVO>('/admin/blacklist', req),
  deleteBlacklist: (id: string) => api.delete<void>(`/admin/blacklist/${id}`),
  scanTasks: (params?: PageQuery & { status?: string }) =>
    api.get<Page<ScanTaskVO>>('/admin/scan-tasks', { params }),
  retryScanTask: (id: string) => api.post<void>(`/admin/scan-tasks/${id}/retry`),
  users: (params?: PageQuery) => api.get<Page<AdminUserVO>>('/admin/users', { params }),
  updateQuota: (id: string, req: QuotaReq) =>
    api.put<AdminUserVO>(`/admin/users/${id}/quota`, req)
}
