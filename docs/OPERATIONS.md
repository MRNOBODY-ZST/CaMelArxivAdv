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

## Phase 1 验收基线

2026-08-05 实测：4 个 Flyway 迁移成功、50 张 public 表、9 个容器健康；Nginx/API/Mailpit/MinIO 宿主入口可达；后端、Python、Vue 全量质量门通过；桌面 1280×720 与移动 390×844 无横向溢出、零 console warning/error，移动抽屉关闭后焦点回到触发按钮。
