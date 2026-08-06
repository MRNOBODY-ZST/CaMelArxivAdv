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

## 当前进度

- Phase 1（工程基础）：**已完成，2026-08-05 验收**。
- Phase 2（认证与 RBAC）：**已完成，2026-08-05 验收**。
- Phase 3（arXiv 发现与导入）：**已完成，2026-08-06 验收**。
- Phase 4（Source 解析）：**已完成，2026-08-06 验收**。
- Phase 5–9：待按任务清单逐阶段实现，不以数据库占位表代替业务验收。

Phase 1 实际证据：后端 `clean check bootJar` 成功；Testcontainers 从空 PostgreSQL 执行 4 个 Flyway 迁移并建立 50 张表；Python 8 tests、Ruff、MyPy strict 成功；前端 7 tests、ESLint、`vue-tsc`、Vite build 成功；Compose/镜像契约成功；九服务均健康，外部健康 API、Mailpit 和 MinIO Console 可达。浏览器验证桌面 1280×720、移动 390×844 均无横向溢出和 console warning/error，移动抽屉焦点恢复正常。

Phase 2 实际证据：Flyway V5 幂等建立 26 项权限和 5 个系统角色；后端全量 `clean check bootJar`、前端 25 项 Vitest/ESLint/`vue-tsc`/Vite build 均通过。真实 HTTP 验证了初始管理员强制改密、改密后 access/refresh 立即失效、refresh 轮换及重放整族撤销、logout、拒绝/重放审计、普通 `ADMIN` 无法接管 `SUPER_ADMIN`、角色不变量以及 `VIEWER` 403。真实 `ForwardedHeaderTransformer` 和容器内 HTTP 验证两个代理客户端形成独立 IP 限流桶。生产 Compose 缺失认证密钥时拒绝渲染，九服务重建后健康；用户、角色、审计页面在桌面与 390×844 下无页面级横向溢出，控制台零 warning/error。

Phase 3 实际证据：Flyway V6 从空库建立 53 张表和 1 个物化视图；后端 132 tests/`clean check`/`bootJar`、Python 31 tests/Ruff/MyPy、前端 27 tests/ESLint/`vue-tsc`/Vite build 均通过。真实 Compose 运行验证了官方分类离线读取、Legacy API 查询预览、Redis 全局租约、Outbox/RabbitMQ、Worker 心跳、选中论文导入、任务事件/终态和论文持久化；真实导入 `2212.02256` 成功。管理员分类同步通过 OAI `ListSets` 形成完整 Job → Outbox → Worker → 结果快照闭环，Job `18fed311-b1af-4dd7-ae09-148a867aac71` 原子完成 166 个分类、6 个 alias 和 155 条描述；两段式 physics set 被保留，重复内容复用既有快照且历史分类只失活不删除。`mail-worker` Profile 的业务 API 真实请求返回 404。桌面发现/任务/论文流程与 390×844 发现页均无全局横向溢出，控制台零 error。

Phase 4 实际证据：Flyway V7 后保持 53 张表和 1 个物化视图并增加 Source 幂等/尺寸/清理证明、独立显示 nonce 与映射乐观版本；后端 153 tests/`clean check`/`bootJar`、Python 68 tests/Ruff/MyPy strict（41 files）、前端 30 tests/ESLint/`vue-tsc`/Vite build 全部通过，Compose 九服务与三个非 root 镜像契约通过。真实官方 Source Job `81f0900e-2865-4044-8c42-dff7899505db` 解析论文 `2212.02256`：`TAR_GZIP` 归档 488,729 bytes、展开 913,762 bytes、检查 1 个 TeX 文件，按顺序保留 2 位作者并得到 2 个 `HIGH`/`UNVERIFIED` 明确联系人；数据库验证规范化/显示 nonce 全部不同、HMAC 唯一、两类密文均不含 `@`，Worker 临时根为空且 RestartCount=0。受权 HTTP 验证列表始终脱敏、单条完整披露和披露审计成功、论文提取记录清理确认可见；`mail-worker` 业务 API 为 404。桌面 1280×720 与移动 390×844 的联系人/论文详情均 `scrollWidth=clientWidth`，七个指定标签页可用，控制台零 warning/error；验收 QA 账号及失败试跑的单条 DLQ 消息均已删除，四个 arXiv 队列最终为空。

## 阶段验收规则

每阶段必须依次执行适用的后端测试、Worker 测试、前端测试、lint、类型检查和构建。任何因外部网络、Docker 或服务不可用而未执行的检查必须明确记录；未看到成功输出的检查不得标为通过。
