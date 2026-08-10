CREATE INDEX ix_papers_analytics_imported
    ON papers (imported_at, primary_category_id, id)
    WHERE deleted_at IS NULL;

CREATE INDEX ix_paper_imports_analytics_date_job
    ON paper_imports (imported_at, job_id, paper_id);

CREATE INDEX ix_paper_categories_analytics_relation
    ON paper_categories (relation_type, category_id, paper_id);

CREATE INDEX ix_extraction_runs_analytics_latest
    ON extraction_runs (paper_id, started_at DESC, id)
    INCLUDE (status, source_format, files_inspected, contacts_found, duration_ms,
             archive_size_bytes, extracted_size_bytes, document_class, completed_at);

CREATE INDEX ix_extraction_runs_analytics_duration
    ON extraction_runs (completed_at, duration_ms, paper_id)
    WHERE duration_ms IS NOT NULL;

CREATE INDEX ix_contact_mappings_analytics_latest
    ON paper_author_contacts (paper_id, contact_id, created_at DESC, id)
    INCLUDE (extraction_run_id, paper_author_id, confidence, corresponding_author,
             verification_status, human_verified);

CREATE INDEX ix_extraction_evidence_analytics_rule
    ON extraction_evidence (rule_name, paper_author_contact_id);

CREATE INDEX ix_jobs_analytics_actor_date
    ON jobs (type, created_at, created_by, status, id)
    WHERE type LIKE 'ARXIV_%';

CREATE INDEX ix_job_errors_analytics_code
    ON job_errors (code, occurred_at, job_id);
