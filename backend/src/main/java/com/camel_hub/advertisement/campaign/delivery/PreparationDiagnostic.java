package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.campaign.partner.PartnerInviteException;
import io.r2dbc.spi.R2dbcException;
import org.springframework.dao.DataAccessException;
import reactor.core.publisher.Mono;

import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** The only persistable preparation evidence: no exception text or content. */
public record PreparationDiagnostic(PreparationStage stage, SafeExceptionType exceptionType,
        PreparationErrorCode errorCode, UUID traceRef) {
    public PreparationDiagnostic {
        Objects.requireNonNull(stage); Objects.requireNonNull(exceptionType);
        Objects.requireNonNull(errorCode); Objects.requireNonNull(traceRef);
        if (!errorCode.name().equals("PREP_" + stage.name() + "_FAILED"))
            throw new IllegalArgumentException("Preparation diagnostic stage/code mismatch");
    }

    public enum PreparationStage {
        CLAIM_VALIDATION, SOURCE_VALIDATION, TRACKING_RENDER, ARTIFACT_PERSIST,
        BODY_PERSIST, PARTNER_FINALIZE, MESSAGE_BUILD, SMTP_FENCE
    }
    public enum SafeExceptionType { VALIDATION, CRYPTO, PERSISTENCE, PARTNER, OTHER }
    public enum PreparationErrorCode {
        PREP_CLAIM_VALIDATION_FAILED, PREP_SOURCE_VALIDATION_FAILED, PREP_TRACKING_RENDER_FAILED,
        PREP_ARTIFACT_PERSIST_FAILED, PREP_BODY_PERSIST_FAILED, PREP_PARTNER_FINALIZE_FAILED,
        PREP_MESSAGE_BUILD_FAILED, PREP_SMTP_FENCE_FAILED
    }

    public static <T> Mono<T> at(PreparationStage stage, Supplier<Mono<T>> operation) {
        return Mono.defer(operation).onErrorMap(error -> wrap(stage, error, false));
    }

    /** Synchronous sub-operation, preserving the surrounding subscription and worker. */
    public static <T> T during(PreparationStage stage, Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException error) { throw wrap(stage, error, false); }
    }

    /** Only for actual crypto operations whose implementation discards the JCA cause. */
    public static <T> T crypto(PreparationStage stage, Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException error) { throw wrap(stage, error, true); }
    }

    private static PreparationFailure wrap(PreparationStage stage, Throwable error, boolean crypto) {
        if (error instanceof PreparationFailure failure) return failure;
        SafeExceptionType type = classify(error);
        if (crypto && type == SafeExceptionType.OTHER) type = SafeExceptionType.CRYPTO;
        return new PreparationFailure(new PreparationDiagnostic(stage, type,
                PreparationErrorCode.valueOf("PREP_" + stage.name() + "_FAILED"), UUID.randomUUID()), error, error);
    }

    private static SafeExceptionType classify(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        SafeExceptionType result = SafeExceptionType.OTHER;
        for (int depth = 0; error != null && depth < 8 && seen.add(error); depth++, error = error.getCause()) {
            if (error instanceof GeneralSecurityException) return SafeExceptionType.CRYPTO;
            if (error instanceof PartnerInviteException) result = SafeExceptionType.PARTNER;
            else if ((error instanceof DataAccessException || error instanceof R2dbcException)
                    && result != SafeExceptionType.PARTNER) result = SafeExceptionType.PERSISTENCE;
            else if ((error instanceof IllegalArgumentException
                    || error instanceof com.camel_hub.advertisement.campaign.CampaignValidationException
                    || error instanceof jakarta.validation.ConstraintViolationException)
                    && result == SafeExceptionType.OTHER)
                result = SafeExceptionType.VALIDATION;
        }
        return result;
    }

    public static PreparationDiagnostic diagnostic(Throwable error) {
        return error instanceof PreparationFailure failure ? failure.diagnostic : null;
    }

    /** Semantic settlement cause, deliberately not exposed as a Throwable cause chain. */
    public static Throwable original(Throwable error) {
        return error instanceof PreparationFailure failure ? failure.settlement : error;
    }

    public static Throwable remap(Throwable error, Throwable settlement) {
        return error instanceof PreparationFailure failure
                ? new PreparationFailure(failure.diagnostic, failure.original, settlement) : settlement;
    }

    public static String explanation(String status, String category, PreparationDiagnostic diagnostic) {
        if (diagnostic != null || category == null || status == null
                || !Set.of("PERMANENT_FAILURE", "TEMPORARY_FAILURE", "CANCELED", "OUTCOME_UNKNOWN").contains(status)) return null;
        return Set.of("PREPARATION_FAILED", "PARTNER_INVITE_DISABLED", "PARTNER_INVITE_HELD",
                "PARTNER_INVITE_EXPIRED", "PARTNER_INVITE_CLAIMED", "PARTNER_INVITE_INVALID_INPUT",
                "PARTNER_INVITE_UNAVAILABLE", "PARTNER_INVITE_PREPARATION_INTERRUPTED").contains(category)
                ? "ROOT_CAUSE_UNKNOWN" : null;
    }

    public static final class PreparationFailure extends RuntimeException {
        private final PreparationDiagnostic diagnostic;
        private final Throwable original;
        private final Throwable settlement;
        private PreparationFailure(PreparationDiagnostic diagnostic, Throwable original, Throwable settlement) {
            super(diagnostic.errorCode().name(), null, false, false);
            this.diagnostic = diagnostic; this.original = original; this.settlement = settlement;
        }
    }
}
