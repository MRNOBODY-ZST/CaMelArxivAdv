# 运维手册

## 健康检查

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps
curl -fsS http://localhost:8080/healthz
curl -fsS http://localhost:8080/api/v1/system/health
docker compose exec -T postgres pg_isready -U camel -d camel_arxiv
docker compose exec -T redis redis-cli ping
docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping
```

正常基线为九个服务 running/healthy，worker `RestartCount=0`。健康接口只返回状态，不暴露连接信息。

## 日志与排障

```bash
docker compose logs --since=15m backend-api
docker compose logs --since=15m mail-worker
docker compose logs --since=15m arxiv-worker
docker compose logs --since=15m postgres rabbitmq redis
```

按 Trace ID 串联 Nginx、API、任务事件和 worker 消息。禁止为了排障临时输出 Authorization、Cookie、邮箱、SMTP Secret、JWT、Source 内容或完整消息帧。Python worker 即使在 DEBUG 下也把 AMQP 协议库限制为 WARNING。

常见现象：

| 现象 | 检查 | 处理 |
|---|---|---|
| 前端 502 | `backend-api` health/logs | 先处理 Flyway/依赖连接，不循环重启数据库 |
| Worker 重启 | `docker inspect ... RestartCount` 与 worker logs | 检查入口、消息版本、RabbitMQ 凭据 |
| 任务长时间 RUNNING | `jobs.heartbeat_at`、worker heartbeat、队列积压 | 标记失联任务并按幂等键安全重试 |
| 邮件没有进入 Mailpit | `ALLOW_LIVE_SMTP`、mail-worker、Mailpit accepted 数 | 不改为真实 SMTP 作为排障手段 |
| 数据库迁移失败 | `flyway_schema_history` 与 API logs | 停止扩容，修正新迁移；不要编辑已发布迁移 |

## 认证运维

首次部署通过运行时 Secret 提供 `INITIAL_ADMIN_USERNAME`、`INITIAL_ADMIN_EMAIL`、`INITIAL_ADMIN_DISPLAY_NAME` 与符合策略的 `INITIAL_ADMIN_PASSWORD`。启动日志只应出现用户名，绝不能出现临时密码。管理员完成首次改密后，从部署 Secret 中移除密码值；引导逻辑是幂等的，不会覆盖现有账号。

认证相关独立密钥至少 32 字节并使用 Base64：`JWT_SIGNING_KEY_BASE64` 与 `AUTH_FINGERPRINT_HMAC_KEY_BASE64` 不得复用。生产环境保持 `AUTH_COOKIE_SECURE=true` 和 `AUTH_COOKIE_SAME_SITE=Strict`，只有 localhost 开发覆盖可以关闭 Secure。

账号恢复顺序：

1. 由另一名 `SUPER_ADMIN` 在用户管理中启用账号或重置临时密码。
2. 让用户使用临时密码登录并立即改密；重置动作已经撤销其全部 refresh 会话并增加 `tokenVersion`。
3. 对登录限流不要直接删除审计记录。确认失败来源后等待默认 15 分钟窗口结束；若必须人工处置，只能按已核实的主体/IP 哈希定点处理 `login_attempts`，并保留变更审计。
4. 普通 `ADMIN` 不能创建、编辑、重置或启停 `SUPER_ADMIN` 账号；最后一个有效 `SUPER_ADMIN` 还受事务保护，不能通过 UI/API 停用。应在投产时建立至少两个受控管理员账号并分别保管凭据。

refresh 重放通常表现为第一次旧 Cookie 请求 401，随后同 family 的最新 Cookie 也返回 401。此行为是主动撤销而非服务异常；按 API Trace ID 检查 `AUTH_REFRESH_REPLAY` 拒绝审计，并确认客户端是否存在并发或复制 Cookie，随后要求重新登录。禁止记录或粘贴 Cookie/JWT 原值用于排障。

## 数据库检查

```bash
docker compose exec -T postgres psql -U camel -d camel_arxiv \
  -c 'select installed_rank, version, description, success from flyway_schema_history order by installed_rank;'
```

备份示例（目标路径应位于受保护且容量充足的备份卷）：

```bash
docker compose exec -T postgres pg_dump -U camel -d camel_arxiv -Fc > camel_arxiv.dump
```

恢复必须在隔离环境定期演练。MinIO、RabbitMQ definitions 和运行平台 Secret 需要独立备份；Redis 不作为唯一事实源。

## 队列与幂等

- 监控 ready/unacked 数、最老消息年龄、重试和 DLQ。
- 只在根因处理后重放 DLQ；保留原 `messageId`/`idempotencyKey`。
- Outbox 发布后设置 `published_at`；消费者完成业务事务后记录 `processed_messages` 再 ACK。
- 不手工删除未知积压，也不直接把失败任务改为成功。

## 邮件安全操作

开发/CI 只使用 Mailpit。后续启用真实 SMTP 前必须确认活动已审批、Recipient 快照已冻结、抑制/退订已应用、频率上限有效、域名认证完成。紧急停止应暂停 Campaign 消费者并保留队列，不删除 Recipient/Attempt 审计记录。

## 保留与隐私

`data_retention_policies` 是保留任务的事实配置。原始追踪事件、IP/User-Agent 派生数据、审计和导出文件应按不同期限清理。打开事件只能解释为估算信号；报告中明确 SMTP accepted 不等同 delivered。

## 阶段验收基线

2026-08-05 实测：4 个 Flyway 迁移成功、50 张 public 表、9 个容器健康；Nginx/API/Mailpit/MinIO 宿主入口可达；后端、Python、Vue 全量质量门通过；桌面 1280×720 与移动 390×844 无横向溢出、零 console warning/error，移动抽屉关闭后焦点回到触发按钮。

Phase 2 同日实测：Flyway 更新到 V5；初始管理员强制改密；改密后旧 access/refresh 均为 401；refresh 单次轮换成功，旧值重放后整族失效且写入 `AUTH_REFRESH_REPLAY`；权限目录为 26 项；普通 `ADMIN` 的四类 `SUPER_ADMIN` 接管请求均为 403 并落审计，`VIEWER` 管理端请求为带 Trace ID 的 403；logout 后 refresh 为 401。用户/角色/审计三页通过桌面与 390×844 浏览器检查，页面无全局横向溢出且控制台零 warning/error。
