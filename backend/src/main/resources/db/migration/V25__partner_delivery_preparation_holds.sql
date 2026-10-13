ALTER TABLE campaign_recipients ADD COLUMN partner_invite_hold_reason VARCHAR(80);
ALTER TABLE delivery_attempts
    ADD COLUMN partner_preparation_pending BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN smtp_started_at TIMESTAMPTZ;
ALTER TABLE campaign_recipients ADD CONSTRAINT ck_partner_invite_hold_reason CHECK (
    partner_invite_hold_reason IS NULL OR partner_invite_hold_reason IN (
        'DISABLED', 'HELD', 'EXPIRED', 'CLAIMED', 'INVALID_INPUT', 'UNAVAILABLE', 'PREPARATION_INTERRUPTED'
    )
);
CREATE INDEX ix_campaign_partner_invite_holds ON campaign_recipients(campaign_id)
    WHERE partner_invite_hold_reason IS NOT NULL;
