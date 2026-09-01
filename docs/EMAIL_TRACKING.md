# 测试邮件与互动回传

此功能只覆盖 SMTP 诊断邮件与模板测试发送，不修改活动投递、联系人或活动报表。每次实际发送前创建独立记录，ID 就是原有成功响应中的 `correlationId`。连接测试不发送邮件，因此不创建发送记录。上线前的旧邮件无法补加回传；IMAP 的 Seen 状态也不能证明收件人读过另一封外发邮件。

## 如何理解结果

界面应使用“检测到图片加载”或“链接点击回传”，不能标记为“确认阅读”或“确认人工点击”。没有事件不等于未阅读：收件人可能禁用了图片、使用缓存或离线阅读；转发、图片代理、预取及安全扫描也可能产生事件。Apple 的邮件隐私保护会在后台下载远程内容，即使用户没有与邮件互动。[Apple 官方说明](https://www.apple.com/legal/privacy/data/en/mail-privacy-protection/)

发送结果与图片回传是两条独立事实：

- `SENDING`：记录已创建，发送结果尚未确认。SMTP 一旦开始，其完成和结果落库不再依赖发起请求保持连接；启动时及每分钟会把超过 `TRACKING_STALE_SENDING_AFTER` 的残留状态对账为 `UNKNOWN`，失败类别为 `SEND_OUTCOME_MISSING`。
- `SMTP_ACCEPTED`：SMTP 服务已接受，不代表最终投递或人工阅读。
- `FAILED`：有明确拒绝或发送前连接/认证等失败；之后的 token 请求不计数。
- `UNKNOWN`：超时、断连或其他无法确定结果的传输故障；不要自动重发。

回传可以先于 SMTP 返回，所以 `SENDING`、`UNKNOWN` 也可以记录图片或点击请求，但不会因回传自动变成 `SMTP_ACCEPTED`。已记录的早期回传不会因为后来收到明确失败而被抹除。发送记录及其链接映射在同一事务中创建；任一落库失败时不会尝试 SMTP。SMTP 接受后，账户测试元数据、审计或结果持久化失败不会把响应改为发送失败、改写为 `FAILED` 或触发重试。无法还原的残留 `SENDING` 只会保守变为 `UNKNOWN`，不会猜测 SMTP 是否接受。

## 管理端操作

- 在“邮件账户”的 SMTP 卡片选择“测试邮件”，或在已保存模板的编辑页选择“测试发送”。两处都要求明确填写测试收件人；模板测试还必须明确选择 SMTP 账户。
- “检测图片加载与链接点击（可选）”默认不勾选。为保持 API 兼容，它仍通过当前测试邮件的 `trackOpens=true` 启用两类检测；关闭或重新打开测试对话框会恢复为未勾选。
- 控件会显示当前回传地址及 `LOCAL_ONLY` 或 `PUBLIC_HTTPS_CONFIGURED`。后者只表示配置满足公网 HTTPS 形态；只有列表中的记录确实收到图片或点击事件时，界面才显示“已收到公网回传”。配置无法读取或全局关闭时，只禁用该控件，不会阻止未跟踪的测试发送。
- 成功提示中的“查看测试邮件记录”会打开 `/email/deliveries?record=<correlationId>`。`correlationId` 是该独立记录的 ID，不是活动投递 ID。
- `/email/deliveries` 对具备 `smtp:read` 的用户默认显示“测试邮件记录”；活动发送记录保留在需要 `campaign:read` 的单独标签。只有活动权限的用户不会请求测试邮件记录接口。
- 记录列表显示脱敏收件人、主题、账户、SMTP 结果、检测期、图片加载和点击计数。详情按原始安全目标分组显示链接汇总，以及最近的预取、图片代理、自动化或未分类回传；不会显示或加载 token、像素 URL。

## 配置与范围

| 配置 | 默认值 / 含义 |
| --- | --- |
| `TRACKING_ENABLED` | `false`；全局关闭时，显式要求跟踪返回 400，未跟踪发送仍可进行 |
| `TRACKING_PUBLIC_BASE_URL` | 未设置时回退 `PUBLIC_BASE_URL`，再回退 `http://localhost:8080`；Compose 的空值同样回退 |
| `TRACKING_SIGNING_KEY_BASE64` | 默认空；启用时须为独立随机密钥，至少 32 个解码字节；可分别运行 `openssl rand -base64 32` 生成，切勿复用已有输出 |
| `TRACKING_TOKEN_TTL` | `PT720H`，即 30 天；允许 60 秒至 90 天的整数秒 |
| `TRACKING_STALE_SENDING_AFTER` | `PT15M`；允许 5 分钟至 1 天，超过该年龄的残留 `SENDING` 保守对账为 `UNKNOWN` |

回调地址必须是绝对 origin，不允许用户信息、路径（包括末尾 `/`）、查询或片段。公网主机必须 HTTPS；回环、私网 IP、单标签主机名、`.local` 主机可以 HTTP。应用只验证地址格式和字面主机类型，不访问 URL、不解析 DNS、不从请求 Host 生成地址。

`LOCAL_ONLY` 表示本地/私网配置，即使使用 HTTPS 也不变。`PUBLIC_HTTPS_CONFIGURED` 仅表示配置了非本地主机的 HTTPS，仍需从实际记录观察回传来验证公网 DNS、TLS 与路由。本功能本身不创建隧道或增加端口。外部邮箱的图片代理无法访问 `localhost`；容器中的 `localhost` 也指向容器自身。

每封邮件的 `trackOpens` 默认 `false`；只有明确 opt-in 才在最终 HTML 中增加 1×1 像素，并把最终 HTML 中符合条件的绝对 HTTP(S) 链接改为签名重定向。相同目标复用同一链接记录；相对链接、`mailto:`、含 user-info、过长或已经指向本站 `/t/` 的链接不改写。目标仅由服务器在发送前保存，回调请求不能提供跳转地址，因此不形成开放重定向。纯文本、主题、模板预览及保存版本不变。即使不跟踪，实际发送仍有独立记录。关闭全局开关会停止所有 token 的事件收集；重新启用时，仍有效且未过期的 token 可以继续使用。轮换密钥立即使旧 token 失效；修改 TTL 不会延长已有 token 的到期时间。

## 数据与接口

记录只保存脱敏收件人（如 `q***@example.invalid`）、主题、来源、账户/操作者引用及时间/状态；不重复保存原始收件地址或正文。删除 SMTP 账户不会删除发送历史，账户名称改为 `null`。

token 包含不透明记录 UUID、安全随机 nonce 和到期时间，并由 HMAC-SHA256 签名；数据库仅保存 SHA-256 摘要。管理接口不会返回 token 或像素 URL，也不会因查看记录而自动加载像素。token 是能力凭据，不要复制进工单、日志、分析系统或公开截图。

- `GET /api/v1/mail-tracking/status`：需要 `smtp:read` 或 `template:read`，返回启用状态、回调 origin、配置范围及 TTL 秒数。
- `GET /api/v1/mail-send-records?page=1&pageSize=20`：需要 `smtp:read`，沿用 `PageResponse`；页码 1–100000，单页 1–100，按创建时间和 UUID 稳定降序。
- `GET /api/v1/mail-send-records/{id}`：需要 `smtp:read`，返回 `{record, events, links, clickEvents}`；两类事件各自最多返回最新 50 条并稳定降序；链接只返回服务器保存的原目标和聚合，不返回 token。
- `GET /t/o/{token}`：匿名；合法、错误、伪造、过期、未知、失败或全局禁用 token 均返回相同透明 GIF。`HEAD` 只返回头部，不计数；其他方法不计数。
- `GET /t/c/{token}`：匿名；合法 token 返回 `302` 到发送前保存的 HTTP(S) 目标并记录一次去重事件。`HEAD` 返回相同跳转但不计数，其他方法返回 `405`；错误、伪造、过期、未知、失败或禁用 token 使用相同 `404`，不会泄漏目标。

像素响应使用 `no-store/no-cache`、`no-referrer` 和 `nosniff`。采集数据库不可用或超时也不会向请求者暴露 token 是否存在。Nginx `/t/` 不写访问/错误日志，按来源地址限制为 10 请求/秒、突发 20，超出返回 429。外层代理/WAF/APM 必须使用同等 URL 脱敏策略，不能开启包含完整请求路径的调试日志或异常采集。

事件分类为 `PREFETCH`（识别到预取头）、`IMAGE_PROXY`（识别到图片代理 UA）、`BOT`（识别到扫描/爬虫 UA），否则为 `UNCLASSIFIED`。未知请求不推断为人类。规则可误判，代理也可能隐藏特征。

只用最多 512 字符的 UA 与分类计算指纹摘要；预取头最多检查 128 字符。不保存原始 UA 或 IP。图片按 `(record_id, fingerprint_hash, minute_bucket)`、点击按 `(link_id, fingerprint_hash, minute_bucket)` 原子去重；相同指纹的一分钟内重试只计一次，下一分钟可再计一次。`rawOpenCount`/`rawClickCount` 是各自去重后的存储事件总数，`automatedOpenCount`/`automatedClickCount` 是预取/代理/机器人事件之和，不是独立人数或每次网络请求数。

## 保留期与手动维护

默认不自动删除发送记录或事件；对账任务只改变残留发送状态，不做清理。建议按实际审计需要定期确认保留期，例如保留最近 90 天，备份后使用明确 UTC 截止时间执行以下命令。脚本只删除创建时间严格早于 cutoff 的 `mail_send_records`，级联删除它们的图片事件、链接映射与点击事件；不触及账户、用户、模板、活动统计或审计日志。删除记录不可撤销，仍未过期的 token 也会立即停止计数。

在仓库根目录，替换并复核示例截止时间后执行：

```bash
docker compose -f docker-compose.yml exec -T postgres \
  sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v cutoff="$1"' \
  sh '2026-05-01T00:00:00Z' \
  < backend/src/main/resources/db/maintenance/delete_mail_tracking_before.sql
```

缺少、无效或非过去的 cutoff 会在删除前中止。脚本返回删除记录数量；请对照备份和预期范围检查。大规模历史数据应分阶段推进截止时间，避免一次长事务。

## 本地验证

仅选择本地 Mailpit 账户和明确的测试地址。先发送未勾选跟踪的邮件，确认有记录且 MIME 中无回传 URL；再 opt-in 发送，检查实际收到的 MIME 中有唯一 `/t/o/` 像素和 `/t/c/` 链接。分别用实际 URL 发起 GET，刷新对应记录，确认增加图片和点击事件且点击只跳到服务器保存的原目标，其他记录与活动统计不变。再检查 HEAD 不增加计数、同分钟相同 UA 的 GET 去重，以及预取/代理/扫描 UA 保留分类。

本地合成请求只验证回调链路，不能称为用户打开了之前发到外部邮箱的邮件。若将来需要公网使用，应另行批准部署，并从授权的外部测试环境核验可达性；仅配置一个 HTTPS 字符串不够。
