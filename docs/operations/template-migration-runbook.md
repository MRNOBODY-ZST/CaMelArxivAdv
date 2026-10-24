# 未发送模板迁移：仅草稿协议

迁移不是发送。保持来源 PAUSED 和旧正文、正式尝试、安全试发、回传、未知结果及归属证据；只把服务端确认未正式尝试且符合安全条件的精确名单复制到独立 DRAFT。正常/安全发送最后数据库围栏、当前账户/正文与所有权检查必须在全部执行实例生效，才能启用 `app.campaign.template-migrations.enabled`。

## 冻结、预检、提交

1. 在私有证据中冻结 Actor、来源、不可变模板版本、双方乐观锁版本、精确 recipient UUID 集合及服务端 membership digest。每来源最多 120 位、100 篇论文；不得用数量替代名单或补齐、减少、替换。
2. 核对原正文/attempt/试发/unknown/回传历史指纹。预检响应必须匹配当前选择及版本；预检不是执行授权，提交仍核验数据库锁和最新资格。空名单不会自动全选。
3. `MIGRATED` 表示独立草稿；`CONFIG_UPDATED` 仅用于空且原始的草稿；`NO_MAIL` 不生成邮件；`BLOCKED` 不创建目标。整个流程不审核、批准、排期、启动、恢复来源或周计划。

## 原键对账

浏览器通过持久恢复记录/Web Lock 在 POST 前保存原 Actor、来源及完整命令。超时、连接中断、5xx 等未知结果只按原键 GET；刷新、重开或换标签页不授权重发。404、账号/结果不匹配及损坏/丢失记录均不能清空重来。保留原记录人工核验。明确原 POST 的非提交拒绝才允许精确条件释放；409 必须重新预检。

`scripts/migrate-unsent-template-drafts.py` 是多来源协调器，默认只预检。v1 私有 manifest 的 partial sources 总数必须恰为 137；先预检全部剩余来源，再逐项提交。它不是 UI 超限单批按钮或发送工具。真实名单须独立授权，不能入库。父目录要求 0700，manifest/journal 要求 0600；Token 只交互读取到进程内存。每个 POST 前 fsync 原键 journal，之后核验原 Actor/original-key GET；未知项阻止后续项。`reconcile` 只接受原 journal，不重建或重放 POST。

## 历史与兼容回滚

`TRANSFERRED` 是行政所有权，不是 SMTP 成功、取消或历史状态重写。目标 recipient 保存来源/内容证明；历史尝试及统计分母保留原归属。不能删除旧 attempt/unknown 以取得资格。周计划方向、上限、时间、账户、启用状态及 occurrence 不变。

回滚只能选理解 transfer、prepared-content proof 和 normal/safety 最后围栏的兼容版本。独立授权后停止新入口，排空/核验全部执行实例，再选择版本；不能恢复绕过围栏的旧镜像、删除新增表、重置 journal 或自动恢复活动。

## 隔离验证表

| Gate | 必需证据 | 不可替代项 |
| --- | --- | --- |
| 身份 | commit/tree、锁文件、命令/exit/测试数/skip | 私有旧树通过记录 |
| 后端/worker | Python 3.12 `uv sync --locked`、绝对 R2_PYTHON；`clean check bootJar`、完整 pytest/ruff/mypy | skip、共享数据库、旧 `.venv` |
| 启动 | 实际 api+persistence/Flyway、独立 PG/Redis/Kafka、root/readiness、空表/topic、至少一周轮询间隔、负向业务调用 | disabled context、Mailpit 空箱或开关单独结论 |
| 浏览器 | 真实控件跨页、权限/409/timeout/原键、合成 HTML；40+70+27 和真实 CLI 协调器 | 单个 137 面板、真实账号/链接 |
| 镜像/公开 | Compose、四服务 build、候选 image IDs、内容审计、人工 inventory pin、全树/新历史扫描 | 覆盖共享 `:dev`、忽略 build、绝对保密声明 |

保留警告、失败和 fixture 修复证据。通过不等于部署、实际名单迁移、发信授权或整体无漏洞。
