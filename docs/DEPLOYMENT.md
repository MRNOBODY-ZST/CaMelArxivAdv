# 部署指南

## 配置准备

```bash
cp .env.example .env
```

生产部署必须替换数据库、RabbitMQ、MinIO 密码，并为 `APP_ENCRYPTION_KEY_BASE64`、`APP_EMAIL_HMAC_KEY_BASE64`、`TEMPLATE_ASSET_SIGNING_KEY_BASE64`、`JWT_SIGNING_KEY_BASE64`、`AUTH_FINGERPRINT_HMAC_KEY_BASE64` 分别生成独立 32 字节随机值；后续启用追踪时再独立生成 `TRACKING_SIGNING_KEY_BASE64`。`docker-compose.yml` 对当前五把密钥使用必填插值，缺少任一值时配置渲染会直接失败。不要提交 `.env`，也不要复用随机输出。

首次部署通过运行平台 Secret 注入四个 `INITIAL_ADMIN_*` 值。引导账号创建后必须完成首次改密，并立即从 Secret 中移除 `INITIAL_ADMIN_PASSWORD`；引导逻辑不会覆盖已有账号。真实 SMTP 仍保持 `ALLOW_LIVE_SMTP=false`，后续启用也只能由部署平台注入 Secret，不能写入 Compose 或镜像。

Phase 3 还要求设置受监控的 `ARXIV_CONTACT_EMAIL`。官方端点固定为 `https://export.arxiv.org/api/query` 与 `https://oaipmh.arxiv.org/oai`，允许主机固定为 `export.arxiv.org,oaipmh.arxiv.org,arxiv.org`；不要用环境变量指向任意第三方镜像。`ARXIV_MIN_REQUEST_INTERVAL` 只能保持 `PT3S` 或更慢，多个 API/Worker 副本必须共享同一 Redis 实例。

Source 端点固定为 `https://export.arxiv.org/e-print`。`.env.example` 提供归档 50 MiB、展开 250 MiB、单文件 20 MiB、5,000 文件及压缩比 100 的默认上限；只可在容量评审后收紧或有限提高，不能为了处理未知归档而取消限制。Compose 将 Worker 的 `/var/tmp/arxiv-source` 挂为 512 MiB tmpfs，生产编排平台必须提供等价的临时卷、容量上限和任务结束清理语义。

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
- 托管 PostgreSQL/Redis/RabbitMQ 或可靠备份；
- TLS、域名、WAF/速率限制和集中日志；
- 镜像仓库、签名、SBOM 与漏洞扫描；
- 至少两个 API 副本和独立 worker 扩缩容；
- SMTP 域名认证、退信/投诉回路和法务确认。

Compose 的 `deploy.resources` 是容量指导；非 Swarm 运行时应在编排平台中映射同等限制。

## 发布顺序

1. 备份 PostgreSQL/MinIO，验证可恢复。
2. 构建并扫描不可变版本镜像，设置 `APP_VERSION`。
3. 先启动 PostgreSQL/Redis/RabbitMQ/MinIO，等待健康。
4. 单实例启动 API 执行 Flyway；迁移成功后扩容 API/worker。
5. 启动前端，验证 `/healthz`、API readiness 和关键只读请求。
6. 验证离线分类 API、Worker heartbeat、`arxiv.jobs.worker`/`arxiv.results.backend` bindings 和 Outbox 发布。
7. 用少量明确 arXiv ID 做导入冒烟，确认 Job 终态、事件回放和论文库。
8. 对一篇小型公开 Source 做提取冒烟，确认归档限制、加密联系人、脱敏 UI、提取运行和临时目录为空后再开放批量提取。
9. 观察错误率、数据库连接、Redis 租约、队列/DLQ 和 tmpfs 使用量后再开放流量。

## 回滚

应用回滚通过 `APP_VERSION` 指向上一镜像。数据库采用前向兼容迁移，不自动 down migrate；如果新版本写入了不兼容数据，应按变更手册恢复备份或执行经过评审的修复迁移。

V6 是向前兼容迁移：增加分类快照/同步游标/论文导入来源，并扩展 Job 与论文搜索索引。回滚应用时不要删除 V6 表或 active taxonomy；旧应用忽略新增列。若新 Worker 已发布 v1 结果，先停止 Worker 和 Outbox dispatcher，再回滚 API，避免没有相应消费者的消息继续积压。

V7 增加 Source 提取幂等/清理列、联系人 `display_nonce` 和映射乐观版本。回滚到不理解独立显示 nonce 或 Source 结果的版本前，先停止 Source 命令发布与 Worker 消费；不要删除 V7 列或索引。更换联系人密钥需要专门的版本化重加密迁移，不能直接替换环境变量。
