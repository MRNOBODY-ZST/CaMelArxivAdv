# CaMelArxivAdv 实施计划

本计划以 [设计规格](docs/superpowers/specs/2026-08-05-arxiv-outreach-platform-design.md) 为准，按可独立运行和验收的垂直切片推进。每个阶段另有详细的测试驱动计划，阶段结束后必须更新本文件和 `TASKS.md`，并记录实际执行的验证结果。

## 仓库审计

- 基线分支：`main`，实施分支：`codex/arxiv-platform`。
- 用户已有内容：未跟踪的 `backend/` Spring Initializr 骨架，必须保留并在原位扩展。
- 后端基线：Java 25、Spring Boot 4.1.0、Gradle 9.5.1、WebFlux/R2DBC/Security/Redis/Modulith；没有业务代码、认证、迁移或异常处理。
- 基础设施基线：`backend/compose.yaml` 只有 MongoDB、MySQL 和 Redis，含不安全的开发明文值；没有 PostgreSQL、RabbitMQ、MinIO 或 Mailpit。
- 前端和 Python Worker 不存在。
- 基线构建：`./gradlew clean test build` 在依赖解析阶段失败，原因是 Maven Central 对当前网络返回 HTTP 403，而不是测试断言失败。
- DesignSkill：已审计 `MRNOBODY-ZST/TailwindCSS-DesignSkill@b76e370f`。目录含 657 个组件元数据；用户已确认持有 Pro License。当前可读上游为 React/Tailwind 4.3，允许按原组件语义适配到 Vue 3。

## 技术决策

- API：Spring Boot WebFlux + Spring Security + R2DBC PostgreSQL。
- 迁移：Flyway JDBC，与 R2DBC 共用 PostgreSQL。
- 消息与状态：RabbitMQ + Redis；消息版本化、手动 ACK、幂等键和死信。
- Worker：Python 3.12、Pydantic、aio-pika/httpx、pytest、Ruff、MyPy。
- Frontend：Vue 3、TypeScript strict、Vite、Tailwind CSS 4、Pinia、Vue Router、Axios、ECharts、Headless UI、Heroicons。
- 对象存储：MinIO，仅用于图片、导出和上传，不长期保存 arXiv Source。
- 邮件：Spring Mail Worker 独立 Profile；开发和测试仅 Mailpit，`ALLOW_LIVE_SMTP=false`。

## 阶段

1. **工程基础**：仓库守卫、后端依赖与配置、Flyway 基础迁移、Worker/Frontend 初始化、DesignSkill 外壳、Compose、健康检查、OpenAPI、统一错误。
2. **认证与 RBAC**：用户、角色、权限、JWT、Refresh 轮换、登录限制、初始管理员、审计和前端权限路由。
3. **arXiv 发现与导入**：分类快照/同步、查询预览、保存查询、导入任务、SSE、论文库。
4. **Source 解析**：安全下载/解包、TeX 发现、作者/邮箱/机构提取、证据与置信度、RabbitMQ 幂等回写。
5. **数据统计**：采集/论文/联系人聚合、ECharts、URL 筛选、权限脱敏和导出。
6. **模板与 SMTP**：安全模板编辑/版本/预览、MinIO 图片、SMTP AES-GCM 配置、Mailpit 测试发送。
7. **活动发送**：Segment、收件人快照、审批状态机、限速、抑制/退订、Mail Worker、失败重试。
8. **追踪**：签名打开像素、受控点击重定向、机器人标记、活动分析和数据保留。
9. **完善发布**：全量 Testcontainers/pytest/Vitest/Playwright、可访问性、安全审计、Docker E2E、运维和隐私文档。

## 阶段验收规则

每阶段必须依次执行适用的后端测试、Worker 测试、前端测试、lint、类型检查和构建。任何因外部网络、Docker 或服务不可用而未执行的检查必须明确记录；未看到成功输出的检查不得标为通过。

