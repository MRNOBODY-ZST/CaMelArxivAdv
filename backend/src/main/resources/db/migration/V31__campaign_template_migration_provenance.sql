-- Legacy rows remain unverified: never invent original hashes or provenance.
ALTER TABLE campaign_recipient_transfers
    ADD COLUMN source_hash BYTEA CHECK(source_hash IS NULL OR octet_length(source_hash)=32),
    ADD COLUMN content_hash BYTEA CHECK(content_hash IS NULL OR octet_length(content_hash)=32),
    ADD COLUMN provenance JSONB CHECK(provenance IS NULL OR (
        jsonb_typeof(provenance)='object' AND octet_length(provenance::text)<=2048
        AND provenance ?& ARRAY['paperId','evidenceId','hydratedFields','capturedAt']
        AND provenance - ARRAY['paperId','evidenceId','hydratedFields','capturedAt'] = '{}'::jsonb
        AND jsonb_typeof(provenance->'hydratedFields')='array'
        AND provenance->'hydratedFields' <@ '["paperAbstract","arxivId"]'::jsonb
        AND jsonb_typeof(provenance->'paperId')='string'
        AND jsonb_typeof(provenance->'evidenceId')='string'
        AND jsonb_typeof(provenance->'capturedAt')='string'
    ));
COMMENT ON COLUMN campaign_recipient_transfers.provenance IS 'Target-only captured same-paper input provenance. Null legacy proof is unverified, not a safety exemption.';
