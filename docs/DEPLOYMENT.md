# 部署指南

## 配置准备

```bash
cp .env.example .env
```

生产部署必须替换数据库与 MinIO 密码，并为 `APP_ENCRYPTION_KEY_BASE64`、`APP_EMAIL_HMAC_KEY_BASE64`、`TEMPLATE_ASSET_SIGNING_KEY_BASE64`、`JWT_SIGNING_KEY_BASE64`、`AUTH_FINGERPRINT_HMAC_KEY_BASE64` 分别生成独立 32 字节随机值；后续启用追踪时再独立生成 `TRACKING_SIGNING_KEY_BASE64`。Kafka 生产集群还必须独立配置 TLS/SASL 与 ACL。`docker-compose.yml` 对当前五把密钥使用必填插值，缺少任一值时配置渲染会直接失败。不要提交 `.env`，也不要复用随机输出。

首次部署通过运行平台 Secret 注入四个 `INITIAL_ADMIN_*` 值。引导账号创建后必须完成首次改密，并立即从 Secret 中移除 `INITIAL_ADMIN_PASSWORD`；引导逻辑不会覆盖已有账号。公网 SMTP/IMAP/POP3 由 `ALLOW_LIVE_SMTP` 与 `ALLOW_PUBLIC_MAILBOX` 控制，账户密码只能通过受权 API 写入加密存储，不能写入 Compose、镜像或日志。公网目标必须使用 TLS。

个性化草稿默认使用 `PERSONALIZATION_ENABLED=false`。启用时由运行平台 Secret 注入 `PERSONALIZATION_API_KEY`，再设置 `PERSONALIZATION_ENABLED=true`；Key 为空时 Worker 会拒绝启动启用态。通过 `PERSONALIZATION_PROVIDER=openai|anthropic`、`PERSONALIZATION_MODEL` 与 `PERSONALIZATION_API_BASE_URL` 指定模型和 HTTPS 网关。Anthropic 默认 `PERSONALIZATION_API_AUTH_SCHEME=x-api-key`，仅在网关要求时设为 `bearer`。Compose 兼容旧 `OPENAI_API_KEY` / `OPENAI_API_BASE_URL`（非空通用变量优先）；原生 Worker/Ray 必须使用 `PERSONALIZATION_*`。可配置并发和超时，但不要把 Key 写入 `.env.example`、Compose、镜像或前端。Ray head、Ray worker 和 personalization worker 只加入内部网络，10001/6379 不得发布到宿主机或公网。

Phase 3 还要求设置受监控的 `ARXIV_CONTACT_EMAIL`。官方端点固定为 `https://export.arxiv.org/api/query` 与 `https://oaipmh.arxiv.org/oai`，允许主机固定为 `export.arxiv.org,oaipmh.arxiv.org,arxiv.org`；不要用环境变量指向任意第三方镜像。`ARXIV_MIN_REQUEST_INTERVAL` 只能保持 `PT3S` 或更慢，多个 API/Worker 副本必须共享同一 Redis 实例。

Source 端点固定为 `https://export.arxiv.org/e-print`。`.env.example` 提供归档 50 MiB、展开 250 MiB、单文件 20 MiB、5,000 文件及压缩比 100 的默认上限；只可在容量评审后收紧或有限提高，不能为了处理未知归档而取消限制。Compose 将 Worker 的 `/var/tmp/arxiv-source` 挂为 512 MiB tmpfs，生产编排平台必须提供等价的临时卷、容量上限和任务结束清理语义。

## 测试邮件图片回传

`TRACKING_ENABLED=false` 为默认值；每封诊断/模板测试邮件还必须显式选择 `trackOpens=true` 才插入图片。启用前通过 Secret 单独提供至少 32 字节的 Base64 `TRACKING_SIGNING_KEY_BASE64`，不能复用 JWT、模板图片或其他应用密钥；缺失、格式错误或复用会使启用态 API 启动失败。

`TRACKING_PUBLIC_BASE_URL` 未配置时沿用现有 `PUBLIC_BASE_URL`（Compose 中空值也回退），默认 `http://localhost:8080`。必须是无路径、查询、片段或用户信息的绝对 origin；公网主机只允许 HTTPS，本地/私网允许 HTTP。HTTPS 只表示配置形态，不能证明公网可达。外部邮箱图片代理无法访问本机 localhost；本功能不创建公网服务或隧道，也不调整端口暴露。

默认 `TRACKING_TOKEN_TTL=PT720H`（30 天），允许 1 分钟至 90 天的整数秒。Nginx `/t/` 关闭访问/错误日志、禁用缓存并按来源地址限制为 10 请求/秒、突发 20；上游负载均衡、WAF、APM 同样必须排除这些能力 URL，不能记录完整路径或异常请求 URL。回传仅表示“检测到图片加载 / 估算打开”，不能证明已阅读。数据模型、权限、误差、手动保留期清理及本地验证流程见 [邮件回传说明](EMAIL_TRACKING.md)。

## 开发环境

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
bash scripts/verify-compose.sh
bash scripts/verify-container-images.sh
curl -fsS http://localhost:8080/api/v1/system/health
curl -fsS http://localhost:8025/api/v1/info
```

开发覆盖额外发布 Mailpit 8025 和 MinIO Console 9001。端口冲突时：

```bash
HTTP_PORT=18080 MAILPIT_WEB_PORT=18025 MINIO_CONSOLE_PORT=19001 \
  docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

## 生产形态 Compose

```bash
docker compose -f docker-compose.yml config --quiet
docker compose -f docker-compose.yml up -d --build
docker compose -f docker-compose.yml ps
```

生产基线只发布 `${HTTP_PORT:-8080}`。TLS 应在受信任的入口代理/负载均衡终止；边缘代理必须清除客户端自带的 forwarding headers。仓库 Nginx 只把连接的 `$remote_addr` 写入 `X-Forwarded-For`，不追加客户端链。若 Nginx 前还有负载均衡器，必须仅对其受控 CIDR 配置 `set_real_ip_from`，再启用 `real_ip_header`/`real_ip_recursive`，使 `$remote_addr` 成为真实客户端地址；禁止信任任意来源的转发头。公网部署还需要：

- 外部 Secret Manager 和密钥轮换；
- 托管 PostgreSQL/Redis/Kafka 或可靠备份；
- TLS、域名、WAF/速率限制和集中日志；
- 镜像仓库、签名、SBOM 与漏洞扫描；
- 至少两个 API 副本和独立 worker 扩缩容；
- SMTP 域名认证、退信/投诉回路和法务确认。

Compose 的 `deploy.resources` 是容量指导；非 Swarm 运行时应在编排平台中映射同等限制。

## 发布顺序

1. 备份 PostgreSQL/MinIO，验证可恢复。
2. 构建并扫描不可变版本镜像，设置 `APP_VERSION`。
3. 先启动 PostgreSQL/Redis/Kafka/MinIO，等待健康并确认 `kafka-init` 已创建八个主题。
4. 单实例启动 API 执行 Flyway；迁移成功后扩容 API/worker。
5. 启动 Ray head/worker 和个性化消费者；检查内部 Ray Client、`mail.personalization.worker` 与 `mail.personalization.results.backend`。
6. 启动前端，验证 `/healthz`、API readiness 和关键只读请求。
7. 验证离线分类 API、Worker heartbeat、`camel.arxiv.jobs.v1`/`camel.arxiv.results.v1` consumer lag 和 Outbox 发布。
8. 用少量明确 arXiv ID 做导入冒烟，确认 Job 终态、事件回放和论文库。
9. 对一篇小型公开 Source 做提取冒烟，确认归档限制、加密联系人、脱敏 UI、提取运行和临时目录为空后再开放批量提取。
10. 观察错误率、数据库连接、Redis 租约、队列/DLQ 和 tmpfs 使用量后再开放流量。

## 回滚

应用回滚通过 `APP_VERSION` 指向上一镜像。数据库采用前向兼容迁移，不自动 down migrate；如果新版本写入了不兼容数据，应按变更手册恢复备份或执行经过评审的修复迁移。

V6 是向前兼容迁移：增加分类快照/同步游标/论文导入来源，并扩展 Job 与论文搜索索引。回滚应用时不要删除 V6 表或 active taxonomy；旧应用忽略新增列。若新 Worker 已发布 v1 结果，先停止 Worker 和 Outbox dispatcher，再回滚 API，避免没有相应消费者的消息继续积压。

V7 增加 Source 提取幂等/清理列、联系人 `display_nonce` 和映射乐观版本。回滚到不理解独立显示 nonce 或 Source 结果的版本前，先停止 Source 命令发布与 Worker 消费；不要删除 V7 列或索引。更换联系人密钥需要专门的版本化重加密迁移，不能直接替换环境变量。

V14 只新增测试邮件发送记录/图片回传事件，不改活动投递表。回滚时保留新表；旧版应用不会处理新图片回传。暂停收集可先设置 `TRACKING_ENABLED=false`，无需删除历史记录。轮换跟踪签名密钥会立即使此前发出的图片 token 失效，不应把因此缺失的回传解释为未阅读。
