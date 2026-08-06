# 运维手册

## 健康检查

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps
curl -fsS http://localhost:8080/healthz
curl -fsS http://localhost:8080/api/v1/system/health
docker compose exec -T postgres pg_isready -U camel -d camel_arxiv
docker compose exec -T redis redis-cli ping
docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping
```

正常基线为九个服务 running/healthy，worker `RestartCount=0`。健康接口只返回状态，不暴露连接信息。

## 日志与排障

```bash
docker compose logs --since=15m backend-api
docker compose logs --since=15m mail-worker
docker compose logs --since=15m arxiv-worker
docker compose logs --since=15m postgres rabbitmq redis
```

按 Trace ID 串联 Nginx、API、任务事件和 worker 消息。禁止为了排障临时输出 Authorization、Cookie、邮箱、SMTP Secret、JWT、Source 内容或完整消息帧。Python worker 即使在 DEBUG 下也把 AMQP 协议库限制为 WARNING。

常见现象：

| 现象 | 检查 | 处理 |
|---|---|---|
| 前端 502 | `backend-api` health/logs | 先处理 Flyway/依赖连接，不循环重启数据库 |
| Worker 重启 | `docker inspect ... RestartCount` 与 worker logs | 检查入口、消息版本、RabbitMQ 凭据 |
| 任务长时间 RUNNING | `jobs.heartbeat_at`、worker heartbeat、队列积压 | 标记失联任务并按幂等键安全重试 |
| arXiv 预览 503 | Redis、`backend-api` 日志、官方状态 | Redis 故障时保持 fail-closed；不要临时绕过全局限速 |
| 分类同步停在 PENDING | `outbox_messages.published_at`、`arxiv.jobs.worker` binding | 先修复 Outbox/RabbitMQ；不要直接手工替换 active snapshot |
| OAI 同步反复重试 | Job checkpoint、`badResumptionToken`、官方 OAI 状态 | 让 Worker 按保存日期安全重启游标；不要编辑不透明 token |
| Source 为 `SOURCE_UNAVAILABLE` | 官方论文 Source 可用性、Job 事件 | 这是论文级可接受终态；不要换第三方镜像或伪造 Source |
| Source 为 `SECURITY_REJECTED` | 非敏感错误码、归档尺寸/格式、Worker 版本 | 保留归档边界，不手工解包；确认是否为格式变化后以测试夹具升级解析器 |
| Source Job 已完成但无联系人 | 提取运行、作者、规则和脱敏证据 | 无邮箱是有效结果；不要按姓名/域名猜测或外部丰富 |
| Source Job 无法进入终态 | `job_items`、结果队列/DLQ、`processed_messages` | 先处理未持久化的 item result；禁止直接把 Job 改为成功 |
| Worker tmpfs 增长 | 当前 Job、heartbeat、容器 RestartCount | 暂停新任务，保留日志后安全重建 Worker；确认临时根为空再恢复 |
| 邮件没有进入 Mailpit | `ALLOW_LIVE_SMTP`、mail-worker、Mailpit accepted 数 | 不改为真实 SMTP 作为排障手段 |
| 数据库迁移失败 | `flyway_schema_history` 与 API logs | 停止扩容，修正新迁移；不要编辑已发布迁移 |

## 认证运维

首次部署通过运行时 Secret 提供 `INITIAL_ADMIN_USERNAME`、`INITIAL_ADMIN_EMAIL`、`INITIAL_ADMIN_DISPLAY_NAME` 与符合策略的 `INITIAL_ADMIN_PASSWORD`。启动日志只应出现用户名，绝不能出现临时密码。管理员完成首次改密后，从部署 Secret 中移除密码值；引导逻辑是幂等的，不会覆盖现有账号。

认证相关独立密钥至少 32 字节并使用 Base64：`JWT_SIGNING_KEY_BASE64` 与 `AUTH_FINGERPRINT_HMAC_KEY_BASE64` 不得复用。生产环境保持 `AUTH_COOKIE_SECURE=true` 和 `AUTH_COOKIE_SAME_SITE=Strict`，只有 localhost 开发覆盖可以关闭 Secure。

账号恢复顺序：

1. 由另一名 `SUPER_ADMIN` 在用户管理中启用账号或重置临时密码。
2. 让用户使用临时密码登录并立即改密；重置动作已经撤销其全部 refresh 会话并增加 `tokenVersion`。
3. 对登录限流不要直接删除审计记录。确认失败来源后等待默认 15 分钟窗口结束；若必须人工处置，只能按已核实的主体/IP 哈希定点处理 `login_attempts`，并保留变更审计。
4. 普通 `ADMIN` 不能创建、编辑、重置或启停 `SUPER_ADMIN` 账号；最后一个有效 `SUPER_ADMIN` 还受事务保护，不能通过 UI/API 停用。应在投产时建立至少两个受控管理员账号并分别保管凭据。

refresh 重放通常表现为第一次旧 Cookie 请求 401，随后同 family 的最新 Cookie 也返回 401。此行为是主动撤销而非服务异常；按 API Trace ID 检查 `AUTH_REFRESH_REPLAY` 拒绝审计，并确认客户端是否存在并发或复制 Cookie，随后要求重新登录。禁止记录或粘贴 Cookie/JWT 原值用于排障。

## 数据库检查

```bash
docker compose exec -T postgres psql -U camel -d camel_arxiv \
  -c 'select installed_rank, version, description, success from flyway_schema_history order by installed_rank;'
```

备份示例（目标路径应位于受保护且容量充足的备份卷）：

```bash
docker compose exec -T postgres pg_dump -U camel -d camel_arxiv -Fc > camel_arxiv.dump
```

恢复必须在隔离环境定期演练。MinIO、RabbitMQ definitions 和运行平台 Secret 需要独立备份；Redis 不作为唯一事实源。

## 队列与幂等

- 监控 ready/unacked 数、最老消息年龄、重试和 DLQ。
- 只在根因处理后重放 DLQ；保留原 `messageId`/`idempotencyKey`。
- Outbox 发布后设置 `published_at`；消费者完成业务事务后记录 `processed_messages` 再 ACK。
- 不手工删除未知积压，也不直接把失败任务改为成功。

Phase 3 队列快速检查：

```bash
docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
docker compose exec -T rabbitmq rabbitmqctl list_bindings source_name destination_name routing_key
docker compose exec -T postgres psql -U camel -d camel_arxiv \
  -c "select id,type,status,current_stage,progress_percent,heartbeat_at from jobs where type like 'ARXIV_%' order by created_at desc limit 20;"
docker compose exec -T postgres psql -U camel -d camel_arxiv \
  -c "select worker_id,status,current_job_id,last_seen_at from worker_heartbeats order by last_seen_at desc;"
```

正常拓扑包含 `arxiv.jobs.worker <- arxiv.#`、`arxiv.results.backend <- arxiv.#` 和 `arxiv.results.backend <- worker.heartbeat`。`mail-worker` 不应消费 arXiv 结果队列，且其 `mail-worker` profile 不注册 `/api/v1/**` 业务 Controller。

Phase 4 Source 快速检查：

```bash
docker compose exec -T rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
docker compose exec -T postgres psql -U camel -d camel_arxiv \
  -c "select id,status,current_stage,total_count,processed_count,success_count,failed_count from jobs where type='ARXIV_FETCH_AND_PARSE_SOURCE' order by created_at desc limit 20;"
docker compose exec -T postgres psql -U camel -d camel_arxiv \
  -c "select paper_id,status,source_format,archive_size_bytes,extracted_size_bytes,files_inspected,contacts_found,cleanup_confirmed from extraction_runs order by started_at desc limit 20;"
docker compose exec -T arxiv-worker sh -c 'test -z "$(find /var/tmp/arxiv-source -mindepth 1 -maxdepth 1 -print -quit)"'
```

最后一条只判断临时根是否为空，不输出 Source 文件名/内容。联系人检查应比较记录数、nonce 是否不同、密文是否含 `@` 等布尔结果；不要把解密值打印到终端。完整邮箱的合规验证应通过受权 API 完成，并核对 `CONTACT_EMAIL_DISCLOSED` 审计数量。

## arXiv 限速、分类与 OAI

- `ARXIV_MIN_REQUEST_INTERVAL` 不得小于 `PT3S`；Worker 同步使用 `ARXIV_WORKER_MIN_REQUEST_INTERVAL_SECONDS=3`。两端共享 Redis 全局预约协议。
- `ARXIV_CONTACT_EMAIL` 必须是受监控地址，用于描述性 User-Agent；生产不得保留 `.invalid` 默认值。
- 预览缓存默认 24 小时。清缓存只会导致下一次查询重新取得全局租约，不能用来绕过官方限制。
- 分类读取不依赖上游；首次空库从版本化 JSON 快照启动。管理员同步使用 OAI `ListSets`，保留两段式 physics set、离线描述和 alias；active snapshot 与 Job 成功终态原子切换。
- OAI `resumptionToken` 是不透明短期值。Worker 将其保存在 Redis 用于 pause/requeue 恢复，后端在 `jobs.checkpoint` 和 `arxiv_sync_cursors` 保存持久状态；排障时只能查看长度、存在性和接收时间，不把 token 全文写日志或工单。
- 暂停/取消是协作式的：当前官方 HTTP 请求完成后、下一次租约/请求前生效。重试保留原 Job 历史并创建新 lineage。

## Source 提取运维

- 官方 Source 请求与 Legacy/OAI 共用全局三秒租约；禁止为了吞吐建立绕过 Redis 的 Worker 池。
- 默认只允许 tar/tar.gz/zip/gzip TeX/纯 TeX，并同时执行下载、展开、单文件、文件数、路径深度、include 深度、压缩比和解析时间上限。
- Source 归档/展开文件只存在于 Worker tmpfs。`cleanup_confirmed=false` 不是可忽略告警，应隔离该 Worker 并确认目录和挂载生命周期。
- 结果消息永久校验失败时可能进入 `arxiv.dead.archive`。先按 Job/Message ID 验证其确为本平台消息和失败根因；未知积压不得 purge。修复消费者后以原幂等键定点重放，重复结果会安全 ACK。
- 联系人列表按论文筛选时选择该论文范围内最新映射；全局列表选择联系人全局最新映射。人工验证使用 `mappingId` 与 `expectedVersion`，409 表示应刷新而不是覆盖。

## 邮件安全操作

开发/CI 只使用 Mailpit。后续启用真实 SMTP 前必须确认活动已审批、Recipient 快照已冻结、抑制/退订已应用、频率上限有效、域名认证完成。紧急停止应暂停 Campaign 消费者并保留队列，不删除 Recipient/Attempt 审计记录。

## 保留与隐私

`data_retention_policies` 是保留任务的事实配置。原始追踪事件、IP/User-Agent 派生数据、审计和导出文件应按不同期限清理。打开事件只能解释为估算信号；报告中明确 SMTP accepted 不等同 delivered。

## 阶段验收基线

2026-08-05 实测：4 个 Flyway 迁移成功、50 张 public 表、9 个容器健康；Nginx/API/Mailpit/MinIO 宿主入口可达；后端、Python、Vue 全量质量门通过；桌面 1280×720 与移动 390×844 无横向溢出、零 console warning/error，移动抽屉关闭后焦点回到触发按钮。

Phase 2 同日实测：Flyway 更新到 V5；初始管理员强制改密；改密后旧 access/refresh 均为 401；refresh 单次轮换成功，旧值重放后整族失效且写入 `AUTH_REFRESH_REPLAY`；权限目录为 26 项；普通 `ADMIN` 的四类 `SUPER_ADMIN` 接管请求均为 403 并落审计，`VIEWER` 管理端请求为带 Trace ID 的 403；logout 后 refresh 为 401。用户/角色/审计三页通过桌面与 390×844 浏览器检查，页面无全局横向溢出且控制台零 warning/error。

Phase 3 于 2026-08-06 实测：Flyway V6 后为 53 张表、1 个物化视图；后端 132 tests/`clean check`/`bootJar`、Worker 31 tests/Ruff/MyPy、前端 27 tests/ESLint/`vue-tsc`/Vite build 全部通过。真实官方查询 `reliable agents` 返回结果，选中导入 Job `e42fd065-25ce-42d7-a639-090c6913625f` 完成并持久化论文 `2212.02256`。OAI 分类同步 Job `18fed311-b1af-4dd7-ae09-148a867aac71` 原子完成 166 个分类、6 个 alias 和 155 条描述，包含 `hep-th`、`math-ph`、`quant-ph` 等两段式 set；`mail-worker` 的业务健康 API 返回 404。任务详情显示 CREATED/STARTED/BATCH/PROGRESS/COMPLETED 事件和新鲜 Worker 心跳；发现、任务、论文页面桌面无 console error，390×844 发现页 `scrollWidth=clientWidth=390`。

Phase 4 于 2026-08-06 实测：Flyway V7；后端 153 tests、Worker 68 tests、前端 30 tests 及各自完整质量门通过，Compose 九服务和三个镜像契约通过。真实 Source Job `81f0900e-2865-4044-8c42-dff7899505db` 对 `2212.02256` 完成 TAR_GZIP 下载、解包、作者/联系人提取和原子回写，归档/展开尺寸为 488,729/913,762 bytes，临时目录清理确认。数据库密文不含 `@`、nonce 独立、HMAC 唯一；受权联系人列表脱敏、完整披露审计与 `mail-worker` 业务 API 404 均通过。桌面 1280×720 和移动 390×844 无页面级横向溢出，控制台零 warning/error；Worker RestartCount=0，验收后四个 arXiv 队列均为空。

Phase 5 于 2026-08-06 实测：Flyway V8 分析索引生效；真实队列独立 SQL 与 overview/contact API 在论文、解析、邮箱论文、唯一联系人和映射数上完全一致，导出审计成功。桌面 1440×900 和移动 390×844 的三个分析页无横向溢出且控制台零 warning/error，联系人页不显示完整邮箱；九服务 healthy、应用容器 RestartCount=0、队列为空。详细口径与对账 SQL 见 [ANALYTICS.md](ANALYTICS.md)。
