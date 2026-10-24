-- Future attempt evidence only. Existing attempts deliberately remain without a diagnosis.
CREATE TABLE campaign_preparation_diagnostics (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    production_attempt_id uuid UNIQUE REFERENCES delivery_attempts(id),
    safety_attempt_id uuid UNIQUE REFERENCES campaign_safety_attempts(id),
    stage text NOT NULL CHECK (stage IN ('CLAIM_VALIDATION','SOURCE_VALIDATION','TRACKING_RENDER',
        'ARTIFACT_PERSIST','BODY_PERSIST','PARTNER_FINALIZE','MESSAGE_BUILD','SMTP_FENCE')),
    exception_type text NOT NULL CHECK (exception_type IN ('VALIDATION','CRYPTO','PERSISTENCE','PARTNER','OTHER')),
    error_code text NOT NULL CHECK (error_code = 'PREP_' || stage || '_FAILED'),
    trace_ref uuid NOT NULL,
    CHECK ((production_attempt_id IS NOT NULL) <> (safety_attempt_id IS NOT NULL))
);
