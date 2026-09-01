# 运维手册

## 健康检查

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml ps
curl -fsS http://localhost:8080/healthz
curl -fsS http://localhost:8080/api/v1/system/health
docker compose exec -T postgres pg_isready -U camel -d camel_arxiv
docker compose exec -T redis redis-cli ping
docker compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092
docker compose exec -T ray-head python -c "import socket; socket.create_connection(('127.0.0.1', 10001), 3).close()"
```

正常基线为十三个服务 running/healthy、`kafka-init` 成功退出，worker `RestartCount=0`。健康接口只返回状态，不暴露连接信息。

## 日志与排障

```bash
docker compose logs --since=15m backend-api
docker compose logs --since=15m mail-worker
docker compose logs --since=15m arxiv-worker
docker compose logs --since=15m personalization-worker ray-head ray-worker
docker compose logs --since=15m postgres kafka redis
```

按 Trace ID 串联 Nginx、API、任务事件和 worker 消息。禁止为了排障临时输出 Authorization、Cookie、邮箱、SMTP Secret、JWT、Source 内容或完整消息帧。Python worker 即使在 DEBUG 下也把 Kafka 协议库限制为 WARNING。

常见现象：

| 现象 | 检查 | 处理 |
|---|---|---|
| 前端 502/业务 API 意外 404 | `backend-api` health/logs、Nginx upstream 与 Docker DNS | 先处理 Flyway/依赖连接；边缘 Nginx 使用 `127.0.0.11` 动态重解析 `backend-api`，后端 healthy 后应自行恢复。若仍失败，检查服务名与网络，不要用固定容器 IP |
| Worker 重启 | `docker inspect ... RestartCount` 与 worker logs | 检查入口、消息版本、Kafka bootstrap 与 consumer group |
| 任务长时间 RUNNING | `jobs.heartbeat_at`、worker heartbeat、队列积压 | 标记失联任务并按幂等键安全重试 |
| arXiv 预览 503 | Redis、`backend-api` 日志、官方状态 | Redis 故障时保持 fail-closed；不要临时绕过全局限速 |
| 分类同步停在 PENDING | `outbox_messages.published_at`、`camel.arxiv.jobs.v1` consumer lag | 先修复 Outbox/Kafka；不要直接手工替换 active snapshot |
| OAI 同步反复重试 | Job checkpoint、`badResumptionToken`、官方 OAI 状态 | 让 Worker 按保存日期安全重启游标；不要编辑不透明 token |
| Source 为 `SOURCE_UNAVAILABLE` | 官方论文 Source 可用性、Job 事件 | 这是论文级可接受终态；不要换第三方镜像或伪造 Source |
| Source 为 `SECURITY_REJECTED` | 非敏感错误码、归档尺寸/格式、Worker 版本 | 保留归档边界，不手工解包；确认是否为格式变化后以测试夹具升级解析器 |
| Source Job 已完成但无联系人 | 提取运行、作者、规则和脱敏证据 | 无邮箱是有效结果；不要按姓名/域名猜测或外部丰富 |
| Source Job 无法进入终态 | `job_items`、结果队列/DLQ、`processed_messages` | 先处理未持久化的 item result；禁止直接把 Job 改为成功 |
| Worker tmpfs 增长 | 当前 Job、heartbeat、容器 RestartCount | 暂停新任务，保留日志后安全重建 Worker；确认临时根为空再恢复 |
| 邮件没有进入 Mailpit | `ALLOW_LIVE_SMTP`、mail-worker、Mailpit accepted 数 | 不改为真实 SMTP 作为排障手段 |
| 测试邮件长期停留 `SENDING` | 记录 `created_at`、`backend-api` 重启/发送日志、SMTP 侧记录 | 服务会在启动时及每分钟把超过 `TRACKING_STALE_SENDING_AFTER` 的记录保守改为 `UNKNOWN/SEND_OUTCOME_MISSING`；不要据回传猜测为已发送，也不要自动重发 |
| 公网回传配置存在但没有事件 | DNS/TLS、Nginx `/t/`、邮件 MIME、token 是否过期及代理策略 | `PUBLIC_HTTPS_CONFIGURED` 只表示配置形态；用内部 Mailpit 的实际 `/t/o/`、`/t/c/` URL 合成验收，禁止把无事件解释为未阅读/未点击 |
| 个性化生成按钮禁用 | `/api/v1/system/runtime` 的非敏感开关、`PERSONALIZATION_ENABLED` | 注入有效 API Key 后再启用；不要在浏览器、日志或数据库中粘贴 Key |
| 生成长期 QUEUED/RUNNING | personalization worker、Ray 节点、`mail.personalization.*` 队列 | 先确认 Worker/Ray 健康和积压；保留原幂等键，禁止直接改活动为完成 |
| 单个作者生成失败 | 收件人安全错误码、提供方状态、Ray 有界重试日志 | 永久失败逐条保留；不记录模型请求全文或联系人邮箱，不重跑整个已完成批次 |
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

恢复必须在隔离环境定期演练。MinIO、Kafka topic/ACL 配置和运行平台 Secret 需要独立备份；Redis 不作为唯一事实源。

## Kafka 主题与幂等

- 监控 consumer group lag、最老消息年龄、retry 和 DLT。
- 只在根因处理后重放 DLT；保留原 `messageId`/`idempotencyKey`。
- Outbox 发布后设置 `published_at`；消费者完成业务事务后记录 `processed_messages` 再手工提交 offset。
- 不手工删除未知积压，也不直接把失败任务改为成功。

Kafka 快速检查：

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --all-groups --describe
docker compose exec -T postgres psql -U camel -d camel_arxiv \
  -c "select id,type,status,current_stage,progress_percent,heartbeat_at from jobs where type like 'ARXIV_%' order by created_at desc limit 20;"
docker compose exec -T postgres psql -U camel -d camel_arxiv \
  -c "select worker_id,status,current_job_id,last_seen_at from worker_heartbeats order by last_seen_at desc;"
```

正常拓扑包含八个 `camel.*.v1` 主题；`kafka-init` 必须成功，broker 的 `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`。`mail-worker` 不应消费 arXiv results 主题，且其 `mail-worker` profile 不注册 `/api/v1/**` 业务 Controller。

Phase 4 Source 快速检查：

```bash
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group camel-arxiv-workers-v1 --describe
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
- 结果消息永久校验失败时进入 `camel.arxiv.dlt.v1`。先按 Job/Message ID 验证其确为本平台消息和失败根因；未知积压不得删除。修复消费者后以原幂等键定点重放，重复结果会安全提交 offset。
- 联系人列表按论文筛选时选择该论文范围内最新映射；全局列表选择联系人全局最新映射。人工验证使用 `mappingId` 与 `expectedVersion`，409 表示应刷新而不是覆盖。

## 邮件安全操作

个性化生成链路的快速检查：

```bash
docker compose ps ray-head ray-worker personalization-worker
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --group camel-personalization-workers-v1 --describe
curl -fsS http://localhost:8080/api/v1/system/runtime
```

未设置 `PERSONALIZATION_API_KEY` 时应保持 `PERSONALIZATION_ENABLED=false`，运行状态返回 `generationReady=false`，活动生成请求返回 503 且不创建收件人。Compose 也接受旧 `OPENAI_API_KEY` / `OPENAI_API_BASE_URL`；原生进程使用 `PERSONALIZATION_*`。`PERSONALIZATION_PROVIDER` 可选 `openai` 或 `anthropic`，后者默认 `x-api-key` 鉴权，部分网关需设置 `PERSONALIZATION_API_AUTH_SCHEME=bearer`。启用后先用一个不超过数人的受控分组验收结构化草稿、退订变量、脚本净化、失败分类和幂等，再提高批量上限。提供方拒绝、截断或返回非约定工具时应核对提供方能力与政策，不执行其返回的其他工具。生成结束不会自动进入 SMTP 投递。

开发/CI 的发送验收只使用 Mailpit；IMAP/POP3 验收使用隔离的 GreenMail。以开发覆盖启动后，在 `/admin/mail-accounts` 创建 `mailpit:1025`、`PLAIN_LOCAL_ONLY` SMTP 账户，以及 `mail-test:3143` IMAP 或 `mail-test:3110` POP3 账户（登录用户 `researcher`，收件地址 `researcher@example.test`）。连接测试成功只表示握手/认证成功，测试邮件的 `SMTP_ACCEPTED` 只表示 Mailpit/SMTP 接受，不是最终投递。公网账户必须使用 STARTTLS 或隐式 TLS；不要用真实发送作为故障诊断手段。

本机验收顺序：

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
curl -fsS http://localhost:8080/api/v1/system/health
curl -fsS http://localhost:8025/api/v1/messages | jq '.total'
cd frontend
E2E_USER=local-super-admin E2E_PASSWORD='runtime-secret' npm run test:e2e
```

Edge E2E 要求账号同时拥有模板和 SMTP 管理权限；它会通过 API 自行建立带自动纯文本的净化模板与本机 SMTP 账户，完成桌面/移动交互后归档模板并删除 SMTP 账户。Mailpit 是本地捕获器，按测试环境保留策略清理消息；不要在共享或生产环境运行该套件。

私有图片仅存于默认 `template-assets` MinIO bucket（可由 `TEMPLATE_ASSET_BUCKET` 覆盖）。管理读取要求模板权限；编辑器和测试邮件只使用由 `TEMPLATE_ASSET_SIGNING_KEY_BASE64` 生成的应用签名 URL。Nginx 的 `/api/v1/template-assets/` location 必须保持 `access_log off`，Compose 契约会检查这一点。该密钥轮换会令既有模板中的图片 URL 失效，必须采用版本化双读/重签迁移，不能直接替换。复制含图模板会创建独立对象和签名，预期可在源模板归档后继续读取；模板归档后其自身签名读取返回 404，任何历史版本仍引用图片时删除返回 409。不要只删除数据库行或 MinIO 对象。排障不得输出 SMTP 密码、密文/nonce、完整收件地址、资产签名或 SMTP transcript；审计只允许记录 `passwordConfigured=[REDACTED]`、TLS 模式和稳定结果类别。

后续启用真实 SMTP 前必须确认活动已审批、Recipient 快照已冻结、抑制/退订已应用、频率上限有效、域名认证完成。紧急停止应暂停 Campaign 消费者并保留队列，不删除 Recipient/Attempt 审计记录。

## V9 分析索引修正维护窗口

已发布 V8 必须保持 checksum 不变。V9 在 PostgreSQL 事务中删除并重建两条索引以修正日期前导列；`DROP/CREATE INDEX` 会与写入竞争。生产升级必须先进入停写维护窗口：停止 `backend-api`、`mail-worker`、`arxiv-worker`，确认 Kafka 业务队列无进行中消息并记录积压，确认 PostgreSQL 有足够临时/索引磁盘空间和无长事务，再只启动一个迁移实例。V9 设置 `lock_timeout=5s`，仍有写事务时升级应快速失败，不得无限等待或反复重启争锁。成功后检查 `pg_stat_user_indexes` 中九个 `ix_*_analytics_*` 索引有效、应用只读对账和 `EXPLAIN` 命中，再恢复 API/Worker。若 5 秒锁超时，先找出并正常结束写入方；不得直接杀死未知事务。

## 保留与隐私

`data_retention_policies` 是保留任务的事实配置。原始追踪事件、IP/User-Agent 派生数据、审计和导出文件应按不同期限清理。打开事件只能解释为估算信号；报告中明确 SMTP accepted 不等同 delivered。

## 阶段验收基线

2026-08-05 实测：4 个 Flyway 迁移成功、50 张 public 表、9 个容器健康；Nginx/API/Mailpit/MinIO 宿主入口可达；后端、Python、Vue 全量质量门通过；桌面 1280×720 与移动 390×844 无横向溢出、零 console warning/error，移动抽屉关闭后焦点回到触发按钮。

Phase 2 同日实测：Flyway 更新到 V5；初始管理员强制改密；改密后旧 access/refresh 均为 401；refresh 单次轮换成功，旧值重放后整族失效且写入 `AUTH_REFRESH_REPLAY`；权限目录为 26 项；普通 `ADMIN` 的四类 `SUPER_ADMIN` 接管请求均为 403 并落审计，`VIEWER` 管理端请求为带 Trace ID 的 403；logout 后 refresh 为 401。用户/角色/审计三页通过桌面与 390×844 浏览器检查，页面无全局横向溢出且控制台零 warning/error。

Phase 3 于 2026-08-06 实测：Flyway V6 后为 53 张表、1 个物化视图；后端 132 tests/`clean check`/`bootJar`、Worker 31 tests/Ruff/MyPy、前端 27 tests/ESLint/`vue-tsc`/Vite build 全部通过。真实官方查询 `reliable agents` 返回结果，选中导入 Job `e42fd065-25ce-42d7-a639-090c6913625f` 完成并持久化论文 `2212.02256`。OAI 分类同步 Job `18fed311-b1af-4dd7-ae09-148a867aac71` 原子完成 166 个分类、6 个 alias 和 155 条描述，包含 `hep-th`、`math-ph`、`quant-ph` 等两段式 set；`mail-worker` 的业务健康 API 返回 404。任务详情显示 CREATED/STARTED/BATCH/PROGRESS/COMPLETED 事件和新鲜 Worker 心跳；发现、任务、论文页面桌面无 console error，390×844 发现页 `scrollWidth=clientWidth=390`。

Phase 4 于 2026-08-06 实测：Flyway V7；后端 153 tests、Worker 68 tests、前端 30 tests 及各自完整质量门通过，Compose 九服务和三个镜像契约通过。真实 Source Job `81f0900e-2865-4044-8c42-dff7899505db` 对 `2212.02256` 完成 TAR_GZIP 下载、解包、作者/联系人提取和原子回写，归档/展开尺寸为 488,729/913,762 bytes，临时目录清理确认。数据库密文不含 `@`、nonce 独立、HMAC 唯一；受权联系人列表脱敏、完整披露审计与 `mail-worker` 业务 API 404 均通过。桌面 1280×720 和移动 390×844 无页面级横向溢出，控制台零 warning/error；Worker RestartCount=0，验收后四个 arXiv 队列均为空。

Phase 5 于 2026-08-06 实测并经独立复核修正：Flyway V8 九条分析索引及追加式 V9 顺序修正生效；真实队列独立 SQL 与 API 的 canonical author、唯一联系人和映射数一致，导入日期、错误日期和最新映射查询计划均命中专用索引。受权 `dataset=domains` CSV 的 nosniff/文件名/审计正确；非法数据集 400、未认证 401、无 `user:read` 时用户选项为空。后端 170、Worker 68、前端 37 项测试和全部静态/构建门通过。桌面 1440×900 和移动 390×844 的三个分析页无横向溢出且控制台零 warning/error，联系人页不显示完整邮箱；九服务 healthy、应用容器 RestartCount=0、队列为空。详细口径与对账 SQL 见 [ANALYTICS.md](ANALYTICS.md)。

Phase 6 于 2026-08-07 实测并经两轮独立复核修正：Flyway V10/V11；后端 201 tests、前端 45 tests、Worker 68 tests 以及 ESLint/严格类型/生产构建/Ruff/MyPy 门全部通过。API 权限为匿名 401、Viewer 403，OpenAPI 为 58 paths；MinIO 私有图片、危险内容净化、版本/恢复/复制、渲染后 Header 边界、AES-GCM SMTP 密钥轮换与保留、公网主机禁用均经真实 API 验证。匿名签名图片读取实测 `200 image/png`，历史版本引用删除为 409；Mailpit MIME 使用绝对签名图片 URL，同时包含净化 HTML 与保留论文/退订 URL 的纯文本。Microsoft Edge 桌面/移动 3/3 场景通过，覆盖真实 PNG 上传/加载、图片深拷贝、归档源模板后副本继续加载、地址脱敏、移动抽屉、无横向溢出和零控制台错误；签名 capability 未进入 Nginx/应用日志。验收对象、模板、SMTP、邮件、审计和账号均已清理。
