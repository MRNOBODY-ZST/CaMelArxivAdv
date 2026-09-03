-- Normalize any pre-release rows before making the IMAP event contract structural.
ALTER TABLE campaign_safety_runs
    ADD COLUMN mailbox_account_id UUID REFERENCES mailbox_accounts(id) ON DELETE SET NULL;

UPDATE campaign_safety_runs safety
SET mailbox_account_id = campaign.mailbox_account_id
FROM campaigns campaign
WHERE campaign.id = safety.campaign_id;

CREATE INDEX ix_campaign_safety_runs_mailbox
    ON campaign_safety_runs (mailbox_account_id, created_at DESC, id)
    WHERE mailbox_account_id IS NOT NULL;

UPDATE mailbox_inbound_events
SET inbound_type = 'UNMATCHED',
    referenced_message_id = NULL,
    campaign_recipient_id = NULL,
    safety_message_id = NULL,
    diagnostic_code = NULL,
    permanent = NULL
WHERE (inbound_type = 'UNMATCHED' AND (
          referenced_message_id IS NOT NULL
          OR campaign_recipient_id IS NOT NULL
          OR safety_message_id IS NOT NULL
          OR diagnostic_code IS NOT NULL
          OR permanent IS NOT NULL))
   OR (inbound_type <> 'UNMATCHED' AND (
          referenced_message_id IS NULL
          OR (campaign_recipient_id IS NULL) = (safety_message_id IS NULL)));

UPDATE mailbox_inbound_events
SET diagnostic_code = NULL,
    permanent = NULL
WHERE inbound_type <> 'BOUNCE';

UPDATE mailbox_inbound_events
SET permanent = false
WHERE inbound_type = 'BOUNCE' AND permanent IS NULL;

UPDATE campaign_safety_events
SET diagnostic_code = NULL
WHERE event_type IN ('REPLY', 'AUTO_REPLY');

ALTER TABLE mailbox_inbound_events
    DROP CONSTRAINT mailbox_inbound_events_campaign_recipient_id_fkey,
    DROP CONSTRAINT mailbox_inbound_events_safety_message_id_fkey,
    ADD CONSTRAINT mailbox_inbound_events_campaign_recipient_id_fkey
        FOREIGN KEY (campaign_recipient_id) REFERENCES campaign_recipients(id) ON DELETE CASCADE,
    ADD CONSTRAINT mailbox_inbound_events_safety_message_id_fkey
        FOREIGN KEY (safety_message_id) REFERENCES campaign_safety_messages(id) ON DELETE CASCADE;

ALTER TABLE mailbox_inbound_events
    ADD CONSTRAINT ck_mailbox_inbound_event_semantics CHECK (
        (inbound_type = 'UNMATCHED'
            AND referenced_message_id IS NULL
            AND campaign_recipient_id IS NULL
            AND safety_message_id IS NULL)
        OR (inbound_type <> 'UNMATCHED'
            AND referenced_message_id IS NOT NULL
            AND ((campaign_recipient_id IS NOT NULL AND safety_message_id IS NULL)
                OR (campaign_recipient_id IS NULL AND safety_message_id IS NOT NULL)))
    ),
    ADD CONSTRAINT ck_mailbox_inbound_event_bounce_fields CHECK (
        (inbound_type = 'BOUNCE' AND permanent IS NOT NULL)
        OR (inbound_type <> 'BOUNCE' AND diagnostic_code IS NULL AND permanent IS NULL)
    );

ALTER TABLE campaign_safety_events
    ADD CONSTRAINT ck_campaign_safety_event_inbound_diagnostics CHECK (
        event_type = 'BOUNCE'
        OR event_type NOT IN ('REPLY', 'AUTO_REPLY')
        OR diagnostic_code IS NULL
    );

CREATE INDEX ix_mailbox_inbound_events_safety
    ON mailbox_inbound_events (safety_message_id, created_at DESC)
    WHERE safety_message_id IS NOT NULL;
