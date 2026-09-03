ALTER TABLE campaign_safety_runs
    ADD COLUMN from_name_snapshot VARCHAR(160),
    ADD COLUMN from_email_snapshot VARCHAR(320),
    ADD COLUMN reply_to_snapshot VARCHAR(320),
    ADD COLUMN tracking_opens_enabled BOOLEAN,
    ADD COLUMN tracking_clicks_enabled BOOLEAN;

UPDATE campaign_safety_runs safety
SET from_name_snapshot = campaign.from_name,
    from_email_snapshot = campaign.from_email,
    reply_to_snapshot = campaign.reply_to,
    tracking_opens_enabled = campaign.tracking_opens_enabled,
    tracking_clicks_enabled = campaign.tracking_clicks_enabled
FROM campaigns campaign
WHERE campaign.id = safety.campaign_id;

ALTER TABLE campaign_safety_runs
    ALTER COLUMN from_name_snapshot SET NOT NULL,
    ALTER COLUMN from_email_snapshot SET NOT NULL,
    ALTER COLUMN reply_to_snapshot SET NOT NULL,
    ALTER COLUMN tracking_opens_enabled SET NOT NULL,
    ALTER COLUMN tracking_clicks_enabled SET NOT NULL,
    ADD CONSTRAINT ck_campaign_safety_sender_snapshot CHECK (
        btrim(from_name_snapshot) <> ''
        AND btrim(from_email_snapshot) <> ''
        AND btrim(reply_to_snapshot) <> ''
        AND from_name_snapshot !~ '[[:cntrl:]]'
        AND from_email_snapshot !~ '[[:cntrl:]]'
        AND reply_to_snapshot !~ '[[:cntrl:]]'
    );

CREATE UNIQUE INDEX uk_campaign_safety_one_active_run
    ON campaign_safety_runs (campaign_id)
    WHERE status IN ('QUEUED', 'RUNNING');
