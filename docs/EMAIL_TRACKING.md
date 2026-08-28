# 测试邮件与图片回传

此功能只覆盖 SMTP 诊断邮件与模板测试发送，不修改活动投递、联系人或活动报表。每次实际发送前创建独立记录，ID 就是原有成功响应中的 `correlationId`。连接测试不发送邮件，因此不创建发送记录。上线前的旧邮件无法补加回传；IMAP 的 Seen 状态也不能证明收件人读过另一封外发邮件。

## 如何理解结果

界面应使用“检测到图片加载 / 估算打开”，不能标记为“确认阅读”。没有事件不等于未阅读：收件人可能禁用了图片、使用缓存或离线阅读；转发、图片代理、预取及安全扫描也可能产生事件。Apple 的邮件隐私保护会在后台下载远程内容，即使用户没有与邮件互动。[Apple 官方说明](https://www.apple.com/legal/privacy/data/en/mail-privacy-protection/)

发送结果与图片回传是两条独立事实：

- `SENDING`：记录已创建，发送结果尚未确认，也可能因进程中断或结果写入故障而停留在此状态。
- `SMTP_ACCEPTED`：SMTP 服务已接受，不代表最终投递或人工阅读。
- `FAILED`：有明确拒绝或发送前连接/认证等失败；之后的 token 请求不计数。
- `UNKNOWN`：超时、断连或其他无法确定结果的传输故障；不要自动重发。

回传可以先于 SMTP 返回，所以 `SENDING`、`UNKNOWN` 也可以记录图片请求，但不会因回传自动变成 `SMTP_ACCEPTED`。已记录的早期回传不会因为后来收到明确失败而被抹除。发送记录插入失败时不会尝试 SMTP；SMTP 接受后，账户测试元数据、审计或结果持久化失败不会把响应改为发送失败、改写为 `FAILED` 或触发重试。结果写入故障时可能暂留 `SENDING`，服务仅记录不含 token 的记录 ID 告警，需结合 SMTP 侧记录核对。

## 配置与范围

| 配置 | 默认值 / 含义 |
| --- | --- |
| `TRACKING_ENABLED` | `false`；全局关闭时，显式要求跟踪返回 400，未跟踪发送仍可进行 |
| `TRACKING_PUBLIC_BASE_URL` | 未设置时回退 `PUBLIC_BASE_URL`，再回退 `http://localhost:8080`；Compose 的空值同样回退 |
| `TRACKING_SIGNING_KEY_BASE64` | 默认空；启用时须为独立随机密钥，至少 32 个解码字节；可分别运行 `openssl rand -base64 32` 生成，切勿复用已有输出 |
| `TRACKING_TOKEN_TTL` | `PT720H`，即 30 天；允许 60 秒至 90 天的整数秒 |

回调地址必须是绝对 origin，不允许用户信息、路径（包括末尾 `/`）、查询或片段。公网主机必须 HTTPS；回环、私网 IP、单标签主机名、`.local` 主机可以 HTTP。应用只验证地址格式和字面主机类型，不访问 URL、不解析 DNS、不从请求 Host 生成地址。

`LOCAL_ONLY` 表示本地/私网配置，即使使用 HTTPS 也不变。`PUBLIC_HTTPS_UNVERIFIED` 仅表示配置了非本地主机的 HTTPS，仍需操作者另行验证公网 DNS、TLS、路由与访问权限。本功能不部署公网服务、不创建隧道、不增加端口。外部邮箱的图片代理无法访问 `localhost`；容器中的 `localhost` 也指向容器自身。

每封邮件的 `trackOpens` 默认 `false`；只有明确 opt-in 才在最终 HTML 中增加 1×1 像素。纯文本、主题、模板预览及保存版本不变。即使不跟踪，实际发送仍有独立记录。关闭全局开关会停止所有 token 的事件收集；重新启用时，仍有效且未过期的 token 可以继续使用。轮换密钥立即使旧 token 失效；修改 TTL 不会延长已有 token 的到期时间。

## 数据与接口

记录只保存脱敏收件人（如 `q***@example.invalid`）、主题、来源、账户/操作者引用及时间/状态；不重复保存原始收件地址或正文。删除 SMTP 账户不会删除发送历史，账户名称改为 `null`。

token 包含不透明记录 UUID、安全随机 nonce 和到期时间，并由 HMAC-SHA256 签名；数据库仅保存 SHA-256 摘要。管理接口不会返回 token 或像素 URL，也不会因查看记录而自动加载像素。token 是能力凭据，不要复制进工单、日志、分析系统或公开截图。

- `GET /api/v1/mail-tracking/status`：需要 `smtp:read` 或 `template:read`，返回启用状态、回调 origin、配置范围及 TTL 秒数。
- `GET /api/v1/mail-send-records?page=1&pageSize=20`：需要 `smtp:read`，沿用 `PageResponse`；页码 1–100000，单页 1–100，按创建时间和 UUID 稳定降序。
- `GET /api/v1/mail-send-records/{id}`：需要 `smtp:read`，返回 `{record, events}`；事件仅最新 50 条，按时间和事件 ID 稳定降序。
- `GET /t/o/{token}`：匿名；合法、错误、伪造、过期、未知、失败或全局禁用 token 均返回相同透明 GIF。`HEAD` 只返回头部，不计数；其他方法不计数。

像素响应使用 `no-store/no-cache`、`no-referrer` 和 `nosniff`。采集数据库不可用或超时也不会向请求者暴露 token 是否存在。Nginx `/t/` 不写访问/错误日志，按来源地址限制为 10 请求/秒、突发 20，超出返回 429。外层代理/WAF/APM 必须使用同等 URL 脱敏策略，不能开启包含完整请求路径的调试日志或异常采集。

事件分类为 `PREFETCH`（识别到预取头）、`IMAGE_PROXY`（识别到图片代理 UA）、`BOT`（识别到扫描/爬虫 UA），否则为 `UNCLASSIFIED`。未知请求不推断为人类。规则可误判，代理也可能隐藏特征。

只用最多 512 字符的 UA 与分类计算指纹摘要；预取头最多检查 128 字符。不保存原始 UA 或 IP。数据库按 `(record_id, fingerprint_hash, minute_bucket)` 原子去重；相同指纹的一分钟内重试只计一次，下一分钟可再计一次。`rawOpenCount` 是去重后存储事件总数，`automatedOpenCount` 是预取/代理/机器人事件之和，不是独立读者数或每次网络请求数。`firstOpenAt`/`lastOpenAt` 也是图片请求时间。

## 保留期与手动维护

默认不自动删除发送记录或事件；此变更不添加定时任务。建议按实际审计需要定期确认保留期，例如保留最近 90 天，备份后使用明确 UTC 截止时间执行以下命令。脚本只删除创建时间严格早于 cutoff 的 `mail_send_records`，级联删除它们的 `mail_open_events`；不触及账户、用户、模板、活动统计或审计日志。删除记录不可撤销，仍未过期的 token 也会立即停止计数。

在仓库根目录，替换并复核示例截止时间后执行：

```bash
docker compose -f docker-compose.yml exec -T postgres \
  sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v cutoff="$1"' \
  sh '2026-05-01T00:00:00Z' \
  < backend/src/main/resources/db/maintenance/delete_mail_tracking_before.sql
```

缺少、无效或非过去的 cutoff 会在删除前中止。脚本返回删除记录数量；请对照备份和预期范围检查。大规模历史数据应分阶段推进截止时间，避免一次长事务。

## 本地验证

仅选择本地 Mailpit 账户和明确的测试地址。先发送未勾选跟踪的邮件，确认有记录且 MIME 中无像素；再 opt-in 发送，检查实际收到的 MIME 中有唯一 `/t/o/` URL。用该实际 URL 发起一次 GET，刷新对应记录，确认增加一个图片加载事件，其他记录与活动统计不变。再检查 HEAD 不增加计数、同分钟相同 UA 的 GET 去重，以及预取/代理/扫描 UA 保留分类。

本地合成请求只验证回调链路，不能称为用户打开了之前发到外部邮箱的邮件。若将来需要公网使用，应另行批准部署，并从授权的外部测试环境核验可达性；仅配置一个 HTTPS 字符串不够。
