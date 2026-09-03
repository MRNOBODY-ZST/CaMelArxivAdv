# 活动投递运行手册

本手册用于从论文发现、作者/联系人证据、个性化草稿、安全实流，推进到正式邮件活动。PostgreSQL 是唯一业务状态源；Kafka 只携带不含地址、正文、凭据或回调 token 的唤醒消息。

## 1. 不可跳过的边界

- `CAMPAIGN_DELIVERY_ENABLED`、`CAMPAIGN_SAFETY_ENABLED`、`CAMPAIGN_INBOUND_ENABLED` 默认均为 `false`。
- 安全实流最多 20 封，API 必须收到精确确认短语 `SAFETY_REDIRECT`，收件地址只能来自运行时 `CAMPAIGN_SAFETY_RECIPIENT`。
- 安全实流使用独立的 run/message/attempt/link/event 表，不得写入正式 recipient/attempt/tracking/unsubscribe/suppression/cooldown 状态。
- 正式活动只有在内容、退订占位符、发件身份、SMTP、IMAP、公开 HTTPS 回调及合格收件人全部通过预检后才能提交审核、批准或开始。
- 对真实外部作者的发送仍是停止门：必须先给出具体、真实的发件身份和联系目的，核对收件人证据及预览，再执行独立的最终批准。安全实流授权不能替代正式发送授权。

## 2. 变更前只读核查

记录当前 Git 提交、镜像摘要、容器健康、数据库版本、Kafka topic、Nginx vhost 与上游地址。不要在这一步重建或重启服务。

```bash
git rev-parse HEAD
docker compose ps
docker image inspect IMAGE --format '{{index .RepoDigests 0}} {{.Id}}'
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
  'select version, description, success from flyway_schema_history order by installed_rank desc limit 5'
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 --describe --topic camel.mail.delivery.jobs.v1
sudo nginx -T
```

确认 `arxiv.nodexi.top` 是唯一待修改 vhost；不得修改其他域名、默认 server 或无关 location。

## 3. 备份与恢复演练

使用权限为 700 的临时目录保存 custom-format 备份、校验和和镜像清单。备份文件不得进入仓库。

```bash
umask 077
delivery_backup_dir=$(mktemp -d)
docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc \
  >"$delivery_backup_dir/pre-delivery.dump"
shasum -a 256 "$delivery_backup_dir/pre-delivery.dump" \
  >"$delivery_backup_dir/pre-delivery.dump.sha256"
pg_restore --list "$delivery_backup_dir/pre-delivery.dump" >/dev/null
createdb campaign_restore_drill
pg_restore --exit-on-error --no-owner --no-privileges \
  --dbname campaign_restore_drill "$delivery_backup_dir/pre-delivery.dump"
dropdb campaign_restore_drill
```

恢复演练必须对隔离数据库执行，绝不能覆盖生产库。部署完成并达到备份保留要求后，安全删除临时目录。

## 4. 禁用状态部署

首次部署必须保持以下运行时值：

```dotenv
CAMPAIGN_DELIVERY_ENABLED=false
CAMPAIGN_SAFETY_ENABLED=false
CAMPAIGN_SAFETY_RECIPIENT=
CAMPAIGN_INBOUND_ENABLED=false
```

构建带提交 SHA 的不可变镜像。只重建/替换 `backend-api`、`mail-worker`、`frontend`；不要 recreate PostgreSQL、Redis、Kafka、Ray、arXiv Worker、个性化 Worker或无关 Nginx vhost。先运行迁移，再等待三个受影响容器健康。

Kafka topic 必须为 3 个分区、7 天保留：

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server kafka:9092 --create --if-not-exists \
  --topic camel.mail.delivery.jobs.v1 --partitions 3 --replication-factor 1 \
  --config retention.ms=604800000
docker compose exec -T kafka /opt/kafka/bin/kafka-configs.sh \
  --bootstrap-server kafka:9092 --alter --entity-type topics \
  --entity-name camel.mail.delivery.jobs.v1 --add-config retention.ms=604800000
```

公开 Nginx 只在 `arxiv.nodexi.top` vhost 中增加精确的 `^~ /u/` 代理，且必须位于 SPA fallback 之前。该 location 与 `/t/` 一样关闭 access/error token 日志，限流，设置 `no-store`、`no-referrer`，并只转发可信的 `$remote_addr`。修改后先执行 `sudo nginx -t`，成功才 reload。

## 5. 预检和账户健康

在 UI 或认证 API 中分别测试 SMTP 与 IMAP 账户，确认最近一次成功结果仍在有效期内。IMAP 仅以 READ_ONLY 打开文件夹，不设置 Seen、不移动、不删除邮件。每个活动执行：

```text
GET /api/v1/campaigns/{id}/preflight
```

以下检查必须全部为通过：`CONTENT_READY`、`UNSUBSCRIBE_PRESENT`、`SENDER_VALID`、`SMTP_READY`、`MAILBOX_READY`、`TRACKING_READY`、`RECIPIENTS_ELIGIBLE`。核对 `ELIGIBLE` 及所有排除计数，不以总分组人数代替可发送人数。

## 6. 安全实流启用顺序

只有禁用状态部署、迁移、topic、HTTPS 回调、SMTP/IMAP 测试、认证 API 和 UI 均通过后，才可通过运行时 Secret 设置固定安全邮箱。不要将地址或任何凭据写入 Git、命令历史或日志。

安全实流所需开关为：公开 HTTPS tracking 已启用并有独立签名密钥，允许经过 TLS 策略的公网 SMTP/IMAP，活动投递、入站同步及安全模式已启用。API 与 mail-worker 必须使用相同的安全地址、签名/加密/HMAC 密钥和策略值。协调重启避免新旧副本重叠。

通过活动详情填写 1–20 和 `SAFETY_REDIRECT`。观察 run 的 queued/connecting/SMTP accepted/temporary failure/permanent failure/outcome unknown，直到所有 message 进入持久化终态。达到 SMTP 账户分钟、小时、日或域名窗口限制时应等待窗口自然恢复，不得提高限额绕过保护。

## 7. Kafka 与 IMAP 观察

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:9092 --describe --group camel-mail-delivery-v1
```

短暂 lag 可在频控等待时存在；成功标准是没有持续增长的可执行 lag，outbox 没有长期未发布记录，worker 健康。IMAP 核对只查询 UID、UIDVALIDITY、受界限约束的 Message-ID/References/In-Reply-To/DSN 字段，不读取或打印正文/附件。匹配结果应进入 safety event；生产 recipient 不得变化。

## 8. 回调隐私验收

回调 token 只能从本次安全消息的持久化工件中受控提取到 mode-600 临时文件，不得出现在终端输出、浏览器地址截图、访问日志或工单。依次请求一条 open、一条 click 和一条 unsubscribe 回调，验证 HTTPS、限流、`Cache-Control: no-store`、`Referrer-Policy: no-referrer` 与安全事件计数。结束后立即删除临时文件。

回调计数是技术信号：打开/点击可能来自代理、预取或安全扫描，不可表述为确认人工阅读。

## 9. 安全隔离核对

安全实流前后保存下列正式表的行数和按状态聚合（不要导出密文、HMAC、正文或 token）：

- `campaign_recipients`
- `delivery_attempts`
- `tracking_events`
- `unsubscribe_records`
- `suppression_entries`
- `recipient_delivery_cooldowns`

本次安全 run 只允许改变 `campaign_safety_runs`、`campaign_safety_messages`、`campaign_safety_attempts`、`campaign_safety_links`、`campaign_safety_events`、对应 mailbox cursor/inbound audit 及不含隐私的 outbox 状态。正式表的差异必须可解释为同时间其他已批准生产活动；否则立即停止发送并调查。

## 10. 回滚

1. 先将安全、正式投递和入站开关设为 `false`，协调停止 mail-worker。
2. 将 API、mail-worker、frontend 回滚到部署前记录的镜像摘要。
3. 等待健康并验证 API、Nginx 与只读查询。
4. 保留前向迁移、Kafka topic、投递/审计历史；不要通过删除记录伪造回滚。
5. 只有迁移本身导致无法兼容且已经过单独批准时，才从已验证备份恢复到隔离验证后的新数据库实例。

正式作者发送前再次执行完整预检、逐封预览和最终批准。没有具体发件身份及联系目的时，到此停止。
