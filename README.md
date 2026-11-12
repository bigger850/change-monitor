# change-monitor · 网页变更订阅与实时通知平台

状态：**已上线，HTTPS 可访问** → **https://watch.bigger.ink/**（IP 入口 `http://47.106.66.24/` 亦可用）。P0 收尾三项（SSE 断线补推 / 令牌桶限流 / Prometheus 指标）已上线实测。部署详情见 `04-服务器部署记录.md`。

> 阿里云备案拦截只作用于 **80 端口**，443 通畅，所以证书走 **TLS-ALPN-01** 签发（acme.sh，到期 2027-02-10，cron 自动续期）。`http://watch.bigger.ink/` 仍会被 403 拦截，用 HTTPS 访问即可。主域 `bigger.ink` 由同机 pantry 站点占用，本项目不与之冲突。

技术栈：Java 21 + Spring Boot 3.2.5 + MyBatis-Plus + MySQL 8 + Redis 7 / Vue3 + TS + Vite + ECharts / Nginx + Docker Compose。

## 目录

| 路径 | 内容 |
|---|---|
| `00-项目启动.md` | 决策基线、边界、进度 |
| `01-数据库设计.md` | 11 张表、状态机、幂等、数据流 |
| `02-接口契约.md` | 统一约定、错误码、接口清单、DTO |
| `03-部署与备案清单.md` | DNS、备案前置条件、上线检查清单 |
| `backend/` | Spring Boot 后端（52 个 Java 类 + schema.sql） |
| `frontend/` | Vue3 SPA（6 个页面 + axios/SSE 封装） |
| `deploy/` | Dockerfile、compose、nginx 配置、环境变量样例 |

## 环境要求

Java 21 / Maven 3.9+ / Node 22 / npm 10 / Docker 29（本机均已验证可用）。

## 启动方式

### A. 本地联调（最轻）

```bash
# 1. 只起中间件
cd deploy && docker compose up -d mysql redis

# 2. 后端（dev profile：localhost MySQL/Redis，schema 由 initdb 自动导入）
cd ../backend && mvn spring-boot:run

# 3. 前端
cd ../frontend && npm install && npm run dev
```

打开 `http://localhost:5173`。首次启动自动创建管理员 `admin`，密码取 `WATCH_ADMIN_PASSWORD`，未设置时为 `admin123`（**仅限本地**）。
Swagger：`http://localhost:8080/swagger-ui.html`。

### B. 一键 Docker（推荐，含前端与 Nginx）

```bash
cd frontend && npm install && npm run build     # 产出 dist/
cd ../backend && mvn -q -DskipTests package     # 产出 target/*.jar
cd ../deploy && cp .env.example .env             # 必须改掉三个占位密码
docker compose up -d --build
```

访问 `http://localhost`（或备案前用 `http://47.106.66.24`）。健康检查：`/api/v1/health`。

> `WATCH_JWT_SECRET` 与 `WATCH_ADMIN_PASSWORD` 在 compose 里是必填变量，未设置会直接报错拒绝启动。`.env` 不要提交版本库。

### C. 生产部署（备案通过后）

按 `03-部署与备案清单.md`：DNS 指向云主机 → certbot 签证书 → 用 `nginx-ssl.conf.example` 替换 `nginx.conf` → 走完上线检查清单。证书签发前保持 HTTP 版配置。

## 接口前缀（联调必读）

| 环节 | 路径 |
|---|---|
| 前端 baseURL | `/api/v1`（相对路径，同源） |
| dev proxy / Nginx | `rewrite ^/api/v1/(.*)$ /$1`，**剥离前缀** |
| 后端 Controller | `/subscriptions` 等，**不带 `/v1`** |

即 `/api/v1/subscriptions` → `/subscriptions`。`api.bigger.ink` 仅作直连调试入口，前端不依赖它，因此无跨域问题。
SSE 路径 `/api/v1/stream/events?token=<jwt>`——EventSource 不能带请求头，后端 `JwtAuthFilter` 只识别 `?token=`。

## 已实现（P0 最小闭环）

- 注册 / 登录 / 刷新 / JWT 鉴权 / ADMIN 角色隔离 / BCrypt
- 订阅 CRUD、启停、手动扫描（`Idempotency-Key` + Redis SETNX 去重、配额、黑名单）
- 定时调度（`next_scan_at` + Redis 分布式锁 + 同域名并发 1 / 间隔 ≥30s）
- Jsoup 抓取（超时、重试、robots 基本校验）、SHA-256 指纹、二次确认、变更事件
- 站内通知 + SSE 推送 + 邮件（SMTP 未配置则自动降级为记日志）
- 变更时间线 / 已读 / CSV 导出 / 通知渠道配置
- 管理端：审核、黑名单、扫描任务与重试、配额、sys_config、审计日志
- 监控大盘聚合指标、健康检查、OpenAPI 文档
- 统一响应体、错误码、全局异常、`X-Trace-Id` 全链路

## 已知裁剪（如实记录，别当已完成）

| 项 | 状态 |
|---|---|
| SSE `Last-Event-ID` 断线补推 | **已完成**（2026-11-12）：后端按 event_no 补推最多 100 条，前端同时监听 `change` 命名事件 |
| 接口级令牌桶限流（429） | **已完成**：Redis + Lua 令牌桶，300 并发实测 49 个 429，`watch.rate-limit.*` 可配 |
| `/actuator/prometheus` | **已完成**：5 个自定义指标；公网经 nginx 拦截返回 404，仅内网可达 |
| `diff_summary` | 只存指纹前缀、长度变化等元数据，**不存网页原文片段**（合规红线）；契约里「¥299→¥269」是示例文案，非实际产出 |
| 订阅审核 | 新建走 URL 合法性 + 黑名单校验后默认 ACTIVE，`approve/reject` 接口仍在 |
| 其余 5 张表 | 用 JdbcTemplate 访问，未建实体 / Mapper |
| P1/P2 组件 | 未引 Kafka / ES / XXL-Job / Sentinel / 分库分表，按设计留到后续 |
| `schema.sql` | 应用内 `spring.sql.init.mode=never`，compose 通过 initdb 挂载初始化（仅空数据卷生效） |

## 安全与合规红线

- 上线前必须改：`MYSQL_ROOT_PASSWORD`、`WATCH_JWT_SECRET`（≥32 字节）、`WATCH_ADMIN_PASSWORD`；默认 `admin/admin123` 绝不能带到公网
- 安全组只放行 22（限源 IP）/ 80 / 443；3306、6379、8080 不公网暴露
- `page_snapshot` 不得新增原文字段；抓取原文只在内存算指纹，用完即弃
- robots.txt、单域名限速、URL 白/黑名单、隐私政策、账号注销与投诉入口必须齐备
- 备案未通过前不要把 `bigger.ink` 解析到国内 IP

## 上线验收

公开 URL 可访问 → `/api/v1/health` 正常 → 定时任务连续写入 `scan_task` → 二次确认不误报 → 幂等验证通过 → 大盘有真实曲线 → 上线检查清单全过 → 基础压测基线。

## 下一步

1. **唯一阻塞项**：在阿里云备案控制台把 `watch.bigger.ink` 加入已备案网站域名。完成后我立即签证书并切 443（HTTPS 配置已备好）
2. 真实目标订阅若干，跑出连续真实数据
3. P1：服务拆分 + Nacos + XXL-Job 分片 + Kafka + Sentinel + ES
