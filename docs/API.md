# API 约定

## 模板迁移与准备诊断

`/api/v1/campaigns/{campaignId}/template-migrations` 的 `POST /preview` 和 `POST` 同时要求 `campaign:read`、`campaign:create`；`GET /by-key?key=…` 与 `GET /{migrationId}` 要求读取权限及原 Actor 范围。请求严格拒绝未知/重复字段、重复收件人及超限集合；预检提交精确 recipientIds、目标模板/不可变版本，提交另带双方 expected lock version、服务端 membership digest、原 idempotencyKey。单来源最多 120 位；不接受客户端数量代替名单。

结果 `MIGRATED/CONFIG_UPDATED/NO_MAIL/BLOCKED` 不代表发送、批准或排期。未知结果只读回原键，不刷新令牌重放 POST。前端跨页选择和持久恢复详情见[迁移 runbook](operations/template-migration-runbook.md)。准备诊断只公开受限分类/固定码/trace reference；缺少历史诊断保持 `ROOT_CAUSE_UNKNOWN`，不提供原异常或隐私正文。

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

### 持久化自动论文推广

| 方法与路径 | 权限 | 说明 |
|---|---|---|
| `GET /api/v1/outreach-runs/options` | `campaign:read` | 不含密钥的 SMTP / 模板概要、数量上限及生产 hold |
| `GET /api/v1/outreach-runs` | `campaign:read` | 标准 `PageResponse` 分页，默认 `page=1&pageSize=20` |
| `GET /api/v1/outreach-runs/{id}` | `campaign:read` | 持久运行状态；浏览器关闭或 API 重启不会丢失 |
| `POST /api/v1/outreach-runs` | 同时具有 `paper:import`、`campaign:create`、`campaign:approve`、`campaign:send` | 显式确认后创建；相同用户+幂等键+载荷返回原运行，改变载荷返回 409 |
| `POST /api/v1/outreach-runs/{id}/cancel` | `campaign:pause` | 请求 `{"expectedLockVersion":0}`；旧版本/终态返回 409；正常取消已运行活动，不能撤回已接受或在途邮件 |

创建 JSON 示例：

```json
{
  "name":"论文邀请",
  "targetMode":"EMAILS",
  "targetCount":100,
  "smtpAccountId":"00000000-0000-0000-0000-000000000001",
  "templateId":"00000000-0000-0000-0000-000000000002",
  "purpose":"研究合作邀请",
  "criteria":{"categoryIds":["cs.AI"],"titleKeywords":"agents","page":1,"pageSize":100},
  "confirmation":"AUTO_OUTREACH_SEND",
  "idempotencyKey":"client-generated-unique-key"
}
```

`EMAILS` 目标 1–120 个唯一可发送地址，搜索预算最多 100 篇；不足进入 `BLOCKED`，不自动无限补搜。`PAPERS` 目标 1–100 篇，不要求每篇有联系人；地址超过 120 时阻塞而不裁剪。结构化 `criteria` 与现有 arXiv 搜索相同；相同条件+上限沿用现有幂等导入结果。实际论文清单只读取对应导入的 `paper_imports(job_id)`，不使用全库搜索或未完成的 job items。

创建返回直接 `RunView` JSON（201），字段为 `id,name,targetMode,targetCount,status,stage,message,paperCount,extractedCount,eligibleCount,selectedCount,smtpAccepted,failedCount,unknownCount,campaignId,importJobId,extractionJobId,smtpAccountId,templateId,lockVersion,createdAt,updatedAt,allowedActions`。`ACTIVE/SENDING` 可取消；`BLOCKED/FAILED/CANCELED/COMPLETED` 为终态。阶段为 `IMPORT,WAIT_IMPORT,EXTRACT,WAIT_EXTRACTION,PREPARE,REVIEW,APPROVE,START,SENDING,DONE`。配额等待仍为 `SENDING`；`smtpAccepted` 仅说明服务器接受，不能表示收件箱投递。

Options JSON 为 `senders:[{id,name,fromEmail,replyToEmail,available,message}]`、`templates:[{id,name,currentVersion,available,message}]`、`limits:{maxEmails:120,maxPapers:100,searchPaperBudget:100}` 与 `productionHoldReason:string|null`。发件选项要求 SMTP 启用且最近测试 `SUCCEEDED` 并仍对应当前配置，同时自动匹配启用、当前测试成功、用户名等于 Reply-To 的 IMAP 监控邮箱。响应不包含主机、用户名、密码或加密字段。

创建冻结模板 version ID、实际 From / Reply-To 与 SMTP / IMAP 配置版本。自动推进前重新检查账号实时权限；配置变化、已完成提取失败/不安全/部分结果、已有非本运行提取、联系人数量或机器内容摘要不一致均阻塞，不假装“无邮箱”，不盲重放。正常 `submitReview → approve → start` 每步先验证机器内容审核与 `TOTAL=ELIGIBLE=selectedCount`，并读取最新活动锁版本。`app.outreach.production-hold-reason` 默认为空；非空只阻止启动发送，绝不自动清除。

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

### 邮件模板、私有图片与邮件协议账户

模板读取需要 `template:read`，修改需要 `template:manage`；SMTP 读取/修改分别需要 `smtp:read`/`smtp:manage`，IMAP/POP3 读取/修改分别需要 `mailbox:read`/`mailbox:manage`。模板测试发送同时要求 `template:manage` 与 `smtp:manage`。

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
| `GET /api/v1/mail-tracking/status` | 返回测试邮件回传开关、回调 origin、`LOCAL_ONLY`/`PUBLIC_HTTPS_CONFIGURED` 与 token TTL |
| `GET /api/v1/mail-send-records` | 分页返回脱敏测试邮件发送结果及图片/点击聚合；需要 `smtp:read` |
| `GET /api/v1/mail-send-records/{id}` | 返回记录、最多 50 条图片事件、按位置最多 100 条服务器保存的链接汇总及最多 50 条点击事件；不返回 token |
| `GET|HEAD /t/o/{token}` | 匿名图片回传；`HEAD` 不计数 |
| `GET|HEAD /t/c/{token}` | 匿名签名点击重定向；目标只取自服务器保存映射，`HEAD` 不计数 |
| `GET/POST /api/v1/mailbox-accounts` | 分页读取或创建 IMAP/POP3 账户；只返回 `passwordConfigured` |
| `GET/PUT/DELETE /api/v1/mailbox-accounts/{id}` | 读取、乐观更新或删除邮箱账户；更新密码为 `null` 表示保留 |
| `POST /api/v1/mailbox-accounts/{id}/test-connection` | 验证协议、TLS 与认证，返回稳定错误类别 |
| `GET /api/v1/mailbox-accounts/{id}/messages?limit=20` | 以只读方式返回有界邮件头预览；发件人脱敏，不读取正文或附件 |

模板仅允许 `author_name`、`first_name`、`paper_title`、`arxiv_id`、`primary_category`、`paper_url`、`organization` 和 `unsubscribe_url`。文本变量按文本转义；HTML 属性只允许完整的 URL 变量，且渲染值必须是无 user-info 的绝对 HTTP(S) URL。脚本、事件处理器、iframe、`javascript:`、危险 data URL、未知/畸形变量会被移除或拒绝。开启 `autoGenerateText` 时，状态随模板版本持久化，纯文本会从净化 HTML 重建并保留安全链接目标。

资产签名 URL 只授权读取一张仍属于未归档模板的图片，不授予模板 API 权限。签名使用独立运行时密钥且不会包含 MinIO 对象键，边缘代理不会把带签名的查询串写入 access log；测试邮件渲染时会把有效的相对签名路径转换为 `PUBLIC_BASE_URL` 下的绝对 URL。复制模板会深拷贝被引用的图片并为副本重签，源模板归档后副本不受影响。模板主题和发件人名称会在变量替换后再次校验最终长度与控制字符，避免样例值绕过 Header 边界。

`ALLOW_LIVE_SMTP` 与 `ALLOW_PUBLIC_MAILBOX` 分别控制公网 SMTP 和 IMAP/POP3。`PLAIN_LOCAL_ONLY` 只用于精确匹配的本地白名单；公网目标必须使用要求证书与主机名校验的 STARTTLS 或隐式 TLS。邮箱预览只以 `READ_ONLY` 打开文件夹，不删除邮件、不更改 flag，也不返回正文或附件。

### 指定论文生成与多邮箱安全实流

`POST /api/v1/campaigns/{id}/personalizations` 需要 `campaign:create`。不带请求体时保留原分群生成行为；指定论文时接受：

```json
{"paperIds":["615e263f-0041-48f6-9331-7a3e909344c6"]}
```

指定列表须为 1–20 个不同 UUID，且不超过运行时生成上限。每篇论文必须在活动分群中存在合格联系人；服务为每篇分配不同邮箱的联系人，共同作者不会掩盖其他候选。论文缺失、联系人不足或现有活动收件人与本次选择不一致时，事务整体拒绝，不静默缩小批次。选文清单与首次排队在同一事务中持久化；之后省略请求体仍复用该清单，不扩大到整个分群。更换选文须新建草稿。新建活动第一次生成应核对响应 `queuedRecipients` 等于所选论文数；相同选择的 HTTP 失败重试只重新排队失败项，已生成内容保留。活动仍须是 `DRAFT`。

### 固定人数的正式批次生成

新的空白 `DRAFT` 活动可显式请求恰好 N 位作者，仍使用 `POST /api/v1/campaigns/{id}/personalizations`：

```json
{"recipientLimit":120}
```

`recipientLimit` 必须是 1–120 的 JSON 整数，与 `paperIds` 互斥；字符串、浮点数、重复或未知字段均拒绝。此显式正式模式不受旧分群生成默认数量（例如 20）限制，但不会改变旧接口、指定论文最多 20 篇或安全实流最多 20 封的限制。

首次选择必须恰好 N 个不同 `author_id` 和不同邮箱 HMAC，满足活动分群规则和原 Prompt 的证据门槛：最新成功提取中的 `HIGH`，或同时满足 `human_verified=true`、`verification_status=CONFIRMED` 的 `MEDIUM`。两者都必须有有效作者关系与可追溯源码证据，`REJECTED` 始终排除；还需有效、未退订、未抑制的联系人及完整论文公开输入。`HIGH` 不再额外要求人工确认；自动源码审核不会伪写人工标记，真正的位置对应或共享邮箱也不会批量提升为 `HIGH`。已触发冷却、正在发送或发送结果未知的地址，以及全部已配置安全自测邮箱（默认与命名白名单）均排除。查询最多检查 12,000 条合格作者/邮箱关联；无法选足 N 时整体拒绝，零新增 recipient、零生成 outbox，不以不合格联系人补足。分群规则仍可进一步缩小范围：旧的 `HIGH + CONFIRMED` 分群不会因恢复基础策略而自动扩大，应通过正常接口创建相应新分群和草稿。

名单和联系人、作者、论文、加密邮箱、公开论文输入快照与首次排队在同一事务冻结；活动行锁防止并发请求改变模式。重试可省略请求体或重复相同 `recipientLimit`，仅重排 `PENDING` / `FAILED`，`GENERATED` 不重新生成，后续新增联系人和改动的论文内容不会加入或替换原快照。重试排队前仍检查所有冻结收件人的最新资格：原始证据门槛、有效作者关系、未删除的论文／联系人、证据、抑制／退订／排除、冷却和未决发送状态；有任意失效则整体回滚，不能靠旧快照重新放行。更改数量/模式、将已有旧批次改为正式模式、损坏或缺失快照时必须新建草稿。首次响应 `queuedRecipients` 必须等于 N；重试响应是本次待生成数。

正式生成命令的完整 UTF-8 序列化大小不得超过 900 KiB（不是字符数）；超限时同样整体回滚，需在新草稿请求较小批次，避免消息进入 Kafka 后因尺寸过大卡住。此限制不更改旧生成模式或 Kafka 配置。

此调用仅生成草稿，不审批或发送。随后仍需原有内容审核、发件配置、正式预检、审批和启动；真正启动前应检查 `ELIGIBLE = TOTAL = N`。生成后新发生的退订、抑制、证据失效和冷却仍由预检及发送 worker 拦截，不会为了凑满 N 自动换人。N 表示冻结目标数，不代表保证 N 封送达。

### 论文公开联系人模式（不按映射置信度筛选）

新的空白 `DRAFT` 可使用第三种互斥选择器：

```json
{"paperContactIds":["615e263f-0041-48f6-9331-7a3e909344c6"]}
```

接受 1–100 个不同 UUID；禁止空值、强制类型转换、重复或未知字段，以及与 `paperIds` / `recipientLimit` 混用。该显式模式以论文清单代替分群筛选，选择论文的全部合格公开邮箱，按邮箱 HMAC 去重；结果为 0 或超过 120 时整体拒绝，不截断。响应仍为 `{jobId, queuedRecipients}`。论文没有合格邮箱时不会换入清单外论文。

首次生成原子冻结 `recipient_policy=PAPER_CONTACT`、`paper_contact_ids`、`paper_contact_recipient_count`（1–120）、原始置信度及公开输入快照；重试时冻结成员数量改变也整体拒绝。旧活动默认 `AUTHOR_VERIFIED`，原选择与发送门槛不变；旧模式不允许空作者身份生成命令。该模式允许 HIGH / MEDIUM / LOW / UNMAPPED，且不要求作者映射或人工确认，但必须有未删除论文、最新成功提取关联和真实提取证据；REJECTED、无效／示例／删除邮箱、退订、抑制、活动排除、180 天冷却、发送中／未知结果和安全自测邮箱仍不可进入。跨活动选择使用事务级 advisory lock；其他未结束的 PAPER_CONTACT 活动中 QUEUED / TEMPORARY_FAILURE 邮箱仍被预留。已完成历史不形成永久预留，但仍执行正常冷却；任何活动的 CONNECTING / OUTCOME_UNKNOWN 始终阻止重复选择。

重试必须重复相同 `paperContactIds`，只生成原冻结集合中的 PENDING / FAILED；已 GENERATED 不重做，新增联系人或改变后的论文输入不替换旧快照。任一冻结成员失效或已有发送尝试则整体失败。改变模式或清单必须新建草稿。预检指纹包含策略和论文清单；发送阶段仍在原子预留流程内复查实际证据和所有安全限制。

仅直接 HIGH 证据或真实人工确认的作者映射可用于具名问候。新证据不再支持原冻结姓名时，重试、预检和发送均拒绝旧具名内容；原本中性的联系人仍不受置信度门槛限制。其他情况生成目标的 `authorName` 显式为 null，AI 公开输入不含 author 身份；研究邀请使用 `Hello research team,`，中性引用论文，不宣称收件人是作者。空白作者名仍无效；AI 输入不含收件邮箱或凭据，退订占位符与 HTML 转义保持不变。

### 已生成草稿的逐封内容校正

`PUT /api/v1/campaigns/{id}/recipients/{recipientId}/content` 需要 `campaign:create`，由已登录操作者提交：

```json
{
  "expectedLockVersion": 1,
  "subject": "A research collaboration idea",
  "html": "<p>Research-specific invitation. <a href=\"{{unsubscribe_url}}\" rel=\"noopener noreferrer\">Unsubscribe</a></p>",
  "text": "Research-specific invitation. Unsubscribe: {{unsubscribe_url}}",
  "rationale": "The proposal is grounded in the frozen paper abstract.",
  "reason": "Correct an unsupported experimental claim before campaign review."
}
```

仅允许 `DRAFT` 活动，生成状态须为 `COMPLETED`、`PARTIALLY_FAILED` 或 `FAILED`；目标须为同一活动内已 `GENERATED`、尚未开始发送的 `QUEUED` 收件人。版本冲突、发送尝试／租约、Message-ID、追踪／退订／安全自测／收信关联记录均会阻止修改。活动与收件人加锁，并在同一事务写入内容、递增活动版本、清除旧审批摘要与审批标记、记录操作者／原因和修改前后内容 SHA-256。审核日志不保存整封邮件或邮箱正文。

主题、HTML、纯文本和理由一起校验／更新；保留唯一的 `unsubscribe_url` 模板变量，继续采用模板引擎的 HTML、邮件头和字节数限制。不会改动作者、论文、邮箱与公开输入快照，也不会改动 AI provider/model/job、原生成时间或生成尝试次数。响应包含 `campaignId`、`recipientId`、新 `lockVersion`、校验后的四项内容和 `updatedAt`。修改后必须重新预检、审批和启动；本接口不发送邮件，也不写入联系人“人工核验”标记。

主题不得包含退订占位符或任何 ISO 控制字符；HTML 和纯文本必须各自含精确 `{{unsubscribe_url}}`。提交 HTML 应保留模板引擎输出的规范格式；如果安全过滤仍需移除或改写输入内容，接口会拒绝而非悄悄改写后保存。

### 多邮箱安全实流

`POST /api/v1/campaigns/{id}/safety-runs` 需要 `campaign:send`，接受：

```json
{
  "expectedLockVersion": 1,
  "recipientLimit": 20,
  "confirmation": "SAFETY_REDIRECT",
  "destinationKey": "qq"
}
```

省略 `destinationKey` 或指定 `default` 使用原 `CAMPAIGN_SAFETY_RECIPIENT`；`qq` / `gmail` 分别使用运行时 `CAMPAIGN_SAFETY_RECIPIENT_QQ` / `CAMPAIGN_SAFETY_RECIPIENT_GMAIL`。空配置不会启用目标。请求不能直接提供邮箱、多个目标或未知字段；未知目标在创建运行前拒绝。白名单只配置所有者明确提供的测试邮箱，不猜测地址。

同一活动同时只允许一个活跃安全实流。20 篇分别测试三个邮箱时，依次创建三个 20 份运行，共 60 封；每封拥有独立邮件 ID 和回传 token，并按实际目标域名及同一 SMTP 配额计数。不要复制已渲染 MIME，也不要临时轮换原固定收件地址；须保留旧地址和仍需验证历史回传的白名单地址。安全实流不会审批或启动正式活动，不修改真实联系人的退订状态。SMTP 接受、外部收件箱收到、自然打开和合成回传是不同验证结果。

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

## 个性化模型设置

管理页 `/admin/settings` 和以下接口需要 `system:manage` 权限：

| 方法与路径 | 说明 |
| --- | --- |
| `GET /api/v1/system/personalization` | 读取当前生效的配置及版本；仅返回 `keyConfigured`，不返回密钥 |
| `PUT /api/v1/system/personalization` | 加密保存配置；保存后新生成任务立即生效，无需重启 Worker |
| `POST /api/v1/system/personalization/test` | 使用表单草稿执行小额模型连接测试，不保存草稿、不发送邮件 |

保存与测试请求包含 `version`、`enabled`、`provider`（`openai` / `anthropic`）、`model`、`apiOrigin`、`authScheme`（`bearer` / `x-api-key`），以及可选的只写字段 `apiKey`。

- 初次读取使用环境配置（`source=ENVIRONMENT`）；保存后使用数据库配置（`source=DATABASE`）。
- `apiOrigin` 必须为公网 HTTPS，不接受用户信息、查询参数或片段；裸域名规范为 `/v1`，已有路径前缀保留。实际连接再次检查 DNS，拒绝私网地址和重定向。
- `apiKey` 留空仅在服务商及规范化地址未改变时保留旧密钥；更换服务商或地址必须提供新密钥。未配置密钥时，可以保存相同目标的停用配置，但不能通过连接测试或启用生成。
- 保存成功返回安全配置及递增版本。过期版本返回 `409`，前端要求显式重新加载，不自动重试覆盖他人修改。
- 连接测试返回 `{success,message,provider,model,latencyMs}`；错误提示不包含供应商原始响应或密钥。测试成功不代表已保存。
- 每个新生成任务在入队事务中冻结独立的加密配置快照；后续修改配置不会改变已创建任务的供应商、模型或密钥。

内部 `GET /internal/personalization/jobs/{jobId}/configuration` 只允许独立服务 Bearer Token，管理员 JWT 不能替代它。接口不通过公开 Nginx 代理暴露，响应为 `Cache-Control: no-store`；无快照返回 `404`，Worker 不回退到当前环境配置。密钥只在内部任务配置传输中提供给执行 Worker，不进入 Kafka 消息或 Ray 调用参数、返回值。

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

Kafka 信封固定包含：

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

Kafka 使用显式创建的 `camel.arxiv.{jobs,results,retry,dlt}.v1` 与 `camel.mail.personalization.{jobs,results,retry,dlt}.v1` 八个主题；自动建主题关闭。命令类型保留在信封与 Header 中，结果和 `WORKER_HEARTBEAT` 进入对应 results 主题。生产者启用幂等与 `acks=all`，消费者手工提交 offset；重试先写 retry 主题，达到上限或永久约束错误写 DLT。元数据批次最多 100 条，分类最多 500 条；分类内容随 `ARXIV_JOB_COMPLETED` 传输，使 active snapshot 与成功终态在一个数据库事务中提交。

Phase 4 增加命令 `arxiv.extract.source` 和结果 `ARXIV_EXTRACTION_RESULT`。命令只含 Job、解析器版本及有界 `{paperId, arxivId}` 目标，不含 URL；结果只含 Source 状态/格式/尺寸、检查文件数、作者/机构、规范化联系人、置信度和脱敏证据，不含归档或 TeX 正文。API 在每篇结果事务中写提取运行、加密联系人、映射、证据、任务项、事件与幂等标记；只有全部任务项结果已持久化且计数一致，`ARXIV_JOB_COMPLETED` 才能提交任务终态。

## 追踪入口预留

Nginx 将 `/t/` 转发后端。后续打开像素和点击 Token 必须签名、限时、不可枚举；点击目标只能来自活动快照中的已验证 URL，禁止把任意查询参数直接作为重定向地址。
