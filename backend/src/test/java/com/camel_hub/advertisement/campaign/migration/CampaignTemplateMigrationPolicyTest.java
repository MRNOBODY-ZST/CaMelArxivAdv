package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.CampaignValidationException;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationRepository.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class CampaignTemplateMigrationPolicyTest {
    private final CampaignTemplateMigrationPolicy policy = new CampaignTemplateMigrationPolicy();
    private static final UUID SOURCE = id(1), TEMPLATE = id(2), VERSION = id(3);

    // Catches silent deduplication/truncation, membership filtering, and order-sensitive digests.
    @Test void canonicalMembershipAndBounds() {
        var first = recipient(10, 20);
        var second = recipient(11, 21);
        var snapshot = snapshot(List.of(first, second));
        assertThat(policy.canonicalRecipients(List.of(second.id(), first.id()))).containsExactly(first.id(), second.id());
        assertThat(policy.membershipDigest(snapshot)).isEqualTo(policy.membershipDigest(with(snapshot, "recipients", List.of(second, first))));
        assertThatThrownBy(() -> policy.canonicalRecipients(List.of(first.id(), first.id()))).isInstanceOf(CampaignValidationException.class);
        assertThatThrownBy(() -> policy.canonicalRecipients(Arrays.asList(first.id(), null))).isInstanceOf(CampaignValidationException.class);
        assertThatThrownBy(() -> policy.canonicalRecipients(null)).isInstanceOf(CampaignValidationException.class);
        assertThatThrownBy(() -> policy.canonicalRecipients(IntStream.range(0, 121).mapToObj(i -> id(100 + i)).toList())).isInstanceOf(CampaignValidationException.class);
        assertThatThrownBy(() -> policy.membershipDigest(snapshot(List.of(with(first, "campaignId", id(99)))))).isInstanceOf(CampaignValidationException.class);
        assertThatThrownBy(() -> policy.membershipDigest(snapshot(IntStream.range(0, 101).mapToObj(i -> recipient(100 + i, 500 + i)).toList()))).isInstanceOf(CampaignValidationException.class);
        assertThat(policy.blockers(snapshot)).isEmpty();
    }

    // Catches treating a formal failure as unsent, or blanket-blocking terminal safety/generation history.
    @Test void attemptAndSafetyClassification() {
        var clean = recipient(10, 20);
        assertThat(policy.blockers(snapshot(List.of(clean)))).isEmpty();
        for (String status : List.of("TEMPORARY_FAILURE", "PERMANENT_FAILURE", "OUTCOME_UNKNOWN", "SMTP_ACCEPTED", "CONNECTING")) {
            assertThat(policy.blockers(snapshot(List.of(with(clean, "status", status))))).extracting("code").contains("NOT_QUEUED");
        }
        assertThat(policy.blockers(snapshot(List.of(with(clean, "attemptCount", 1))))).extracting("code").contains("FORMAL_SIDE_EFFECTS");
        assertThat(policy.blockers(snapshot(List.of(with(clean, "formalSideEffects", true))))).extracting("code").contains("FORMAL_SIDE_EFFECTS");
        assertThat(policy.blockers(snapshot(List.of(with(clean, "safetyActiveOrUnknown", true))))).extracting("code").contains("SAFETY_ACTIVE_OR_UNKNOWN");
        var terminalSafety = with(clean, "safetyEvidenceDigest", "terminal-accepted-safety-evidence");
        assertThat(policy.blockers(snapshot(List.of(terminalSafety)))).isEmpty();
        assertThat(policy.blockers(with(snapshot(List.of(clean)), "campaign", with(snapshot(List.of(clean)).campaign(), "generationStatus", "FAILED")))).isEmpty();
        for (String status : List.of("QUEUED", "RUNNING")) {
            assertThat(policy.blockers(with(snapshot(List.of(clean)), "campaign", with(snapshot(List.of(clean)).campaign(), "generationStatus", status))))
                    .extracting("code").contains("GENERATION_ACTIVE");
            assertThat(policy.blockers(snapshot(List.of(with(clean, "personalizationStatus", status))))).extracting("code").contains("GENERATION_ACTIVE");
        }
        for (String field : List.of("transferred", "sourceAvailable", "eligible")) {
            boolean value = field.equals("transferred");
            assertThat(policy.blockers(snapshot(List.of(with(clean, field, value))))).isNotEmpty();
        }
    }

    // Every isolated frozen-evidence edit must invalidate the approval digest, including reservation ownership.
    @Test void digestBindsAllFrozenEvidence() {
        var baseline = snapshot(List.of(recipient(10, 20)));
        String digest = policy.membershipDigest(baseline);
        assertThat(digest).matches("[0-9a-f]{64}");
        for (String group : List.of("campaign", "template", "sender")) {
            Object evidence = component(baseline, group);
            for (var field : evidence.getClass().getRecordComponents()) {
                if (group.equals("campaign") && field.getName().equals("id")) {
                    assertThatThrownBy(() -> policy.membershipDigest(with(baseline, group, mutate(evidence, field)))).isInstanceOf(CampaignValidationException.class);
                    continue;
                }
                assertThat(policy.membershipDigest(with(baseline, group, mutate(evidence, field))))
                        .as(group + "." + field.getName()).isNotEqualTo(digest);
            }
        }
        var recipient = baseline.recipients().getFirst();
        for (var field : recipient.getClass().getRecordComponents()) {
            if (List.of("id", "campaignId", "paperId").contains(field.getName())) continue;
            assertThat(policy.membershipDigest(with(baseline, "recipients", List.of(mutate(recipient, field)))))
                    .as("recipient." + field.getName()).isNotEqualTo(digest);
        }
        for (var field : recipient.reservation().getClass().getRecordComponents()) {
            assertThat(policy.membershipDigest(with(baseline, "recipients", List.of(with(recipient, "reservation", mutate(recipient.reservation(), field))))))
                    .as("reservation." + field.getName()).isNotEqualTo(digest);
        }
        for (String nested : List.of("frozenTarget", "source")) {
            Object evidence = component(recipient, nested);
            for (var field : evidence.getClass().getRecordComponents()) {
                if (field.getName().equals("capturedAt")) {
                    assertThat(policy.membershipDigest(with(baseline,"recipients",List.of(with(recipient,nested,with(evidence,"capturedAt",java.time.Instant.parse("2026-10-12T00:00:00Z"))))))).isEqualTo(digest);
                    continue; // Capture time is provenance, not mutable membership authority.
                }
                assertThat(policy.membershipDigest(with(baseline, "recipients", List.of(with(recipient, nested, mutate(evidence, field))))))
                        .as(nested + "." + field.getName()).isNotEqualTo(digest);
            }
        }
        assertThat(policy.membershipDigest(with(baseline, "recipients", List.of(with(recipient, "id", id(12)))))).isNotEqualTo(digest);
        assertThat(policy.membershipDigest(with(baseline, "recipients", List.of(with(recipient, "paperId", id(22)))))).isNotEqualTo(digest);
    }

    @Test void requestDigestCanonicalizesIdsAndBindsActorAndCommand() {
        var snapshot = snapshot(List.of(recipient(10, 20), recipient(11, 21)));
        var command = new CampaignTemplateMigrationDtos.MigrationCommand(7, TEMPLATE, VERSION, 8,
                List.of(id(11), id(10)), policy.membershipDigest(snapshot), "key_1");
        String digest = policy.requestDigest(SOURCE, id(30), command, snapshot);
        assertThat(policy.requestDigest(SOURCE,id(30),command,policy.membershipDigest(snapshot))).isEqualTo(digest);
        assertThat(policy.requestDigest(SOURCE, id(30), with(command, "recipientIds", List.of(id(10), id(11))), snapshot)).isEqualTo(digest);
        assertThat(policy.requestDigest(SOURCE, id(31), command, snapshot)).isNotEqualTo(digest);
        for (var field : command.getClass().getRecordComponents()) {
            if (field.getName().equals("recipientIds")) continue;
            assertThat(policy.requestDigest(SOURCE, id(30), mutate(command, field), snapshot)).as(field.getName()).isNotEqualTo(digest);
        }
        for (String key : List.of("", "a.b", "a b", "中文", "a".repeat(129))) {
            assertThatThrownBy(() -> policy.validateKey(key)).isInstanceOf(CampaignValidationException.class);
        }
    }

    @Test void digestLengthsPreventConcatenationAndNullCollisions() {
        assertThat(CampaignTemplateMigrationPolicy.digest(List.of("ab","c"))).isNotEqualTo(CampaignTemplateMigrationPolicy.digest(List.of("a","bc")));
        assertThat(CampaignTemplateMigrationPolicy.digest(Arrays.asList(null,""))).isNotEqualTo(CampaignTemplateMigrationPolicy.digest(Arrays.asList("",null)));
    }

    @Test void terminalOrUnknownReservationCannotBeOverriddenByPristineRecipientFlags() {
        var pristine=recipient(10,20);
        assertThat(policy.blockers(snapshot(List.of(with(pristine,"reservation",null))))).isEmpty();
        assertThat(policy.blockers(snapshot(List.of(pristine)))).isEmpty();
        for (String outcome:List.of("SMTP_ACCEPTED","OUTCOME_UNKNOWN","PERMANENT_FAILURE","TEMPORARY_FAILURE","CANCELED","BOUNCED","unrecognized")) {
            var reserved=with(pristine,"reservation",with(pristine.reservation(),"outcome",outcome));
            assertThat(policy.blockers(snapshot(List.of(reserved)))).as(outcome).extracting("code").contains("RESERVATION_NOT_QUEUED");
        }
    }

    private MigrationSnapshot snapshot(List<FrozenRecipient> recipients) {
        return new MigrationSnapshot(new CampaignEvidence(SOURCE, 7, "PAUSED", id(4), id(5), "FAILED", "campaign-evidence", false),
                new TemplateEvidence(TEMPLATE, VERSION, 8, "ACTIVE", true, "template-content"),
                new SenderEvidence(id(6), 9, id(7), 10L, "sender-config", true), recipients);
    }
    private FrozenRecipient recipient(int recipient, int paper) {
        return new FrozenRecipient(id(recipient), SOURCE, id(40), id(paper), id(41), "01".repeat(32),
                new FrozenTarget("ciphertext", "nonce", "example.test", "Author", "Paper", "Abstract", "2601.00001", "cs.AI", "Organization"), "frozen-target", "body-digest",
                "QUEUED", 0, "FAILED", false, false, false, true, true, "formal-evidence", "safety-evidence", "source-evidence",
                new Reservation(id(50), SOURCE, id(recipient), "QUEUED"),
                new SourceEvidence(id(60), id(61), id(41), "HIGH", false, "UNVERIFIED", true, "Author", "Organization", "Paper", "Abstract", "2601.00001"));
    }
    private static UUID id(int n) { return new UUID(0, n); }
    private static Object component(Object record, String name) {
        try { return record.getClass().getMethod(name).invoke(record); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked") private static <T> T with(T record, String name, Object value) {
        try {
            var fields = record.getClass().getRecordComponents();
            Object[] values = Arrays.stream(fields).map(f -> f.getName().equals(name) ? value : component(record, f.getName())).toArray();
            return (T) record.getClass().getDeclaredConstructor(Arrays.stream(fields).map(RecordComponent::getType).toArray(Class[]::new)).newInstance(values);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static <T> T mutate(T record, RecordComponent field) {
        Object old = component(record, field.getName());
        Object changed = switch (old) {
            case String s -> s + "changed";
            case UUID u -> new UUID(u.getMostSignificantBits(), u.getLeastSignificantBits() + 1);
            case Long l -> l + 1;
            case Integer i -> i + 1;
            case Boolean b -> !b;
            case Reservation r -> with(r, "runId", id(51));
            case FrozenTarget t -> with(t, "paperTitle", "Changed paper");
            case SourceEvidence s -> with(s, "confidence", "LOW");
            default -> throw new AssertionError("Uncovered digest field " + field.getName());
        };
        return with(record, field.getName(), changed);
    }
}
