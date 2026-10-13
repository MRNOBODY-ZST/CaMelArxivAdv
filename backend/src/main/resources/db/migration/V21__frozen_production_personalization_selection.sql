ALTER TABLE campaigns
    ADD COLUMN personalization_recipient_limit INTEGER,
    ADD CONSTRAINT ck_campaign_personalization_recipient_limit CHECK (
        personalization_recipient_limit IS NULL OR (
            personalization_recipient_limit BETWEEN 1 AND 120
            AND personalization_paper_ids IS NULL
        )
    );

ALTER TABLE campaign_recipients
    ADD COLUMN paper_abstract_snapshot TEXT,
    ADD COLUMN arxiv_id_snapshot VARCHAR(48);

COMMENT ON COLUMN campaigns.personalization_recipient_limit IS
    'Explicit exact production selection size; recipient membership and public inputs are frozen on first generation. Null retains legacy/paper selection behavior.';
COMMENT ON COLUMN campaign_recipients.paper_abstract_snapshot IS
    'Public abstract frozen for production personalization retries; never reload from a changed paper.';
