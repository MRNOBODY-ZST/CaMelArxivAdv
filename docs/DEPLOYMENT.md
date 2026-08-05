# 部署指南

## 配置准备

```bash
cp .env.example .env
```

生产部署必须替换数据库、RabbitMQ、MinIO 密码，并为 `APP_ENCRYPTION_KEY_BASE64`、`APP_EMAIL_HMAC_KEY_BASE64`、`JWT_SIGNING_KEY_BASE64`、`TRACKING_SIGNING_KEY_BASE64` 分别生成独立 32 字节随机值。不要提交 `.env`。

Phase 1 尚未启用初始管理员 Bootstrap 和真实 SMTP；保留 `ALLOW_LIVE_SMTP=false`。即使后续启用，也应由部署平台单独注入 Secret，而不是写入 Compose 或镜像。

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

生产基线只发布 `${HTTP_PORT:-8080}`。TLS 应在受信任的入口代理/负载均衡终止，并把 `X-Forwarded-Proto` 传给 Nginx/应用。公网部署还需要：

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
6. 观察错误率、数据库连接、队列积压后再开放流量。

## 回滚

应用回滚通过 `APP_VERSION` 指向上一镜像。数据库采用前向兼容迁移，不自动 down migrate；如果新版本写入了不兼容数据，应按变更手册恢复备份或执行经过评审的修复迁移。
