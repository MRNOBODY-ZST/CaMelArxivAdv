# 安全与隐私

## 数据分类

| 分类 | 示例 | 存储/输出规则 |
|---|---|---|
| Secret | JWT、HMAC/AES 密钥、Refresh Token、SMTP 凭据 | 仅运行平台 Secret 或不可逆摘要/加密值；禁止日志、URL、前端存储和审计详情 |
| 个人联系数据 | 完整邮箱、人工验证记录 | AES-256-GCM 加密；最小权限、显式披露、披露审计和后续保留策略 |
| 脱敏证据 | 掩码邮箱、规则、相对路径、行号、短上下文 | 可供 `contact:read_masked` 查看；禁止恢复或拼接完整地址 |
| 论文元数据 | 标题、摘要、作者、分类、版本、公开 URL | 按 arXiv 来源和许可展示；保留导入来源与同步时间 |
| Source 临时数据 | TeX、归档、展开文件 | 只在 Worker tmpfs 有界处理，结束后删除，不进数据库/对象存储/消息/日志 |
| 运营/追踪数据 | Campaign、退订、投递和追踪事件 | 必须通过活动审批、抑制、频控、签名和保留策略；Phase 6–8 完成前真实发送关闭 |

## 密钥与加密

生产至少使用四把当前必需且互不相同的 32 字节随机密钥：

- `JWT_SIGNING_KEY_BASE64`：Access Token 签名；
- `AUTH_FINGERPRINT_HMAC_KEY_BASE64`：认证指纹/IP 等敏感值摘要；
- `APP_ENCRYPTION_KEY_BASE64`：联系人 AES-256-GCM；
- `APP_EMAIL_HMAC_KEY_BASE64`：联系人规范化值去重。

后续追踪启用时再单独提供 `TRACKING_SIGNING_KEY_BASE64`。不得把示例值带入生产，不得复用同一次随机输出，也不得提交 `.env`。联系人规范化值和显示值使用不同随机 nonce；解密认证失败必须作为完整性错误处理，不能回退为明文。

轮换联系人加密/HMAC 密钥需要专门的在线迁移：先引入带版本的新密钥材料，批量解密/重新加密和重建 HMAC 唯一值，核对数量后再停止旧版本读取。直接替换环境变量会令既有数据无法解密，不是有效轮换方式。

## 授权与审计

默认拒绝所有业务 API；公开范围仅限登录/刷新/注销、健康、API 文档及后续受签名保护的追踪入口。数据库账号状态和 `tokenVersion` 在受保护请求中实时校验。

- `paper:import`：创建单篇/批量 Source 提取任务；
- `contact:read_masked`：读取脱敏列表和证据；
- `contact:read_full`：显式读取单条完整邮箱；
- `contact:verify`：以乐观版本确认或驳回映射；
- `audit:read`：查看审计事件。

完整邮箱披露和验证变化均记录 Actor、资源、结果、Trace ID、哈希化网络来源与非敏感 before/after。审计详情不能包含邮箱、Token、Cookie、Source 正文或加密密钥。

## 网络与执行隔离

生产 Compose 只发布 Nginx；PostgreSQL、Redis、RabbitMQ、MinIO、API 和两个 Worker 均位于内部网络。容器以专用非 root 用户运行、禁止提权并丢弃 capabilities。Nginx 设置 CSP、frame 拒绝、`nosniff`、严格 Referrer Policy 和权限策略。

arXiv 出站请求只允许固定官方 HTTPS 主机并共享至少三秒的 Redis 租约。Source URL 不能由用户输入；重定向逐跳复验。Python Worker 只通过归档/文本库读取 Source，不调用 shell、TeX 引擎或归档内容。完整限制见 [TeX Source 提取](TEX_EXTRACTION.md)。

## 日志、错误与消息

日志采用结构化非敏感字段和 Trace ID；HTTP 错误不返回类名、堆栈或 Secret。禁止记录 Authorization、Cookie、JWT、Refresh 原值、完整邮箱、SMTP Secret、Source 内容、OAI token 全文或完整 AMQP 帧。

RabbitMQ 信封有版本、类型、大小和字段边界；消费者先验证再处理，只在事务提交后 ACK。永久策略/完整性错误进入 DLQ；只有确认根因并核实消息归属后才能定点重放或清理。

## 数据最小化与保留

平台只采集论文明确公开的邮箱，不猜测、不丰富、不验证 SMTP 可投递性。默认只展示掩码；完整值按最小权限单条披露。Source 原文即时清理，提取证据必须截断并脱敏。

`data_retention_policies` 是后续保留任务的事实配置。Phase 8/9 完成前仍需补齐自动清理执行器和最终法务期限；正式邮件运营前还必须完成退订、抑制、活动快照、审批、频控、投诉/退信回路和隐私告知。`ALLOW_LIVE_SMTP=false` 在这些条件满足前保持强制关闭。

## 事件响应

疑似密钥或联系人泄漏时：立即停止相关 API/Worker 扩容与邮件消费者，撤销会话/受影响 Secret，保留审计和任务证据，按 Trace ID/时间窗确认范围，并执行经过演练的密钥迁移。不要删除审计、任务或抑制记录来“清理”事件，也不要在工单中粘贴敏感原值。
