# Source admission、结果发布与重试

API 在新任务/重试事务中初始化 durable command identity。`SourceAdmissionRepository` 属于持久化装配；mailbox/service 由默认关闭的 `app.arxiv.source-admission.enabled` 控制。不开 mailbox 不应移除初始化依赖。

Worker 规范化指纹并严格核验 scope、类型、顺序、时钟、大小、租约及前缀。数据库确认的两次 issue/commit 转换和原始一次性本地 receipt 才授权调用；Redis 读回、TTL 消失、旧终态、旧协议或恢复旧条目不能重建/补发能力。Redis 丢失时保持数据库 hold，不从不可信快照恢复资格。

结果先 stage，再确认发布、推进已确认前缀及 terminal。发布/callback/取消/Kafka retry/DLT/offset 异常保留所需原状态，不把未知或失败伪造为未发现邮箱/成功解析。取消保证 admission 顺序，不保证物理 HTTP 瞬间原子中止；受控 Kafka 测试不是物理 broker exactly-once 证明。

重试保留旧 Job/attempt/结果和 lineage，并建立新合法身份及有界范围。不得重置计数/状态/Redis capability 或重放旧 terminal。官方 HTTPS、重定向复验、共享请求间隔、归档限制和临时清理策略不变。

发布/回滚需独立授权，核验全部 API、worker、消息生产/消费方的协议兼容性。schema additive 不代表旧执行程序安全；停止新入口并排空/核验旧执行实例，保留 authority/issue、结果和未决 journal，不 down-migrate 或删历史。

V34 邮件准备诊断仅为未来正式/安全 attempt 保存受限 stage、exception category、固定 error code、trace reference，不保存异常/cause/message、邮箱、正文、凭据或 capability URL。旧无诊断记录仍为 `ROOT_CAUSE_UNKNOWN`，不能推断历史根因、回填假诊断或自动重试。

公开源到候选的每个路径/模式/字节转换及当前/新历史例外须经私有人工审核和外部 inventory pin。扫描器是启发式及完整性校验，不自动批准或保证绝对隐私。构建产物/私有证据保留在发布范围外，不因 gitignore 获豁免。完整验证见[模板迁移 runbook](template-migration-runbook.md)。
