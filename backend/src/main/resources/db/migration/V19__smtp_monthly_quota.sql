ALTER TABLE smtp_accounts
    ADD COLUMN per_month_limit INTEGER,
    ADD CONSTRAINT ck_smtp_month_limit CHECK (
        per_month_limit IS NULL OR per_month_limit >= per_day_limit
    );

COMMENT ON COLUMN smtp_accounts.per_month_limit IS
    'Optional UTC calendar-month cap shared by production, safety, diagnostic and template sends. NULL retains legacy behavior. Daily limits use a rolling 24-hour window.';

CREATE INDEX ix_mail_send_account_quota
    ON mail_send_records (smtp_account_id, created_at DESC)
    WHERE status IN ('SENDING', 'SMTP_ACCEPTED', 'UNKNOWN');
