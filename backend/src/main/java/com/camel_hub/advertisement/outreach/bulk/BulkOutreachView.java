package com.camel_hub.advertisement.outreach.bulk;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.SelectionCounts;
import com.camel_hub.advertisement.campaign.CampaignSelectionInventory.MigrationReference;
import com.camel_hub.advertisement.campaign.CampaignSelectionInventory.RecipientTransfer;


public record BulkOutreachView(UUID id, String name, int targetPapers, String status, String stage,
        String message, int paperCount, int extractedCount, int excludedCount, int noContactCount,
        int selectedCount, int smtpAccepted, int bouncedCount, int failedCount, int unknownCount,
        UUID smtpAccountId, UUID templateId, boolean incremental, boolean acceptDeliverabilityRisk,
        long lockVersion, Instant createdAt, Instant updatedAt, List<String> allowedActions,
        List<Child> children, SelectionCounts selectionCounts, List<MigrationReference> migrationReferences) {
    public BulkOutreachView(UUID id, String name, int targetPapers, String status, String stage,
        String message, int paperCount, int extractedCount, int excludedCount, int noContactCount,
        int selectedCount, int smtpAccepted, int bouncedCount, int failedCount, int unknownCount,
        UUID smtpAccountId, UUID templateId, boolean incremental, boolean acceptDeliverabilityRisk,
        long lockVersion, Instant createdAt, Instant updatedAt, List<String> allowedActions,
        List<Child> children) {
        this(id,name,targetPapers,status,stage,message,paperCount,extractedCount,excludedCount,noContactCount,selectedCount,smtpAccepted,bouncedCount,failedCount,unknownCount,smtpAccountId,templateId,incremental,acceptDeliverabilityRisk,lockVersion,createdAt,updatedAt,allowedActions,children,null,null);
    }
    public record Child(UUID id, String status, int selectedCount, int smtpAccepted,
            int bouncedCount, int failedCount, int unknownCount, SelectionCounts selectionCounts, List<MigrationReference> migrationReferences) {
        public Child(UUID id,String status,int selectedCount,int smtpAccepted,int bouncedCount,int failedCount,int unknownCount) {
            this(id,status,selectedCount,smtpAccepted,bouncedCount,failedCount,unknownCount,null,null);
        }
    }
}
