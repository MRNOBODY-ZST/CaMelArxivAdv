package com.camel_hub.advertisement.campaign.delivery;

import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;
import java.util.UUID;

/** Joins accepted-lease settlement; never owns or detaches a transaction. */
public final class PreparationDiagnosticRepository {
    private final DatabaseClient database;
    public PreparationDiagnosticRepository(DatabaseClient database) { this.database = database; }

    public Mono<Void> record(UUID attemptId, String deliveryKind, PreparationDiagnostic diagnostic) {
        return Mono.defer(() -> {
            String column = switch (deliveryKind) {
                case "PRODUCTION" -> "production_attempt_id";
                case "SAFETY" -> "safety_attempt_id";
                default -> throw new IllegalArgumentException("Invalid diagnostic delivery kind");
            };
            return TransactionSynchronizationManager.forCurrentTransaction()
                    .filter(TransactionSynchronizationManager::isActualTransactionActive)
                    .switchIfEmpty(Mono.error(new IllegalStateException("Diagnostic requires settlement transaction")))
                    .then(database.sql("INSERT INTO campaign_preparation_diagnostics (" + column
                            + ",stage,exception_type,error_code,trace_ref) VALUES (:attempt,:stage,:type,:code,:trace)")
                            .bind("attempt", attemptId).bind("stage", diagnostic.stage().name())
                            .bind("type", diagnostic.exceptionType().name()).bind("code", diagnostic.errorCode().name())
                            .bind("trace", diagnostic.traceRef()).fetch().rowsUpdated())
                    .flatMap(rows -> rows == 1 ? Mono.empty()
                            : Mono.error(new IllegalStateException("Diagnostic insert was not applied")));
        });
    }

    public static PreparationDiagnostic read(Row row) {
        UUID trace = row.get("preparation_trace_ref", UUID.class);
        return trace == null ? null : new PreparationDiagnostic(
                PreparationDiagnostic.PreparationStage.valueOf(row.get("preparation_stage", String.class)),
                PreparationDiagnostic.SafeExceptionType.valueOf(row.get("preparation_exception_type", String.class)),
                PreparationDiagnostic.PreparationErrorCode.valueOf(row.get("preparation_error_code", String.class)), trace);
    }
}
