package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.campaign.partner.PartnerInviteException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

import java.io.PrintWriter;
import java.io.StringWriter;
import javax.crypto.AEADBadTagException;

import static com.camel_hub.advertisement.campaign.delivery.PreparationDiagnostic.*;
import static org.assertj.core.api.Assertions.*;

class PreparationDiagnosticTest {
    private static final String SECRET = "synthetic-secret@example.test https://private.test/?token=PRIVATE body=PRIVATE";

    @ParameterizedTest @EnumSource(PreparationStage.class)
    void stageFailureHasOnlyAllowlistedDiagnostic(PreparationStage stage) throws Exception {
        for (boolean synchronous : new boolean[]{true, false}) {
            var raw = new IllegalArgumentException(SECRET);
            Throwable error = catchThrowable(() -> at(stage, () -> {
                if (synchronous) throw raw;
                return Mono.error(raw);
            }).block());
            assertThat(error).isInstanceOf(PreparationFailure.class);
            var diagnostic = diagnostic(error);
            assertThat(diagnostic.stage()).isEqualTo(stage);
            assertThat(diagnostic.exceptionType()).isEqualTo(SafeExceptionType.VALIDATION);
            assertThat(diagnostic.errorCode().name()).isEqualTo("PREP_" + stage.name() + "_FAILED");
            assertThat(diagnostic.traceRef()).isNotNull();
            assertThat(original(error)).isSameAs(raw);
            assertThat(error.getCause()).isNull();
            var output = new StringWriter(); error.printStackTrace(new PrintWriter(output));
            assertThat(output.toString()).doesNotContain("synthetic-secret", "PRIVATE", "private.test");
            assertThat(new ObjectMapper().writeValueAsString(diagnostic)).doesNotContain("synthetic-secret", "PRIVATE", "cause", "message");
        }
    }

    @Test void innerStageAndCryptoTypeSurvivePartnerRemapAndOuterWrapper() {
        var raw = new IllegalArgumentException(SECRET, new AEADBadTagException(SECRET));
        Throwable inner = catchThrowable(() -> at(PreparationStage.MESSAGE_BUILD, () -> Mono.error(raw)).block());
        var held = new PartnerInviteException(PartnerInviteException.Reason.UNAVAILABLE);
        Throwable outer = catchThrowable(() -> at(PreparationStage.PARTNER_FINALIZE,
                () -> Mono.error(remap(inner, held))).block());
        assertThat(diagnostic(outer)).isSameAs(diagnostic(inner));
        assertThat(diagnostic(outer).exceptionType()).isEqualTo(SafeExceptionType.CRYPTO);
        assertThat(original(outer)).isSameAs(held);
        assertThat(outer.getCause()).isNull();
    }

    @Test void classifiesKnownTypesWithoutInferringFromMessagesAndStopsCycles() {
        assertType(new com.camel_hub.advertisement.campaign.CampaignValidationException(SECRET), SafeExceptionType.VALIDATION);
        assertType(new org.springframework.dao.DataAccessResourceFailureException(SECRET), SafeExceptionType.PERSISTENCE);
        assertType(new PartnerInviteException(PartnerInviteException.Reason.HELD), SafeExceptionType.PARTNER);
        assertType(new IllegalStateException("GeneralSecurityException SQL IllegalArgumentException"), SafeExceptionType.OTHER);
        var cycle = new RuntimeException(SECRET); var second = new RuntimeException(SECRET, cycle); cycle.initCause(second);
        assertType(cycle, SafeExceptionType.OTHER);
        Throwable context = catchThrowable(() -> crypto(PreparationStage.TRACKING_RENDER, () -> {throw new IllegalStateException(SECRET);}));
        assertThat(diagnostic(context).exceptionType()).isEqualTo(SafeExceptionType.CRYPTO);
        Throwable validation = catchThrowable(() -> crypto(PreparationStage.TRACKING_RENDER, () -> {throw new IllegalArgumentException(SECRET);}));
        assertThat(diagnostic(validation).exceptionType()).isEqualTo(SafeExceptionType.VALIDATION);
    }

    @Test void successfulEmptyAndDeferredOperationsAreNotFailures() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var work = at(PreparationStage.SMTP_FENCE, () -> {calls.incrementAndGet(); return Mono.just(false);});
        assertThat(calls).hasValue(0);assertThat(work.block()).isFalse();assertThat(calls).hasValue(1);
        assertThat(at(PreparationStage.CLAIM_VALIDATION, Mono::empty).block()).isNull();
    }

    private void assertType(Throwable raw, SafeExceptionType expected) {
        Throwable error = catchThrowable(() -> at(PreparationStage.SOURCE_VALIDATION, () -> Mono.error(raw)).block());
        assertThat(diagnostic(error).exceptionType()).isEqualTo(expected);
    }
}
