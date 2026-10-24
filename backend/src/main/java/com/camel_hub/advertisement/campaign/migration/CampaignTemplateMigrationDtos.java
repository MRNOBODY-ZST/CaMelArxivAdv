package com.camel_hub.advertisement.campaign.migration;

import java.util.List;
import java.util.UUID;

/** Public migration protocol: never exposes addresses, HMACs, bodies or private URLs. */
public final class CampaignTemplateMigrationDtos {
    private CampaignTemplateMigrationDtos() { }
    public record PreviewCommand(UUID targetTemplateId, UUID targetTemplateVersionId, List<UUID> recipientIds) { }
    public record MigrationCommand(long expectedLockVersion, UUID targetTemplateId, UUID targetTemplateVersionId,
            long expectedTemplateLockVersion, List<UUID> recipientIds, String expectedMembershipDigest, String idempotencyKey) { }
    public record MigrationReason(String code, int count) { }
    public record SelectionCounts(long historicalSelected, long actionableQueued, long transferred, long migrationBlocked) { }
    public record MigrationPreview(UUID sourceCampaignId, long expectedLockVersion, UUID targetTemplateId,
            UUID targetTemplateVersionId, long expectedTemplateLockVersion, String expectedMembershipDigest,
            List<UUID> recipientIds, SelectionCounts counts, boolean ready, List<MigrationReason> reasons) { }
    public record MigrationResult(UUID id, UUID sourceCampaignId, UUID targetCampaignId, UUID targetTemplateId,
            UUID targetTemplateVersionId, int selectedCount, int migratedCount, String status, List<MigrationReason> reasons) { }
}
