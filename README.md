# CaMel Arxiv Outreach Platform

面向 arXiv 论文发现、联系人证据提取、合规邮件活动与数据分析的一体化平台。仓库当前已完成 Phase 1 工程基础：生产形态数据库、统一 API 基线、Python worker、授权 DesignSkill 前端外壳，以及可一键启动的九服务 Docker Compose 栈。

> 当前业务功能仍按 `IMPLEMENTATION_PLAN.md` 的 Phase 2–9 继续开发。仪表盘使用真实健康接口；尚无业务数据时只显示空状态，不生成演示指标。

## 快速启动

要求：Docker Desktop（Compose v2）、8 GB 以上可用内存。复制环境模板并至少替换所有 `change-this-*` 值：

```bash
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps
```

启动后：

- 应用入口：[http://localhost:8080](http://localhost:8080)
- Mailpit：[http://localhost:8025](http://localhost:8025)
- MinIO Console：[http://localhost:9001](http://localhost:9001)
- 健康 API：[http://localhost:8080/api/v1/system/health](http://localhost:8080/api/v1/system/health)

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

Phase 1 的实际验收结果记录在 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)。

## 文档

- [架构](docs/ARCHITECTURE.md)
- [数据模型与 ERD](docs/ERD.md)
- [API 约定](docs/API.md)
- [部署](docs/DEPLOYMENT.md)
- [运维](docs/OPERATIONS.md)
- [DesignSkill 组件映射](docs/DESIGN_SKILL_COMPONENT_MAP.md)
- [第三方与授权说明](docs/THIRD_PARTY_NOTICES.md)
- [实施任务](TASKS.md)

## 安全边界

- arXiv Source 后续只允许官方主机、限速下载、大小上限和临时目录处理，不进入长期对象存储。
- SMTP、JWT、HMAC 与追踪密钥必须由独立随机值提供；数据库只保存受保护的 Secret 材料。
- 邮箱、Token、Authorization、Cookie 和 Source 内容不得写入日志。
- 邮件发送必须经过快照、抑制、退订、频控和审批状态机；SMTP 接受不等于最终送达。
- 跟踪打开率仅是估算指标，点击跳转必须防止开放重定向。

## License

项目代码遵循仓库 [LICENSE](LICENSE)。Tailwind Plus/DesignSkill 资产依据用户持有的 Pro License 在本项目内适配；授权来源和组件映射见第三方说明，不应将上游付费资产作为独立素材再分发。
