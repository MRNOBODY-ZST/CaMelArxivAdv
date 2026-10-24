# CaMel arXiv Outreach Platform

论文发现、联系人证据与可审计研究触达平台。

[快速开始](#快速开始) · [功能特性](#功能特性) · [系统架构](#系统架构) · [开发指南](#开发指南) · [文档导航](#文档导航) · [English](README.md)

CaMel 将 arXiv 论文发现、元数据导入、有界 TeX Source 提取、联系人证据、个性化邮件草稿、活动投递与分析整合到一个管理平台。Vue 前端提供工作界面，Spring Boot 服务负责授权与持久化状态，Python Worker 和 Ray 处理异步论文数据与草稿生成任务。

平台既支持逐项审核的普通活动，也支持显式授权后的自动触达。发现公开邮箱不等于确认作者身份、取得联系许可或验证地址可投递。

## 功能特性

### 论文发现与研究数据

- 按研究词、分类、作者和日期检索官方 arXiv 元数据，预览结果并保存可复用查询。
- 导入选定论文或有数量边界的搜索结果，同步分类与 OAI-PMH 元数据，保留论文版本和导入来源。
- 通过进度、事件、错误和 Worker 心跳信息查看异步任务。
- 浏览论文库、作者关系，以及采集、论文和联系人分析。

### Source 提取与联系人证据

- 从固定的官方 HTTPS 端点读取受支持的 TeX/Source 归档，不编译 TeX，也不执行归档内容。
- 统一控制请求间隔（官方请求之间至少三秒），校验重定向，并限制归档路径、大小、解析时间和临时文件生命周期。
- 提取论文明确公开的联系人证据，记录置信度和作者映射，区分机器提取结果与人工验证。
- 加密联系人值，使用独立 HMAC 密钥去重；普通列表脱敏，完整地址的单条披露需要授权并记录审计。
- 不猜测邮箱、不爬取无关网站，也不探测 SMTP 可投递性。

### 邮件模板与个性化草稿

- 编辑富文本 HTML 和纯文本替代内容，使用服务端渲染预览、不可变版本与私有图片资源。
- 通过兼容 OpenAI Responses 或 Anthropic Messages 的接口生成结合论文内容的草稿，校验结构化输出，并由独立的 Ray 生成链路执行。
- 在受权限控制的模型设置中配置提供方、模型、网关与凭据。
- 模型输入契约不包含收件人邮箱字段或 SMTP 凭据；生成草稿本身不会调用 SMTP。
- 内置研究邀约夹具突出“符合条件的新用户可注册试用并获得免费额度”，实际适用条件以提供方当前规则为准。额外研究支持需另行协商，不承诺固定额度金额。

### 邮件活动与协议管理

- 管理收件人分组、指定论文活动、发件账户、逐收件人正文、预检、审核、批准和投递。
- 冻结活动正文与收件人选择，使用数据库协调、有界重试、配额、退订、抑制和冷却规则。
- 配置 SMTP 与只读 IMAP/POP3 预览，显式发送诊断邮件，查看投递与发件准备诊断。
- 区分正式投递、受控安全试发、测试邮件记录、入站反馈和追踪结果。
- 通过仅草稿模板迁移，将符合条件的未发送项复制到独立 HTML 草稿，不改写已发历史，也不自动启动活动。

### 自动与每周增量触达

在自动触达工作区选择发件人、模板、研究方向和目标。经过授权的服务端流程可以串联搜索、导入、解析、生成、审核、批准与子活动启动。

| 工作流 | 范围 |
| --- | --- |
| 小规模任务 | 按界面显示的上限，以论文数或符合条件的唯一联系方式为目标。 |
| 批量任务 | 最多检索 10,000 篇唯一论文，拆分为分别经过门禁的子活动。 |
| 每周计划 | 每周一 09:00，Asia/Shanghai 时区，增量发现每轮最多 5,000 篇新论文。 |

每周发现会结合已配置的研究方向、可用的公开趋势证据与模型，并提供有边界的回退逻辑。它不是 ChatGPT 订阅或浏览器集成。

论文目标数不等于邮件配额：论文可能没有可用联系方式、与其他论文共享联系人，或产生多个候选。结果不足、配置变化、权限或投递检查都可能阻塞任务。自动发送需要组合权限和显式的 `AUTO_OUTREACH_SEND` 确认。部分流程提供的历史投递风险接受选项会记录审计，但不能绕过正文、安全、退订或传输要求。

关闭浏览器不会停止服务端任务。取消无法撤回已接受或在途邮件；暂停每周计划只阻止未来触发，不会自动取消已有任务。

### 分析与管理

- 查看采集、论文、联系人、作者网络、活动、链接分析，以及受支持的聚合 CSV 导出。
- 管理用户、角色、实时权限、审计事件、邮件账户与运行设置。
- 前端根据权限展示导航，最终访问控制仍由服务端执行。
- 图片加载和签名链接请求只是互动估算信号，不代表确认人工阅读或点击。

## 系统架构

~~~text
浏览器
   |
Nginx + Vue 前端
   | HTTP API / 任务事件
Spring Boot API ---------------- PostgreSQL（持久业务事实）
   |                             Redis（协调、缓存、限速）
   |                             MinIO（私有资源）
   |
Kafka 任务 / 结果 / 重试 / 死信主题
   |                     |                         |
arXiv Python Worker      个性化生成 Worker          Spring Mail Worker
   |                     |                         |
官方 arXiv API           Ray 任务 -> 模型 API       SMTP / 入站 IMAP
~~~

| 组件 | 职责 |
| --- | --- |
| `backend-api` | 认证、RBAC、API、流程编排、分析与 Flyway 迁移。 |
| `mail-worker` | 使用同一后端镜像的独立运行 profile，处理投递与入站消息，不暴露业务 Controller。 |
| `arxiv-worker` | 元数据、分类和有界 Source 处理。 |
| `personalization-worker` | 消费 Kafka 草稿生成命令并汇总结果。 |
| `ray-head` / `ray-worker` | 分布式、有并发边界的草稿生成任务。 |
| PostgreSQL / Redis / Kafka | 持久事实 / 临时协调 / 异步传输。 |
| MinIO / Mailpit / GreenMail | 私有资源 / 本地邮件捕获 / 隔离的邮件协议夹具。 |

PostgreSQL 是业务事实源。Redis 租约、Kafka ACK、HTTP 超时或空邮箱都不能证明一项业务操作已经完成。

### 技术栈

| 层次 | 技术 |
| --- | --- |
| 前端 | Vue 3、TypeScript、Vite 8、Tailwind CSS 4、Pinia、Vue Router、Headless UI、TipTap、ECharts、vis-network。 |
| 后端 | Java 25、Spring Boot 4.1、WebFlux、R2DBC PostgreSQL、Flyway、Spring Security、Kafka、Redis、Spring Modulith、MinIO SDK。 |
| Worker | Python 3.12、uv、aiokafka、httpx、Pydantic、defusedxml、Redis、Ray Core/Client。 |
| 本地基础设施 | PostgreSQL 17.5、Redis 8.0.2、Kafka 4.1.2、MinIO、Mailpit、GreenMail、Docker Compose。 |

准确依赖与镜像版本以构建文件、锁文件和 Compose 配置为准。

## 快速开始

以下步骤仅用于**全新、隔离的本地安装**，不是现有部署的升级或重启流程。不要复用生产配置、数据卷、队列、凭据或收件人数据。

### 1. 准备代码

需要 Git、OpenSSL、已运行的 Docker engine 与 Compose v2。本地可使用 Docker Desktop；建议先预留至少 8 GB 可用内存，这只是起始资源预算，不是容量保证。容器构建会提供应用所需工具链。

~~~bash
git clone https://github.com/MRNOBODY-ZST/CaMelArxivAdv.git
cd CaMelArxivAdv
cp .env.example .env
~~~

不要覆盖已有 `.env`。请选择未使用的 Compose 项目名和本工作副本独有的 `APP_VERSION` 标签。下面分别使用 `camel-arxiv-demo` 与 `local-demo`，如果已被使用，请更换。项目名可以隔离 Compose 网络和数据卷，但不能隔离全局镜像标签。

### 2. 配置密钥与初始管理员

替换示例 PostgreSQL 和 MinIO 密码，并为下列五个必填密钥**分别**生成不同的 32 字节 Base64 随机值：

| 变量 | 用途 |
| --- | --- |
| `JWT_SIGNING_KEY_BASE64` | Access Token 签名。 |
| `AUTH_FINGERPRINT_HMAC_KEY_BASE64` | 认证指纹。 |
| `APP_ENCRYPTION_KEY_BASE64` | 受保护应用数据与存储凭据。 |
| `APP_EMAIL_HMAC_KEY_BASE64` | 联系人去重。 |
| `TEMPLATE_ASSET_SIGNING_KEY_BASE64` | 私有模板资源签名。 |

~~~bash
openssl rand -base64 32
~~~

每个密钥单独执行一次，不得复用输出。Compose 还要求一个独立的内部配置令牌，**即使关闭模型生成也必须填写**：

~~~bash
openssl rand -hex 32
~~~

将该值填写到 `PERSONALIZATION_RUNTIME_CONFIG_TOKEN`。密钥仅保存在被忽略的本地 `.env` 或受保护运行时 Secret，不得进入前端代码或 Git。

设置 `INITIAL_ADMIN_USERNAME`、`INITIAL_ADMIN_EMAIL`、`INITIAL_ADMIN_DISPLAY_NAME` 和 `INITIAL_ADMIN_PASSWORD`。临时密码至少 12 位，包含大小写字母、数字和符号，且不能包含用户名或邮箱主体。在访问 arXiv 前，将 `ARXIV_CONTACT_EMAIL` 设置为受监控的联系邮箱。

引导逻辑只创建初始管理员，不覆盖已有账号。首次登录需要改密，完成后从运行时 Secret 中移除引导密码。

### 3. 明确关闭公网邮件与可选集成

在 `.env` 中显式设置：

~~~dotenv
APP_VERSION=local-demo
ALLOW_LIVE_SMTP=false
ALLOW_PUBLIC_MAILBOX=false
PERSONALIZATION_ENABLED=false
TRACKING_ENABLED=false
CAMPAIGN_DELIVERY_ENABLED=false
CAMPAIGN_SAFETY_ENABLED=false
CAMPAIGN_INBOUND_ENABLED=false
PARTNER_INVITE_ENABLED=false
~~~

当前环境示例与 Compose 的公网邮件协议回退值允许外部连接，不能假定所有开关默认关闭。以上配置仍允许白名单中的本地协议夹具，但不会启用活动投递。

### 4. 构建并启动本地服务

在仓库根目录执行，始终使用同一项目名：

~~~bash
docker compose -p camel-arxiv-demo -f docker-compose.yml -f docker-compose.dev.yml config --quiet
docker compose -p camel-arxiv-demo -f docker-compose.yml -f docker-compose.dev.yml up -d --build
docker compose -p camel-arxiv-demo -f docker-compose.yml -f docker-compose.dev.yml ps
~~~

开发覆盖会为本地 HTTP 关闭 Secure Cookie，并发布 Mailpit 和 MinIO 控制台；它不是公网部署配置。发布端口未显式绑定 loopback，请在可信本地网络或防火墙保护下使用。

| 入口 | 默认地址 |
| --- | --- |
| 应用 | [http://localhost:8080](http://localhost:8080) |
| 公开 API 健康检查 | [http://localhost:8080/api/v1/system/health](http://localhost:8080/api/v1/system/health) |
| Mailpit 收件箱 | [http://localhost:8025](http://localhost:8025) |
| MinIO 控制台 | [http://localhost:9001](http://localhost:9001) |
| OpenAPI JSON | [http://localhost:8080/api/openapi.json](http://localhost:8080/api/openapi.json) |

`HTTP_PORT`、`MAILPIT_WEB_PORT` 和 `MINIO_CONSOLE_PORT` 用于调整宿主机端口。Swagger UI 的配置路径为 `/api/docs`，OpenAPI JSON 可直接用于查看 API 定义。

PostgreSQL、Redis、Kafka、后端服务、邮件协议夹具端口和 Ray Client/GCS 都不发布到宿主机。例如，`mailpit:1025` 是**容器网络** SMTP 地址，不是 `localhost:1025`。

### 5. 在不联系真实收件人的情况下体验

1. 使用引导账号登录并完成改密。
2. 打开论文发现，预览一个小范围查询并导入少量论文。
3. 跟踪导入任务并查看论文元数据。Source 提取还需要兼容的 Source admission 配置，请完成对应配置后再发起。
4. 检查脱敏联系人证据，不把自动映射当成人工验证。
5. 创建并预览模板。如需本地测试，将 SMTP 配置为 `mailpit:1025`、`PLAIN_LOCAL_ONLY`，并显式指定合成测试收件人。
6. 查看 Mailpit 与发送记录。启用付费模型调用、公网邮件或自动任务前，先核对对应配置与权限。

只查看指定服务的日志，不输出完整配置：

~~~bash
docker compose -p camel-arxiv-demo -f docker-compose.yml -f docker-compose.dev.yml logs --tail=100 backend-api arxiv-worker
~~~

停止**这个本地演示栈**并保留具名数据卷：

~~~bash
docker compose -p camel-arxiv-demo -f docker-compose.yml -f docker-compose.dev.yml down
~~~

需要保留数据时不要加 `--volumes`。这些命令不是已有发件部署的安全停机流程。

## 配置说明

以 [.env.example](.env.example) 查看变量清单，以[部署指南](docs/DEPLOYMENT.md)准备不同环境。

| 配置领域 | 主要变量 |
| --- | --- |
| 地址与端口 | `PUBLIC_BASE_URL`、`HTTP_PORT`、`MAILPIT_WEB_PORT`、`MINIO_CONSOLE_PORT`。 |
| 模型生成 | `PERSONALIZATION_ENABLED`、`PERSONALIZATION_PROVIDER`、`PERSONALIZATION_MODEL`、`PERSONALIZATION_API_BASE_URL`、`PERSONALIZATION_API_KEY` 与内部配置令牌。 |
| 邮件协议 | `ALLOW_LIVE_SMTP`、`ALLOW_PUBLIC_MAILBOX` 与精确的本地主机白名单。 |
| 活动处理 | 独立的 `CAMPAIGN_DELIVERY_ENABLED`、`CAMPAIGN_SAFETY_ENABLED` 和 `CAMPAIGN_INBOUND_ENABLED` 门禁。 |
| 追踪 | `TRACKING_ENABLED`、可达的回调地址和独立的 `TRACKING_SIGNING_KEY_BASE64`。 |
| 合作方激活 | `PARTNER_INVITE_ENABLED`、专用服务端令牌和独立邀请加密密钥。 |

Source admission（`app.arxiv.source-admission.enabled`）和仅草稿模板迁移（`app.campaign.template-migrations.enabled`）均为独立的默认关闭能力。启用前需要兼容的 API/Worker 执行链路，并完成 [Source 发布](docs/operations/source-publication-runbook.md)与[模板迁移](docs/operations/template-migration-runbook.md)运行手册中的检查；本地快速开始不会自动启用它们。

环境变量用于初始化管理端模型设置，保存后的设置作用于后续生成。支持 `openai` 与 `anthropic` 两种提供方，网关必须支持对应 API 契约。提供方调用可能独立计费。

合作方激活是可选功能，与普通草稿生成不同。启用并使用时，会把包括邮箱地址在内的收件人数据发送到固定合作方服务。合作方凭据和已签发的 claim 链接不得进入日志或公开产物。试用资格和额度发放由提供方决定，模板文案不是额度保证。

公网邮件协议要求 STARTTLS 或隐式 TLS，并校验证书。明文仅用于精确白名单中的本地测试端点。公网追踪需要可达 HTTPS 与上游日志排除；配置 HTTPS 地址不等于证明外网可达。不要点击真实追踪、退订或 claim 链接来制造测试事件。

## 典型使用流程

~~~text
发现 -> 导入 -> 提取证据 -> 选择联系人、模板与发件人
     -> 生成草稿 -> 检查正文与预检
     -> 授权审核、批准 -> 启动或排期 -> 观察结果
~~~

普通活动将审核、批准和启动分为独立操作。显式授权的自动触达可以由对应操作者权限驱动这些状态转换，不代表每封邮件都经过人工审核。

| 工作区 | UI 路径 |
| --- | --- |
| 发现与任务 | `/arxiv/discovery`、`/jobs` |
| 论文与联系人 | `/papers`、`/contacts` |
| 模板与受众 | `/email/templates`、`/email/segments` |
| 活动与投递 | `/email/campaigns`、`/email/deliveries` |
| 自动触达 | `/email/automation` |
| 研究分析 | `/analytics/ingestion`、`/analytics/papers`、`/analytics/contacts`、`/analytics/authors` |
| 触达分析 | `/analytics/campaigns`、`/analytics/links` |
| 系统管理 | `/admin/users`、`/admin/roles`、`/admin/audit`、`/admin/mail-accounts`、`/admin/settings` |

### API 概览

业务 API 使用 `/api/v1` 前缀，需要认证及对应操作权限。平台通过引导创建管理员，不为平台操作者提供公开自助注册。

| 接口 | 用途 |
| --- | --- |
| `POST /api/v1/auth/login` | 使用用户名或邮箱登录。 |
| `GET /api/v1/arxiv/taxonomy` | 当前分类快照。 |
| `POST /api/v1/arxiv/search/preview` | 结构化发现预览。 |
| `POST /api/v1/arxiv/imports` | 创建导入任务。 |
| `GET /api/v1/jobs/{id}` | 查询持久化任务状态。 |
| `GET /api/v1/jobs/{id}/stream` | SSE 任务进度。 |
| `GET /api/v1/papers` | 筛选论文库。 |
| `GET /api/v1/contacts` | 获取脱敏联系人列表。 |
| `POST /api/v1/templates/preview` | 渲染并校验模板预览。 |

完整请求结构、数量边界和状态变更操作见 [API 文档](docs/API.md)与生成的 OpenAPI schema。

## 开发指南

### 工具链

| 组件 | 本地要求 |
| --- | --- |
| 后端 | JDK 25，使用仓库内 Gradle Wrapper。 |
| 前端 | Node.js 24.x，容器固定为 24.10.0。 |
| Worker | Python 3.12，不是 3.13；uv 与已提交锁文件。 |
| 集成测试 | 可用 Docker engine 与独立、由测试拥有的夹具。 |

### 前端

先让隔离 Compose API 在 8080 端口可用，再执行：

~~~bash
cd frontend
npm ci
npm run dev
~~~

Vite 使用 5173 端口，将 `/api` 和 `/t` 代理到 `http://localhost:8080`，不会发布内部数据库端口。

~~~bash
npm test -- --run
npm run lint
npm run typecheck
npm run build
~~~

### 后端

在 `backend/` 下执行：

~~~bash
./gradlew --no-daemon check bootJar
~~~

集成测试使用 Testcontainers，需要 Docker；缺依赖或跳过测试不算通过。原生运行后端还需要显式配置数据库、Redis、Kafka 和资源存储连接，主 Compose 栈不会为宿主机原生开发发布这些端口。

### Python Worker

在 `worker/` 下执行：

~~~bash
uv sync --frozen --group dev --python 3.12
uv run pytest -q
uv run ruff check .
uv run mypy src tests
~~~

安装后的入口为 `uv run arxiv-worker` 与 `uv run personalization-worker`。它们是真实消费者，不是离线演示，必须连接预期的 Kafka、Redis、Ray 和运行配置。不要把开发 Worker 接入生产队列。

### 浏览器与容器检查

Playwright 使用已安装的 Microsoft Edge 和本地合成夹具。在 `frontend/` 中通过 `E2E_USER` 与 `E2E_PASSWORD` 提供专用本地测试账号，再执行：

~~~bash
npm run test:e2e
~~~

测试可能创建、归档或删除测试资源，不得指向生产或共享运营数据。所需权限与夹具行为见[运维文档](docs/OPERATIONS.md)。

如需检查基线 Compose 配置契约，先安装 Bash、`jq` 与 Docker Compose，再在仓库根目录执行：

~~~bash
bash scripts/verify-compose.sh
~~~

它检查渲染后的配置，不验收运行栈的健康或行为。

另一个[镜像契约检查脚本](scripts/verify-container-images.sh)会启动短时容器，当前固定指向 `camel-arxiv/*:dev` 镜像，不读取 `APP_VERSION`，因此不能验证上文的 `local-demo` 镜像。请在独立验证环境核对其固定目标，不要为了使检查通过而覆盖或重打共享 `:dev` 标签。两类工具都不能证明任意工作副本或部署已经通过其运维门禁。

## 项目结构

~~~text
CaMelArxivAdv/
├── backend/
│   ├── src/main/java/com/camel_hub/advertisement/
│   │   ├── arxiv/           # 发现、论文和 Source 处理
│   │   ├── campaign/        # 活动工作流、投递、追踪与合作方
│   │   ├── outreach/        # 单次、批量与每周编排
│   │   ├── identity/        # 认证、RBAC 与用户
│   │   ├── email/           # 模板、资源、SMTP 与邮箱协议
│   │   └── analytics/       # 聚合与导出
│   └── src/main/resources/db/migration/
├── worker/src/app/          # arXiv、提取与个性化 Worker
├── frontend/src/            # Vue 模块、组件、布局与路由
├── infra/                   # Nginx 与数据库初始化
├── scripts/                 # 验证与运维工具
├── docs/                    # 架构、API、安全与运行手册
├── docker-compose.yml       # 生产形态基线
└── docker-compose.dev.yml   # 本地开发覆盖
~~~

## 安全与运维边界

- 使用真实发件身份、准确研究目的、经过授权的受众，并遵循适用隐私与反垃圾邮件要求。公开联系方式本身不等于同意接收邮件。
- 模型输入不包含收件人邮箱字段或 SMTP 凭据；可选合作方激活另有外部数据传输边界。
- 联系人值保持加密，普通视图保持脱敏。不要提交凭据、作者联系清单、运营账本、冻结邮件正文或已签发的能力链接。
- `SMTP_ACCEPTED` 仅表示 SMTP 服务器接受，不代表进入收件箱。图片和点击事件可能来自代理、预取或扫描器；没有事件也不能证明未读。
- 保留 `UNKNOWN` / `OUTCOME_UNKNOWN`。应按原操作对账，不能把不确定结果当成再次发送的许可。
- arXiv 访问使用官方主机与共享请求间隔，Source 不执行、不作为业务资源长期保存。Source admission 与结果发布还有独立部署兼容门禁。
- 仅草稿模板迁移不会审核、启动、恢复或重发活动，必须保留历史尝试、回传与原始对账键。
- 已有部署在替换或回滚前，需要证明停止接纳新工作、完成在途工作、保留备份，并核验 API、Worker、结果协议兼容性。数据库迁移仅新增结构或本地测试通过，不等于安全重启证明；不能把实验中的排空代码当作运维门禁。
- 公网部署需要 TLS 和网络控制。本地 Compose Kafka 不是经过公网加固的 broker，开发覆盖不得作为生产服务暴露。

## 文档导航

大部分子系统详细指南目前以中文为主，本仓库总览维护中英文两个版本。

| 指南 | 内容 |
| --- | --- |
| [系统架构](docs/ARCHITECTURE.md) · [ERD](docs/ERD.md) | 组件、数据流与关系模型。 |
| [API](docs/API.md) · [RBAC](docs/RBAC.md) | 请求约定、认证与授权。 |
| [Source 提取](docs/TEX_EXTRACTION.md) | 归档、解析限制与联系人证据。 |
| [分析口径](docs/ANALYTICS.md) | 指标定义、筛选、新鲜度与导出。 |
| [安全与隐私](docs/SECURITY_AND_PRIVACY.md) | 数据分类、密钥、加密与审计边界。 |
| [邮件追踪](docs/EMAIL_TRACKING.md) | 测试邮件、图片与点击结果语义。 |
| [部署](docs/DEPLOYMENT.md) · [运维](docs/OPERATIONS.md) | 环境准备、运行检查与维护。 |
| [活动投递](docs/operations/campaign-delivery-runbook.md) · [SMTP 配额](docs/operations/smtp-quotas.md) | 投递门禁、限制与反馈。 |
| [模板迁移](docs/operations/template-migration-runbook.md) | 仅草稿转移与原键对账。 |
| [Source 发布](docs/operations/source-publication-runbook.md) | 接纳、结果处理、重试与兼容发布。 |
| [邮件夹具](docs/email/README.md) · [准备诊断](docs/email/preparation-diagnostics.md) | 合成邀约示例与受限失败证据。 |
| [组件映射](docs/DESIGN_SKILL_COMPONENT_MAP.md) · [第三方说明](docs/THIRD_PARTY_NOTICES.md) | UI 来源与授权边界。 |

## 参与贡献

欢迎提交 [Issue](https://github.com/MRNOBODY-ZST/CaMelArxivAdv/issues) 或聚焦单一目标的 Pull Request，并附对应测试与文档更新。数据库变更保持追加式，保留不可变历史，说明协议或运维兼容性影响。

测试与文档使用合成夹具，排除真实联系人、凭据、私有运营记录和构建产物。修改安装步骤或安全要求时，请同步维护中英文 README。

## 许可证

[MIT](LICENSE) © 2026 Hades。

项目包含基于商业授权 Tailwind Plus 材料的应用内适配，不作为可独立再分发的组件或模板包提供。详见[第三方说明](docs/THIRD_PARTY_NOTICES.md)。
