# SMTP account quotas

`perMonthLimit` is an optional SMTP account field. A configured value must be at least `perDayLimit`; for the SendPulse account use `perDayLimit: 400` and `perMonthLimit: 12000`.

- Minute, hour, day, and recipient-domain-hour limits use rolling windows. The day window is the preceding 24 hours.
- The month window is a UTC calendar month, from 00:00 UTC on its first day through the beginning of the next month. In China Standard Time the reset is 08:00 on the first day.
- When several windows are full, the next eligible send time is the latest release time. A monthly reset does not reset the rolling daily quota.

For an account with `perMonthLimit`, production campaigns, safety runs, SMTP diagnostics, and template test sends share the same account quota. In-flight reservations, SMTP-accepted messages, and messages with an unknown outcome all consume capacity. Definite failed attempts release their capacity. Connections that send no message do not consume quota.

All send paths lock the same SMTP account row, check shared history, and commit their reservation before performing SMTP I/O. Concurrent workers and test sends cannot both reserve the last available slot. Campaign and safety queues defer to the quota release time; an immediate diagnostic or template test returns an SMTP conflict with that UTC release timestamp and does not attempt SMTP.

Quota counters use this application's durable records and are per configured SMTP account. Provider-side usage outside this account's application history is not available to this limiter.

## Compatibility

The migration leaves existing accounts' `per_month_limit` as `NULL`. They retain their previous production/safety rolling limits and existing diagnostic behavior. Create requests with an omitted or null `perMonthLimit` keep this legacy behavior.

Update requests with an omitted or null `perMonthLimit` preserve the account's existing monthly cap, so an older client cannot silently remove it. A supplied positive value changes the cap. Once configured, null is not a command to disable the cap.
