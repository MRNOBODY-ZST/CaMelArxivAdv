# CaMel Arxiv Outreach Platform

面向 arXiv 论文发现、联系人证据提取、合规邮件活动与数据分析的一体化平台。仓库当前已完成 Phase 1–3：生产形态基础设施、认证/RBAC、官方分类离线回退与同步、查询预览/缓存、保存查询、可控异步导入、OAI-PMH 增量同步、任务 SSE/轮询和论文库均已形成可运行垂直切片。

> Source 解析、联系人提取、统计、模板/SMTP、邮件活动和追踪仍按 `IMPLEMENTATION_PLAN.md` 的 Phase 4–9 继续开发。真实 SMTP 保持关闭；尚无业务数据时只显示空状态，不生成演示指标。

## 快速启动

要求：Docker Desktop（Compose v2）、8 GB 以上可用内存。复制环境模板，替换所有 `change-this-*` 值，并为两个必填认证密钥分别生成独立的 32 字节 Base64 值：

```bash
cp .env.example .env
openssl rand -base64 32  # 填入 JWT_SIGNING_KEY_BASE64
openssl rand -base64 32  # 另生成一次，填入 AUTH_FINGERPRINT_HMAC_KEY_BASE64
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps
```

不要复用两次输出，也不要提交 `.env`。其余空白 `*_KEY_BASE64` 应在相应业务阶段启用前同样使用独立随机值填充。

启动后：

- 应用入口：[http://localhost:8080](http://localhost:8080)
- Mailpit：[http://localhost:8025](http://localhost:8025)
- MinIO Console：[http://localhost:9001](http://localhost:9001)
- 健康 API：[http://localhost:8080/api/v1/system/health](http://localhost:8080/api/v1/system/health)
- 论文发现：[http://localhost:8080/arxiv/discovery](http://localhost:8080/arxiv/discovery)
- 导入任务：[http://localhost:8080/jobs](http://localhost:8080/jobs)
- 论文库：[http://localhost:8080/papers](http://localhost:8080/papers)

首次启动需在 `.env` 设置四个 `INITIAL_ADMIN_*` 值；临时密码必须满足至少 12 位以及大小写、数字、符号要求。首次登录会强制改密。生产部署完成后应从运行时 Secret 中移除初始密码，详见 [认证与 RBAC](docs/RBAC.md)。

PostgreSQL、Redis、RabbitMQ、后端、Mail Worker 和 arXiv Worker 不发布宿主端口。真实 SMTP 默认强制关闭：`ALLOW_LIVE_SMTP=false`。

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
cd .. && bash scripts/verify-compose.sh
bash scripts/verify-container-images.sh
```

Phase 1–3 的实际验收结果记录在 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)。

## 文档

- [架构](docs/ARCHITECTURE.md)
- [数据模型与 ERD](docs/ERD.md)
- [API 约定](docs/API.md)
- [认证与 RBAC](docs/RBAC.md)
- [部署](docs/DEPLOYMENT.md)
- [运维](docs/OPERATIONS.md)
- [DesignSkill 组件映射](docs/DESIGN_SKILL_COMPONENT_MAP.md)
- [第三方与授权说明](docs/THIRD_PARTY_NOTICES.md)
- [实施任务](TASKS.md)

## 安全边界

- arXiv Legacy API、OAI-PMH 和分类同步只允许配置中的官方 HTTPS 主机；Java/Python 共享 Redis 全局三秒租约。Source 阶段仍须增加大小上限、安全解包和临时目录清理。
- SMTP、JWT、HMAC 与追踪密钥必须由独立随机值提供；数据库只保存受保护的 Secret 材料。
- 邮箱、Token、Authorization、Cookie 和 Source 内容不得写入日志。
- 邮件发送必须经过快照、抑制、退订、频控和审批状态机；SMTP 接受不等于最终送达。
- 跟踪打开率仅是估算指标，点击跳转必须防止开放重定向。

## License

项目代码遵循仓库 [LICENSE](LICENSE)。Tailwind Plus/DesignSkill 资产依据用户持有的 Pro License 在本项目内适配；授权来源和组件映射见第三方说明，不应将上游付费资产作为独立素材再分发。
