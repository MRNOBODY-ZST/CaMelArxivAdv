-- Preserve the immutable V8 checksum and correct the two operational analytics paths
-- in an additive migration. Run this transactional migration in the documented
-- analytics-index maintenance window; the short lock timeout fails safely if a writer
-- is still active instead of waiting indefinitely.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30min';

DROP INDEX IF EXISTS ix_jobs_analytics_actor_date;
CREATE INDEX ix_jobs_analytics_actor_date
    ON jobs (created_at, type, created_by, status, id)
    WHERE type LIKE 'ARXIV_%';

DROP INDEX IF EXISTS ix_job_errors_analytics_code;
CREATE INDEX ix_job_errors_analytics_code
    ON job_errors (occurred_at, code, job_id);
