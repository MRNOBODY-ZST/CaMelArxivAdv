package com.camel_hub.advertisement.campaign.migration;

import com.camel_hub.advertisement.campaign.CampaignValidationException;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.*;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationRepository.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Pure, fail-closed rules for the exact supplied list; never selects or hydrates recipients. */
public final class CampaignTemplateMigrationPolicy {
    public List<UUID> canonicalRecipients(List<UUID> ids) {
        if (ids == null || ids.size() > 120 || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size()) throw new CampaignValidationException("Exact recipient IDs must be unique, non-null and at most 120");
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    public void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) throw new CampaignValidationException("Invalid migration idempotency key");
    }

    public String membershipDigest(MigrationSnapshot snapshot) {
        var ids = canonicalRecipients(snapshot.recipients().stream().map(FrozenRecipient::id).toList());
        if (snapshot.recipients().stream().anyMatch(r -> !snapshot.campaign().id().equals(r.campaignId())))
            throw new CampaignValidationException("Recipient is outside the source campaign");
        if (snapshot.recipients().stream().map(FrozenRecipient::paperId).filter(Objects::nonNull).distinct().count() > 100)
            throw new CampaignValidationException("Exact selection exceeds 100 papers");
        List<Object> fields = new ArrayList<>();
        fields.add("campaign-template-membership-v1");
        var c = snapshot.campaign();
        Collections.addAll(fields, c.id(), c.lockVersion(), c.status(), c.templateId(), c.templateVersionId(), c.generationStatus(), c.evidenceDigest(), c.automaticOwnership(), c.parentRunnable());
        var t = snapshot.template();
        Collections.addAll(fields, t.id(), t.versionId(), t.lockVersion(), t.status(), t.currentVersion(), t.contentDigest());
        var s = snapshot.sender();
        Collections.addAll(fields, s.smtpId(), s.smtpLockVersion(), s.mailboxId(), s.mailboxLockVersion(), s.configurationDigest(), s.ready());
        fields.add(ids.size());
        snapshot.recipients().stream().sorted(Comparator.comparing(r -> r.id().toString())).forEach(r -> {
            Collections.addAll(fields, r.id(), r.campaignId(), r.contactId(), r.paperId(), r.authorId(), r.emailHmac(), r.frozenTargetDigest(), r.bodyDigest(),
                    r.status(), r.attemptCount(), r.personalizationStatus(), r.formalSideEffects(), r.safetyActiveOrUnknown(), r.transferred(),
                    r.sourceAvailable(), r.eligible(), r.formalEvidenceDigest(), r.safetyEvidenceDigest(), r.sourceEvidenceDigest());
            var target = r.frozenTarget();
            Collections.addAll(fields, target.emailCiphertextHex(), target.emailNonceHex(), target.emailDomain(), target.authorName(), target.paperTitle(),
                    target.paperAbstract(), target.arxivId(), target.category(), target.organization());
            var source = r.source();
            Collections.addAll(fields, source.evidenceId(), source.paperAuthorId(), source.mappedAuthorId(), source.confidence(), source.humanVerified(),
                    source.verificationStatus(), source.identityTrusted(), source.authorName(), source.organization(), source.paperTitle(), source.paperAbstract(), source.arxivId());
            var reservation = r.reservation();
            fields.add(reservation != null);
            if (reservation != null) Collections.addAll(fields, reservation.runId(), reservation.campaignId(), reservation.recipientId(), reservation.outcome());
        });
        return digest(fields);
    }

    public String requestDigest(UUID sourceId, UUID actorId, MigrationCommand command, MigrationSnapshot snapshot) {
        return requestDigest(sourceId, actorId, command, membershipDigest(snapshot));
    }

    /** Stored proof is the original snapshot digest, never a post-migration snapshot. */
    public String requestDigest(UUID sourceId, UUID actorId, MigrationCommand command, String originalMembershipDigest) {
        validateKey(command.idempotencyKey());
        List<Object> fields = new ArrayList<>();
        Collections.addAll(fields, "campaign-template-request-v1", sourceId, actorId, command.expectedLockVersion(),
                command.targetTemplateId(), command.targetTemplateVersionId(), command.expectedTemplateLockVersion(),
                command.expectedMembershipDigest(), command.idempotencyKey(), originalMembershipDigest);
        var ids = canonicalRecipients(command.recipientIds());
        fields.add(ids.size()); fields.addAll(ids);
        return digest(fields);
    }

    public List<MigrationReason> blockers(MigrationSnapshot snapshot) {
        membershipDigest(snapshot); // Bounds and ownership cannot silently become reason-counted subsets.
        Map<String,Integer> reasons = new TreeMap<>();
        if (!List.of("DRAFT", "PAUSED").contains(snapshot.campaign().status())) add(reasons, "SOURCE_STATUS");
        if (snapshot.campaign().parentRunnable()) add(reasons, "PARENT_ACTIVE");
        if (List.of("QUEUED", "RUNNING").contains(snapshot.campaign().generationStatus())) add(reasons, "GENERATION_ACTIVE");
        if (!"ACTIVE".equals(snapshot.template().status()) || !snapshot.template().currentVersion()) add(reasons, "TARGET_TEMPLATE_UNAVAILABLE");
        if (!snapshot.sender().ready()) add(reasons, "SENDER_NOT_READY");
        for (var recipient : snapshot.recipients()) {
            if (List.of("QUEUED", "RUNNING").contains(recipient.personalizationStatus())) add(reasons, "GENERATION_ACTIVE");
            if (!"QUEUED".equals(recipient.status())) add(reasons, "NOT_QUEUED");
            if (recipient.attemptCount() != 0 || recipient.formalSideEffects()) add(reasons, "FORMAL_SIDE_EFFECTS");
            if (recipient.safetyActiveOrUnknown()) add(reasons, "SAFETY_ACTIVE_OR_UNKNOWN");
            if (recipient.transferred()) add(reasons, "ALREADY_TRANSFERRED");
            if (!recipient.sourceAvailable()) add(reasons, "SOURCE_UNAVAILABLE");
            if (!recipient.eligible()) add(reasons, "RECIPIENT_INELIGIBLE");
            var owner = recipient.reservation();
            if (owner != null && (!owner.campaignId().equals(recipient.campaignId()) || !owner.recipientId().equals(recipient.id()))) add(reasons, "RESERVATION_OWNER_MISMATCH");
            if (owner != null && !"QUEUED".equals(owner.outcome())) add(reasons, "RESERVATION_NOT_QUEUED");
        }
        return reasons.entrySet().stream().map(e -> new MigrationReason(e.getKey(), e.getValue())).toList();
    }

    /** Disjoint exact-selection counts; transferred history has administrative precedence. */
    public SelectionCounts selectionCounts(MigrationSnapshot snapshot) {
        membershipDigest(snapshot);
        long actionable=0,transferred=0,blocked=0;
        for(var recipient:snapshot.recipients()) {
            if(recipient.transferred())transferred++;
            else if(blockers(new MigrationSnapshot(snapshot.campaign(),snapshot.template(),snapshot.sender(),List.of(recipient))).isEmpty())actionable++;
            else blocked++;
        }
        return new SelectionCounts(snapshot.recipients().size(),actionable,transferred,blocked);
    }
    private void add(Map<String,Integer> reasons, String code) { reasons.merge(code, 1, Integer::sum); }

    /** Each fixed field is a signed 32-bit byte length and UTF-8 value; null is -1, never an empty string. */
    static String digest(List<?> fields) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            for (Object field : fields) {
                if (field == null) { out.writeInt(-1); continue; }
                byte[] encoded = field.toString().getBytes(StandardCharsets.UTF_8);
                out.writeInt(encoded.length); out.write(encoded);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException e) { throw new IllegalStateException("Unable to digest migration evidence", e); }
    }
}
