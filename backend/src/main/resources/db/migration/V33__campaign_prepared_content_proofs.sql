-- Receipts are created only by validated preparation; never backfill old prepared bodies.
ALTER TABLE delivery_attempts ADD CONSTRAINT uk_delivery_attempt_recipient_number_id
    UNIQUE(campaign_recipient_id,attempt_number,id);
ALTER TABLE outreach_content_reviews ADD CONSTRAINT uk_outreach_review_campaign_recipient
    UNIQUE(campaign_id,recipient_id);

CREATE TABLE campaign_prepared_content_proofs (
    campaign_id UUID NOT NULL,
    recipient_id UUID NOT NULL REFERENCES campaign_recipient_transfers(target_recipient_id),
    attempt_id UUID PRIMARY KEY,
    attempt_number INTEGER NOT NULL CHECK(attempt_number>0),
    source_hash BYTEA NOT NULL CHECK(octet_length(source_hash)=32),
    review_content_hash BYTEA NOT NULL CHECK(octet_length(review_content_hash)=32),
    prepared_content_hash BYTEA NOT NULL CHECK(octet_length(prepared_content_hash)=32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(campaign_id,recipient_id) REFERENCES campaign_recipients(campaign_id,id),
    FOREIGN KEY(campaign_id,recipient_id) REFERENCES outreach_content_reviews(campaign_id,recipient_id),
    FOREIGN KEY(recipient_id,attempt_number,attempt_id)
        REFERENCES delivery_attempts(campaign_recipient_id,attempt_number,id),
    UNIQUE(recipient_id,attempt_number)
);
COMMENT ON TABLE campaign_prepared_content_proofs IS
    'Actual validated tracking preparation receipts, bound to reviewed migration content and delivery attempt. No raw content/capabilities, backfill, or cascading history deletion.';
