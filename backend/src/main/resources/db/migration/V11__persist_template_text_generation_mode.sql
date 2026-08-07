ALTER TABLE email_template_versions
    ADD COLUMN auto_generate_text BOOLEAN NOT NULL DEFAULT false;
