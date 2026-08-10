# 数据统计口径与看板

## 事实源与日期队列

Phase 5 的事实源是 PostgreSQL 业务表，不使用前端模拟值。所有采集、论文和联系人看板先形成同一个论文队列：

- `papers.deleted_at IS NULL`；
- `papers.imported_at >= from 00:00:00Z`；
- `papers.imported_at < (to + 1 day) 00:00:00Z`。

因此 `from`/`to` 是包含首尾两天的 UTC 日历日期。默认最近 30 个含当天的 UTC 日期，最大范围 10 年。发表月和更新月图展示 `submitted_at`/`updated_at`，但其论文仍来自同一个导入日期队列，不能将这些图误解为按发表日期筛选。

分类、Primary/Cross-list、Job、操作用户、域名和置信度筛选均收窄同一论文队列。Campaign 与 SMTP Account 在 Phase 7–8 产生事实数据后启用；当前 UI 明确禁用，不用空表制造指标。

## 重跑与去重

- 采集统计按论文取 `extraction_runs` 中 `started_at,id` 最新的一条。
- 联系人统计先按 `paper_id,contact_id` 取最新映射，再应用域名/置信度筛选；不能先筛历史置信度再去重。映射作者通过 `paper_authors.author_id` 统计，不能把同一作者在多篇论文上的关联行当成多个作者。
- 联系人数量使用加密联系人 ID/HMAC 对应记录做去重，API 不需要解密邮箱。
- 分类发现率保留分子和分母；分母为 0 时比率固定返回 `0`，不会返回 `NaN` 或无穷大。

## 核心指标

| 指标 | 分子/值 | 分母 | 说明 |
|---|---:|---:|---|
| 已导入论文 | 队列内不同论文数 | — | 非软删除论文 |
| 解析覆盖率 | 最新状态为 `SUCCEEDED` 或 `PARTIALLY_SUCCEEDED` 的论文 | 已导入论文 | 每篇论文只计最新运行 |
| 邮箱发现率 | 有至少一个最新联系人映射的论文 | 已导入论文 | 不要求人工确认 |
| DOI 覆盖率 | `doi IS NOT NULL` 论文 | 已导入论文 | 不做外部 DOI 丰富 |
| 期刊引用覆盖率 | `journal_reference IS NOT NULL` 论文 | 已导入论文 | 来自 arXiv 元数据 |
| 每篇论文邮箱数 | 最新 paper-contact 映射数 | 已导入论文 | 同一邮箱跨论文分别构成映射 |
| 通讯作者率 | `corresponding_author=true` 的最新映射 | 最新映射 | 只使用提取/人工数据，不猜测 |
| 人工确认率 | `human_verified=true` 且 `CONFIRMED` | 最新映射 | `REJECTED` 不进入分子 |

“常见服务商 / 其他域名”只按维护的常见公共邮箱域名集合做平台推导，不代表学校、公司或机构归属。域名后缀不能作为机构身份断言。

## API 与导出

全部端点需要 `analytics:read`：

- `GET /api/v1/analytics/overview`
- `GET /api/v1/analytics/ingestion`
- `GET /api/v1/analytics/papers`
- `GET /api/v1/analytics/contacts`
- `GET /api/v1/analytics/filters`
- `GET /api/v1/analytics/{view}/export`

响应包含 `window.dateBasis=papers.imported_at`、`window.timezone=UTC` 和 `freshness`。空数据时为 `status=NO_DATA,dataThrough=null`；有事实时为 `status=CURRENT`，联系人验证时间与 Job/错误时间按相应页面纳入。每个比率指标同时返回 `numerator`、`denominator`、`value` 和定义，便于用 SQL 独立对账。

CSV 只导出聚合指标/序列，字符串执行 RFC 4180 双引号转义，并对 `= + - @` 开头的值加前置单引号，降低电子表格公式注入风险。每个视图只接受固定数据集：overview 的 metrics/daily-imported/primary-categories/funnel/active-jobs；ingestion 的 metrics/funnel/duration/daily-imported/extraction-statuses/worker-errors/job-throughput；papers 的 metrics/groups/archives/primary-categories/all-categories/cross-list-categories/category-relations/publication-months/update-months/author-counts/version-counts/source-formats；contacts 的 metrics/confidence/domains/domain-classes/category-discovery/document-classes/extraction-rules/reuse-buckets/coauthor-pairs。`all` 导出该视图全部响应数据。审计包含 Actor、视图、数据集、UTC 窗口和 Trace ID；不会包含完整邮箱。每张 ECharts 图可在浏览器导出白底 2× PNG。

## 查询与性能

Flyway V8 为导入日期、导入 Job、分类关系、最新提取、耗时分位、最新联系人映射、规则、操作用户和错误日期建立专用索引。查询使用有界日期队列、`DISTINCT ON` 最新记录和 PostgreSQL 聚合/`percentile_cont`，不会在请求路径扫描原始追踪事件。页面内聚合按顺序订阅，避免一个请求同时占满连接池。已发布的 V8 保持不可变；V9 以追加迁移修正 Job/错误索引顺序，并采用有 5 秒锁等待上限的事务索引构建。生产升级必须执行运维文档中的停写维护窗口。

Phase 5 直接读取事务事实以保证联系人人工确认后立即一致；`ingestion_daily_stats`、`contact_daily_stats`、`campaign_hourly_stats` 和 `link_daily_stats` 保留给后续高数据量刷新任务。引入缓存或预聚合时必须携带 `dataThrough/refreshedAt/status`，Redis 故障时只能回退 PostgreSQL，不能返回无新鲜度的旧数据。

## 前端行为

三个页面为 `/analytics/ingestion`、`/analytics/papers` 和 `/analytics/contacts`。筛选仅在“应用筛选”时写入 URL query，页面刷新和可分享链接可恢复同一条件。图表具备：

- 加载、空、错误状态；
- Tooltip、图例、ARIA 文本和 `ResizeObserver` 自适应；
- 类别柱状图、比例环图、漏斗横向柱图；
- 时间点少于 8 个时使用柱状图，达到 8 个后才使用折线；
- 导入与按状态任务吞吐序列补齐选定 UTC 日期中的零值；
- 路由复用、浏览器前进/后退会重新加载正确视图，过期并发响应不会覆盖新页面；
- ECharts JavaScript 动画遵循 `prefers-reduced-motion`；
- 移动端单列、指标横向滚动但页面本身无横向溢出。

## 独立 SQL 对账示例

```sql
WITH cohort AS (
  SELECT id
  FROM papers
  WHERE deleted_at IS NULL
    AND imported_at >= TIMESTAMPTZ '2026-08-01T00:00:00Z'
    AND imported_at <  TIMESTAMPTZ '2026-08-07T00:00:00Z'
), latest_runs AS (
  SELECT DISTINCT ON (er.paper_id) er.*
  FROM extraction_runs er JOIN cohort c ON c.id = er.paper_id
  ORDER BY er.paper_id, er.started_at DESC, er.id DESC
)
SELECT
  (SELECT count(*) FROM cohort) AS imported,
  count(*) FILTER (WHERE status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED')) AS parsed
FROM latest_runs;
```

验收时还应对 latest mapping、唯一 contact、分类分子/分母分别查询，并确认 API 数值一致，而不是只比较图形外观。
