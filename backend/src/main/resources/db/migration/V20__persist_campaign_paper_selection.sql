ALTER TABLE campaigns
    ADD COLUMN personalization_paper_ids UUID[],
    ADD CONSTRAINT ck_campaign_personalization_papers CHECK (
        personalization_paper_ids IS NULL OR (
            array_ndims(personalization_paper_ids) = 1
            AND cardinality(personalization_paper_ids) BETWEEN 1 AND 20
            AND array_position(personalization_paper_ids, NULL) IS NULL
        )
    );

COMMENT ON COLUMN campaigns.personalization_paper_ids IS
    'Immutable explicit paper selection. NULL retains legacy segment-wide generation; retries reuse a saved selection.';
