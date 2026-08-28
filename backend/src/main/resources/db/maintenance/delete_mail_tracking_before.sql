\set ON_ERROR_STOP on

-- Run manually with psql -v cutoff='2026-05-01T00:00:00Z' -f this-file.sql.
-- Missing, invalid or non-past cutoffs abort before any deletion.
BEGIN;
CREATE TEMP TABLE mail_tracking_retention_cutoff (
    cutoff TIMESTAMPTZ NOT NULL CHECK (cutoff < CURRENT_TIMESTAMP)
) ON COMMIT DROP;
INSERT INTO mail_tracking_retention_cutoff (cutoff) VALUES (:'cutoff'::timestamptz);

WITH removed AS (
    DELETE FROM mail_send_records
    WHERE created_at < (SELECT cutoff FROM mail_tracking_retention_cutoff)
    RETURNING id
)
SELECT count(*) AS deleted_mail_send_records FROM removed;
-- mail_open_events cascade from only the deleted records; no other subsystem is touched.
COMMIT;
