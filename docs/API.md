# API 约定

## 基础约定

- 前缀：`/api/v1`
- 内容：JSON，UTF-8，字段使用 lower camel case。
- 时间：RFC 3339 UTC，例如 `2026-08-05T13:45:22Z`。
- 分页：列表统一 `page`（从 1 开始）与 `pageSize`（最大 100），响应返回 `items`、`page`、`pageSize`、`total` 和 `totalPages`。
- 追踪：接受 `X-Trace-Id`，无值时生成；响应头和错误体均返回同一 `traceId`。
- OpenAPI：`/api/openapi.json`；Swagger UI：`/api/docs`。

## 公开端点

### `GET /api/v1/system/health`

无需认证，用于前端和部署验收。

```json
{
  "status": "UP",
  "checkedAt": "2026-08-05T13:45:22.916688467Z"
}
```

Actuator readiness/liveness 只用于容器探针，不应作为业务 API 扩展。

### 认证入口

`POST /api/v1/auth/login` 接受 `{"principal":"用户名或邮箱","password":"..."}`。成功响应包含 10 分钟 Access Token 和当前用户，同时通过 `Set-Cookie` 写入 refresh；refresh 原值不会进入 JSON。

`POST /api/v1/auth/refresh` 仅使用 refresh Cookie，成功时轮换 Cookie 并返回新的 Access Token。已轮换值再次出现时返回 401，并撤销同一 token family。

`POST /api/v1/auth/logout` 撤销当前 refresh family 并清除 Cookie。即使 Cookie 已缺失，调用也保持幂等。

## 已认证端点

### 当前账号

- `GET /api/v1/auth/me`：返回数据库中的实时账号、角色、权限和 `mustChangePassword`，不返回邮箱或凭据。
- `POST /api/v1/auth/change-password`：接受 `currentPassword`、`newPassword`；成功返回 204，使当前 Access Token 与全部 Refresh Token 立即失效。

初始/重置密码会令 `mustChangePassword=true`，完成改密前业务权限数组为空。

### 用户管理

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/users` | `user:read` | `search`、`status`、`page`、`pageSize` 筛选 |
| `POST /api/v1/users` | `user:create` | 创建账号、初始密码和角色，强制首次改密 |
| `PUT /api/v1/users/{id}` | `user:update` | 更新邮箱、显示名和角色 |
| `POST /api/v1/users/{id}/disable` | `user:disable` | 停用并使会话失效 |
| `POST /api/v1/users/{id}/enable` | `user:disable` | 启用账号 |
| `POST /api/v1/users/{id}/reset-password` | `user:update` | 重置密码、强制改密并使会话失效 |

用户响应不包含密码哈希或 refresh 数据。只有有效 `SUPER_ADMIN` 能创建、编辑、重置或启停 `SUPER_ADMIN` 账号；最后一个有效 `SUPER_ADMIN` 不允许被停用或剥离角色。

### 角色、权限和审计

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/roles` | `role:read` | 角色、用户数及权限集合 |
| `GET /api/v1/permissions` | `role:read` | 26 项只读权限目录 |
| `POST /api/v1/roles` | `role:manage` | 创建自定义角色 |
| `PUT /api/v1/roles/{id}` | `role:manage` | 更新名称、说明和授权 |
| `DELETE /api/v1/roles/{id}` | `role:manage` | 删除未使用的自定义角色；系统角色不可删除 |
| `GET /api/v1/audit-logs` | `audit:read` | 支持 `from`、`to`、`actorId`、`action`、`resource`、`result` 和分页筛选 |

### arXiv 发现、同步与保存查询

官方协议依据 [arXiv API manual](https://info.arxiv.org/help/api/user-manual.html)、[OAI-PMH harvester notes](https://info.arxiv.org/help/oa/index.html) 和 [category taxonomy](https://arxiv.org/category_taxonomy) 实现。

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/arxiv/taxonomy` | `paper:read` | 返回当前快照版本、来源、同步时间以及 Group/Archive/Category 树；上游不可用时仍读取内置快照 |
| `POST /api/v1/arxiv/taxonomy/sync` | `system:manage` | 幂等创建 OAI `ListSets` 同步任务和 Outbox 消息，返回 202 与 Job ID |
| `POST /api/v1/arxiv/search/preview` | `paper:read` | 规范化条件并预览官方 Legacy API 结果；返回查询哈希、缓存状态、官方/平台派生筛选说明和分页结果 |
| `GET /api/v1/arxiv/saved-searches` | `paper:read` | 仅列出当前所有者的保存查询 |
| `GET /api/v1/arxiv/saved-searches/{id}` | `paper:read` | 读取当前所有者的规范化查询 |
| `POST /api/v1/arxiv/saved-searches` | `paper:import` | 保存唯一名称、规范化条件与 SHA-256 查询哈希 |
| `PUT /api/v1/arxiv/saved-searches/{id}` | `paper:import` | 更新所有者自己的保存查询 |
| `DELETE /api/v1/arxiv/saved-searches/{id}` | `paper:import` | 软删除所有者自己的保存查询 |
| `POST /api/v1/arxiv/imports` | `paper:import` | 按选中 arXiv ID 或规范化条件+上限创建元数据导入任务 |
| `POST /api/v1/arxiv/oai/sync` | `paper:import` | 按有效官方 set 和可选 `from` 日期创建 OAI-PMH 增量同步任务 |

预览条件支持分类、Primary/Cross-list 模式、submitted/updated 日期、标题/摘要/作者关键词、DOI/期刊引用存在性、Source 状态、排序、页码和有界页大小。原始 Legacy 查询片段不作为输入；相同规范化条件共享缓存和查询哈希。

导入请求必须二选一：

```json
{"arxivIds":["2212.02256","2608.00001v2"]}
```

或：

```json
{
  "criteria":{"categoryIds":["cs.AI"],"titleKeywords":"reliable agents"},
  "maxPapers":500
}
```

### 任务与论文库

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/jobs` | `paper:read` | 按 `status`、`type` 分页；返回阶段、计数、进度、心跳新鲜度和允许动作 |
| `GET /api/v1/jobs/{id}` | `paper:read` | 轮询回退和任务详情 |
| `GET /api/v1/jobs/{id}/events` | `paper:read` | 以 `afterId`、`limit` 回放持久事件 |
| `GET /api/v1/jobs/{id}/stream` | `paper:read` | SSE；使用 `Last-Event-ID` 回放遗漏事件，终态后结束 |
| `POST /api/v1/jobs/{id}/pause` | `job:manage` | 幂等请求协作式暂停 |
| `POST /api/v1/jobs/{id}/resume` | `job:manage` | 恢复暂停任务 |
| `POST /api/v1/jobs/{id}/cancel` | `job:manage` | 在下一官方请求前协作式取消 |
| `POST /api/v1/jobs/{id}/retry` | `job:manage` | 从终态创建新 execution lineage，并在同一事务写入使用新 Job/Message/幂等键的 Outbox 命令 |
| `GET /api/v1/papers` | `paper:read` | 数据库侧筛选、稳定排序与分页；支持分类、日期、标题、作者、Source、DOI、期刊引用 |
| `GET /api/v1/papers/{id}` | `paper:read` | 返回元数据、通讯作者标记、分类、版本、导入来源、Source 状态、提取运行和已清理原始元数据 |
| `POST /api/v1/papers/{id}/extract` | `paper:import` | 为一篇已导入论文创建 Source 下载/解析任务，返回 202 与 Job ID |
| `POST /api/v1/papers/batch-extract` | `paper:import` | 接受唯一 `paperIds` 数组并创建有界批量任务；缺失、重复或存在并发非终态任务时原子拒绝 |

批量请求示例：

```json
{"paperIds":["615e263f-0041-48f6-9331-7a3e909344c6"]}
```

### 联系人与证据

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/contacts` | `contact:read_masked` | 按 `domain`、`confidence`、`verificationStatus`、`corresponding`、`paperId` 和分页筛选；邮箱始终脱敏 |
| `GET /api/v1/contacts/{id}` | `contact:read_masked` | 返回最新映射与截断脱敏证据；默认不解密完整地址 |
| `GET /api/v1/contacts/{id}?full=true` | `contact:read_full` | 显式单条解密；成功后写 `CONTACT_EMAIL_DISCLOSED` 审计 |
| `PATCH /api/v1/contacts/{id}/verification` | `contact:verify` | 以 `mappingId`、`expectedVersion` 和 `CONFIRMED`/`REJECTED` 做乐观并发更新并审计 |

验证请求示例：

```json
{
  "mappingId":"0bb1cd2a-5a5d-4492-95b6-3f4fb3eb92fe",
  "expectedVersion":0,
  "status":"CONFIRMED"
}
```

机器提取的联系人默认 `UNVERIFIED`。API 不支持批量完整邮箱披露；列表、Job 事件和错误体不会返回完整地址。

### 邮件模板、私有图片与 SMTP

模板读取需要 `template:read`，修改需要 `template:manage`；SMTP 读取/修改分别需要 `smtp:read`/`smtp:manage`。模板测试发送同时要求 `template:manage` 与 `smtp:manage`。

| 方法与路径 | 说明 |
|---|---|
| `GET /api/v1/templates` | 分页列出未归档模板及当前不可变版本 |
| `GET /api/v1/templates/{id}` | 读取当前模板、验证结果、自动文本模式和乐观锁版本 |
| `POST /api/v1/templates` | 净化并创建模板及版本 1 |
| `PUT /api/v1/templates/{id}` | 使用 `expectedLockVersion` 创建新的头版本；不覆盖历史 |
| `POST /api/v1/templates/preview` | 用显式样例值执行服务端净化、上下文校验和确定性预览 |
| `GET /api/v1/templates/{id}/versions` | 按新到旧读取不可变版本 |
| `POST /api/v1/templates/{id}/versions/{version}/restore` | 将历史内容恢复为新的最新版本 |
| `POST /api/v1/templates/{id}/copy` | 复制为独立草稿 |
| `DELETE /api/v1/templates/{id}?expectedLockVersion=...` | 未被活动引用时软归档 |
| `POST /api/v1/templates/{id}/test-send` | 通过指定 SMTP 账户向一个显式测试地址发送 multipart/alternative；只返回 `SMTP_ACCEPTED` |
| `GET/POST /api/v1/templates/{templateId}/assets` | 列出或上传私有 PNG/JPEG/GIF/WebP；最大 5 MiB，并校验 magic signature |
| `GET /api/v1/templates/{templateId}/assets/{assetId}/content` | 经模板读取权限授权后返回私有对象内容 |
| `GET /api/v1/template-assets/{templateId}/{assetId}/content?signature=...` | 校验绑定模板/资产 UUID 的 HMAC 后返回图片；供编辑器沙箱和内部测试 MIME 使用 |
| `DELETE /api/v1/templates/{templateId}/assets/{assetId}` | 仅当所有不可变版本均未引用时删除对象和元数据 |
| `GET/POST /api/v1/smtp-accounts` | 分页读取或创建账户；响应只有 `passwordConfigured`，不返回密码/密文/nonce |
| `GET/PUT/DELETE /api/v1/smtp-accounts/{id}` | 读取、乐观更新或删除未被活动引用的账户；更新密码为 `null` 表示保留 |
| `POST /api/v1/smtp-accounts/{id}/test-connection` | 验证握手并返回 `CONNECTION_SUCCEEDED` 或稳定错误类别 |
| `POST /api/v1/smtp-accounts/{id}/test-email` | 发送一封内部诊断邮件并返回 `SMTP_ACCEPTED` |

模板仅允许 `author_name`、`first_name`、`paper_title`、`arxiv_id`、`primary_category`、`paper_url`、`organization` 和 `unsubscribe_url`。文本变量按文本转义；HTML 属性只允许完整的 URL 变量，且渲染值必须是无 user-info 的绝对 HTTP(S) URL。脚本、事件处理器、iframe、`javascript:`、危险 data URL、未知/畸形变量会被移除或拒绝。开启 `autoGenerateText` 时，状态随模板版本持久化，纯文本会从净化 HTML 重建并保留安全链接目标。

资产签名 URL 只授权读取一张仍属于未归档模板的图片，不授予模板 API 权限。签名使用独立运行时密钥且不会包含 MinIO 对象键，边缘代理不会把带签名的查询串写入 access log；测试邮件渲染时会把有效的相对签名路径转换为 `PUBLIC_BASE_URL` 下的绝对 URL。复制模板会深拷贝被引用的图片并为副本重签，源模板归档后副本不受影响。模板主题和发件人名称会在变量替换后再次校验最终长度与控制字符，避免样例值绕过 Header 边界。

`ALLOW_LIVE_SMTP=false` 时服务端只允许配置的 Mailpit/本机白名单目标，即使数据库账户标记为启用也不能访问公网 SMTP。`PLAIN_LOCAL_ONLY` 只用于该白名单；真实目标必须使用要求证书/主机名校验的 TLS 模式。

### 数据统计

所有分析端点需要 `analytics:read`，共享 `from`、`to`、`categoryId`、`relation`、`jobId`、`userId`、`domain` 和 `confidence` 查询参数。`from`/`to` 是包含首尾两天的 UTC `papers.imported_at` 日期队列，默认 30 天、最大 10 年。筛选接口中的用户目录仅对同时拥有 `user:read` 的调用者返回；其他分析用户收到空 `users` 数组。

| 方法与路径 | 说明 |
|---|---|
| `GET /api/v1/analytics/overview` | 首页核心指标、每日导入、主分类、Source 漏斗和当前任务 |
| `GET /api/v1/analytics/ingestion` | 查询匹配、采集漏斗、最新状态、耗时分位、错误码和任务吞吐 |
| `GET /api/v1/analytics/papers` | Group/Archive/Category、关系、发表/更新月、作者/版本和 Source 格式 |
| `GET /api/v1/analytics/contacts` | 唯一作者/邮箱、置信度、域名、分类发现率、规则、复用和共同作者 |
| `GET /api/v1/analytics/filters` | 当前日期范围内可用分类、Job、用户、域名和枚举选项 |
| `GET /api/v1/analytics/{view}/export?dataset=...` | 按固定数据集 allowlist 导出聚合 CSV 并写 `ANALYTICS_EXPORT_CREATED` 审计；默认 `dataset=all` |

比率指标固定返回 `value`、`numerator`、`denominator`、`unit` 和 `definition`；零分母返回 0。`freshness.status` 为 `CURRENT` 或 `NO_DATA`，后者的 `dataThrough` 为 `null`。联系人响应/CSV 只包含域名和聚合，不包含完整邮箱。完整数据集名称和口径见 [ANALYTICS.md](ANALYTICS.md)。

## 统一错误

错误不会返回 Java 类名或堆栈。结构：

```json
{
  "type": "validation_error",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request contains invalid fields",
  "instance": "/api/v1/users",
  "traceId": "0123456789abcdef0123456789abcdef",
  "fieldErrors": {
    "email": ["must be a well-formed email address"]
  }
}
```

常用状态：400 参数/状态无效，401 未认证，403 权限不足，404 不存在，409 并发或唯一冲突，422 可识别但不可处理，429 限速，500 未预期错误，503 依赖不可用。

## 安全策略

仅登录、refresh、logout、健康、追踪占位和 API 文档公开；其余请求默认要求 Bearer JWT。受保护请求实时校验数据库账号状态与 `tokenVersion`；管理 API 在 HTTP 边界和控制器方法两个层级校验精确权限代码。

Access Token 只保存在前端内存；Refresh Token 使用 HttpOnly/Secure/SameSite Cookie。连续失败默认在 15 分钟窗口内达到 5 次后触发账号与 IP 双维度限流。API 不在 URL、日志或错误信息中返回 Secret。角色矩阵与失效语义见 [RBAC.md](RBAC.md)。

## 异步任务与消息

长任务 API 返回任务 ID；状态由 `jobs` 状态机管理。`/api/v1/jobs/{id}/stream` 提供 SSE，断线后客户端携带最后事件 ID 续传，无法维持流时每 5 秒轮询任务详情。

RabbitMQ 信封固定包含：

```json
{
  "version": 1,
  "messageId": "uuid",
  "type": "WORKER_HEARTBEAT",
  "jobId": null,
  "idempotencyKey": "heartbeat:worker:timestamp",
  "traceId": "32-hex",
  "occurredAt": "2026-08-05T13:45:22Z",
  "payload": {}
}
```

不支持的 `version` 必须拒绝；消费者只有在幂等写入成功后 ACK。

Phase 3 的 RabbitMQ 拓扑使用 `arxiv.jobs`、`arxiv.results`、`arxiv.retry` 和 `arxiv.dead` 四个 durable topic exchange。命令 routing key 为 `arxiv.import.metadata`、`arxiv.sync.oai`、`arxiv.sync.taxonomy`；结果和 `worker.heartbeat` 均进入 API 结果队列。元数据批次最多 100 条，分类最多 500 条；分类内容随 `ARXIV_JOB_COMPLETED` 传输，使 active snapshot 与成功终态在一个数据库事务中提交。所有消息按数据库列宽做严格版本、大小和字段校验，永久约束错误进入 DLQ。

Phase 4 增加命令 `arxiv.extract.source` 和结果 `ARXIV_EXTRACTION_RESULT`。命令只含 Job、解析器版本及有界 `{paperId, arxivId}` 目标，不含 URL；结果只含 Source 状态/格式/尺寸、检查文件数、作者/机构、规范化联系人、置信度和脱敏证据，不含归档或 TeX 正文。API 在每篇结果事务中写提取运行、加密联系人、映射、证据、任务项、事件与幂等标记；只有全部任务项结果已持久化且计数一致，`ARXIV_JOB_COMPLETED` 才能提交任务终态。

## 追踪入口预留

Nginx 将 `/t/` 转发后端。后续打开像素和点击 Token 必须签名、限时、不可枚举；点击目标只能来自活动快照中的已验证 URL，禁止把任意查询参数直接作为重定向地址。
