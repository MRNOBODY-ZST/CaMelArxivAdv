ALTER TABLE campaigns
    ADD COLUMN recipient_policy VARCHAR(24) NOT NULL DEFAULT 'AUTHOR_VERIFIED',
    ADD COLUMN paper_contact_ids UUID[],
    ADD COLUMN paper_contact_recipient_count INTEGER,
    ADD CONSTRAINT ck_paper_contact_recipient_count CHECK (
        paper_contact_recipient_count IS NULL OR
        (recipient_policy = 'PAPER_CONTACT' AND paper_contact_recipient_count BETWEEN 1 AND 120)
    ),
    ADD CONSTRAINT ck_campaign_recipient_policy CHECK (
        (recipient_policy = 'AUTHOR_VERIFIED' AND paper_contact_ids IS NULL)
        OR (recipient_policy = 'PAPER_CONTACT' AND paper_contact_ids IS NOT NULL
            AND cardinality(paper_contact_ids) BETWEEN 1 AND 100
            AND personalization_paper_ids IS NULL AND personalization_recipient_limit IS NULL)
    );

ALTER TABLE campaign_recipients DROP CONSTRAINT ck_campaign_recipient_confidence;
ALTER TABLE campaign_recipients ADD CONSTRAINT ck_campaign_recipient_confidence
    CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW', 'UNMAPPED'));
