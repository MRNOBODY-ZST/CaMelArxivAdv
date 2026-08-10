# CaMel Arxiv Outreach Platform

面向 arXiv 论文发现、联系人证据提取、合规邮件活动与数据分析的一体化平台。仓库包含生产形态基础设施、认证/RBAC、官方分类与论文导入、异步任务、Source 安全提取、真实 PostgreSQL 分析、安全模板/私有图片/本机 SMTP，以及基于 RabbitMQ、Ray Core 和 OpenAI Responses API 的逐作者个性化草稿生成链路。

> 活动发送和追踪仍按 `IMPLEMENTATION_PLAN.md` 的 Phase 7–9 继续开发。真实 SMTP 保持关闭；尚无业务数据时只显示空状态，不生成演示指标。

个性化生成默认关闭。只有在运行环境通过 Secret 注入 `OPENAI_API_KEY` 并设置 `PERSONALIZATION_ENABLED=true` 后，活动页才允许提交生成任务。ChatGPT 订阅与 API 凭据/计费相互独立；未配置 Key 时界面会明确显示禁用，不会生成伪数据。请求使用 Responses API 的严格 JSON Schema 输出且设置 `store=false`，公开作者/论文内容在提交前经过字段白名单；邮箱地址和 SMTP 凭据不会发送给模型。

## 快速启动

要求：Docker Desktop（Compose v2）、8 GB 以上可用内存。复制环境模板，替换所有 `change-this-*` 值，并为五个当前必填密钥分别生成独立的 32 字节 Base64 值：

```bash
cp .env.example .env
openssl rand -base64 32  # 填入 JWT_SIGNING_KEY_BASE64
openssl rand -base64 32  # 另生成一次，填入 AUTH_FINGERPRINT_HMAC_KEY_BASE64
openssl rand -base64 32  # 另生成一次，填入 APP_ENCRYPTION_KEY_BASE64
openssl rand -base64 32  # 另生成一次，填入 APP_EMAIL_HMAC_KEY_BASE64
openssl rand -base64 32  # 另生成一次，填入 TEMPLATE_ASSET_SIGNING_KEY_BASE64
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps
```

不要复用任何一次输出，也不要提交 `.env`。其余空白 `*_KEY_BASE64` 应在相应业务阶段启用前同样使用独立随机值填充。

启动后：

- 应用入口：[http://localhost:8080](http://localhost:8080)
- Mailpit：[http://localhost:8025](http://localhost:8025)
- MinIO Console：[http://localhost:9001](http://localhost:9001)
- 健康 API：[http://localhost:8080/api/v1/system/health](http://localhost:8080/api/v1/system/health)
- 论文发现：[http://localhost:8080/arxiv/discovery](http://localhost:8080/arxiv/discovery)
- 导入任务：[http://localhost:8080/jobs](http://localhost:8080/jobs)
- 论文库：[http://localhost:8080/papers](http://localhost:8080/papers)
- 作者与联系人：[http://localhost:8080/contacts](http://localhost:8080/contacts)
- 采集分析：[http://localhost:8080/analytics/ingestion](http://localhost:8080/analytics/ingestion)
- 论文分析：[http://localhost:8080/analytics/papers](http://localhost:8080/analytics/papers)
- 联系人分析：[http://localhost:8080/analytics/contacts](http://localhost:8080/analytics/contacts)
- 邮件模板：[http://localhost:8080/email/templates](http://localhost:8080/email/templates)
- SMTP 账户：[http://localhost:8080/admin/smtp-accounts](http://localhost:8080/admin/smtp-accounts)
- 收件人分组：[http://localhost:8080/email/segments](http://localhost:8080/email/segments)
- 邮件活动：[http://localhost:8080/email/campaigns](http://localhost:8080/email/campaigns)
- 系统运行状态：[http://localhost:8080/admin/settings](http://localhost:8080/admin/settings)

首次启动需在 `.env` 设置四个 `INITIAL_ADMIN_*` 值；临时密码必须满足至少 12 位以及大小写、数字、符号要求。首次登录会强制改密。生产部署完成后应从运行时 Secret 中移除初始密码，详见 [认证与 RBAC](docs/RBAC.md)。

PostgreSQL、Redis、RabbitMQ、后端、Worker 与 Ray Client/GCS 均不发布宿主端口。真实 SMTP 默认强制关闭：`ALLOW_LIVE_SMTP=false`。

停止服务但保留数据：

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
```

不要在需要保留数据时添加 `--volumes`。

## 工程结构

```text
backend/                 Spring Boot WebFlux API 与 Mail Worker
worker/                  Python 3.12 arXiv/Source 隔离 Worker
frontend/                Vue 3 + TypeScript strict 管理端
infra/nginx/             单一生产入口与安全响应头
infra/postgres/          PostgreSQL 初始化挂载点
scripts/                 Compose 与镜像契约验证
docs/                    架构、ERD、API、部署、运维与设计证据
docker-compose.yml       生产形态基线
docker-compose.dev.yml   仅开发环境的 Mailpit/MinIO 控制台入口
```

## 本地质量门

```bash
cd backend && ./gradlew --no-daemon clean check bootJar
cd ../worker && uv run pytest -q
uv run ruff check .
uv run mypy src tests
cd ../frontend && npm test -- --run
npm run lint
npm run typecheck
npm run build
# 需要具备 template/smtp 管理权限的本地账号；测试自行创建并归档夹具
E2E_USER=... E2E_PASSWORD=... npm run test:e2e
cd .. && bash scripts/verify-compose.sh
bash scripts/verify-container-images.sh
```

Phase 1–6 的实际验收结果记录在 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)。

## 文档

- [架构](docs/ARCHITECTURE.md)
- [数据模型与 ERD](docs/ERD.md)
- [API 约定](docs/API.md)
- [认证与 RBAC](docs/RBAC.md)
- [TeX Source 提取](docs/TEX_EXTRACTION.md)
- [数据统计口径与看板](docs/ANALYTICS.md)
- [安全与隐私](docs/SECURITY_AND_PRIVACY.md)
- [部署](docs/DEPLOYMENT.md)
- [运维](docs/OPERATIONS.md)
- [DesignSkill 组件映射](docs/DESIGN_SKILL_COMPONENT_MAP.md)
- [第三方与授权说明](docs/THIRD_PARTY_NOTICES.md)
- [实施任务](TASKS.md)

## 安全边界

- arXiv Legacy API、OAI-PMH、分类和 Source 只允许固定官方 HTTPS 主机；Java/Python 共享 Redis 全局三秒租约。Source 下载、重定向、归档尺寸/路径/链接/压缩比、include 深度和解析时间均有边界，临时文件只在有界 tmpfs 存在并在结果发布前清理。
- 联系人规范化值和显示值用独立随机 nonce 做 AES-256-GCM 加密，另一把 HMAC 密钥去重；列表和证据默认脱敏，完整披露必须单条授权并审计。
- SMTP、JWT、HMAC 与追踪密钥必须由独立随机值提供；数据库只保存受保护的 Secret 材料。
- 邮箱、Token、Authorization、Cookie 和 Source 内容不得写入日志。
- 邮件发送必须经过快照、抑制、退订、频控和审批状态机；SMTP 接受不等于最终送达。
- 跟踪打开率仅是估算指标，点击跳转必须防止开放重定向。

## License

项目代码遵循仓库 [LICENSE](LICENSE)。Tailwind Plus/DesignSkill 资产依据用户持有的 Pro License 在本项目内适配；授权来源和组件映射见第三方说明，不应将上游付费资产作为独立素材再分发。
