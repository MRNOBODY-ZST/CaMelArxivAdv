# Email preparation failure diagnostics

Future campaign delivery attempts can retain a safe preparation diagnostic when preparation fails before the SMTP sender is invoked. The diagnostic identifies the failing step and a broad error type, not an underlying root cause. Existing attempts are not replayed or backfilled.

## Reading a diagnostic

Each diagnostic contains only `stage`, `exceptionType`, `errorCode`, and an opaque UUID `traceRef`. Error types are limited to `VALIDATION`, `CRYPTO`, `PERSISTENCE`, `PARTNER`, and `OTHER`. The code is the stage name prefixed with `PREP_` and suffixed with `_FAILED`. No exception messages, class names, email addresses, message bodies, URLs, tokens, or credentials are recorded in these fields.

The stage identifies the operation that failed:

| Stage | Operation |
| --- | --- |
| `CLAIM_VALIDATION` | Claim identity, runtime eligibility, and preparation locks |
| `SOURCE_VALIDATION` | Initial or frozen content and stored capability checks |
| `TRACKING_RENDER` | Tracking token creation, content rewriting, and final rendering validation |
| `ARTIFACT_PERSIST` | Reading or writing stored tracking artifacts |
| `BODY_PERSIST` | Persisting validated bodies and their required preparation proof |
| `PARTNER_FINALIZE` | Finalizing an opted-in production partner invitation |
| `MESSAGE_BUILD` | Final content validation, recipient decryption, and outbound message construction |
| `SMTP_FENCE` | The final durable permission check and an invitation expiring before transport |

An inner diagnostic keeps its stage and trace reference when an outer operation handles the failure. Partner holds retain their existing hold reason; the diagnostic does not authorize retrying an invitation or sending a message. Safety preparation uses an inert partner preview and does not create a real invitation.

## Persistence and missing history

The diagnostic is saved with the accepted-lease failure or hold settlement, after the preparation transaction has rolled back. A rejected lease does not create a diagnostic. If saving the diagnostic fails, the settlement also rolls back and the executor reports failure rather than success; reconciliation may still be required.

Production delivery history exposes an optional `preparationDiagnostic` and `preparationDiagnosticExplanation`. Safety message history includes all actual attempts in attempt-number order, with the same diagnostic fields. Existing acceptance, failure, retry, and feedback facts remain separate.

`ROOT_CAUSE_UNKNOWN` means a failed preparation or partner-hold attempt has no retained diagnostic. It does not identify the historical cause or imply that a particular fix applies. Healthy, active, and SMTP-only attempts do not receive this explanation merely because a diagnostic is absent. Historical records are not rewritten to infer a cause or make a failed task appear successful.

## Preparation and SMTP outcomes

Preparation diagnostics do not classify transport errors. SMTP acceptance, explicit temporary rejection, permanent rejection, and an unknown post-DATA outcome keep their existing meanings and retry rules. Acceptance is not proof of inbox delivery, and a later bounce remains a separate feedback count. A final permission check returning false means no work and no send, not a new preparation failure.

A future bulk parent fails when a child fails or is canceled; a search shortfall is supplementary information, not the delivery failure cause. Bounce-only feedback does not fail an otherwise completed child. Later feedback does not restart terminal parents. Operators should inspect the attempt status, hold reason, and safe diagnostic together and reconcile uncertain outcomes without replaying old failed or canceled work.
