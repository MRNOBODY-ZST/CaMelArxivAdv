package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.campaign.migration.CampaignTemplateMigrationDtos.SelectionCounts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import java.util.List;
import java.util.UUID;

/** UNSENT_OWNERSHIP_INVENTORY only. No locking, rendering, preview or send authority.
 * Correlated indexed reads keep historical aggregates and pagination independent of lineage.
 * Stored lineage is conservative evidence, not cryptographic/current-send certification. */
public final class CampaignSelectionInventory {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CampaignProductionSelectionPolicy policy;
    public CampaignSelectionInventory(CampaignProductionSelectionPolicy policy) {
        this.policy = java.util.Objects.requireNonNull(policy);
    }
    private CampaignSelectionInventory() { this.policy = null; }
    public static CampaignSelectionInventory unavailable() { return new CampaignSelectionInventory(); }
    public boolean available() { return policy != null; }
    public static final String COLUMNS = ", inventory.historical_selected, inventory.actionable_queued, inventory.transferred, inventory.migration_references ";

    public DatabaseClient.GenericExecuteSpec bind(DatabaseClient.GenericExecuteSpec query) {
        return policy == null ? query : query.bind("inventoryCutoff", policy.cooldownCutoff())
            .bind("inventoryExcluded", policy.excludedHmacs());
    }

    /** campaignId is an internal SQL column expression, never user input. */
    public String join(String campaignId) {
        if (!List.of("c.id", "campaign.id", "bc.campaign_id").contains(campaignId))
            throw new IllegalArgumentException("Unsupported inventory correlation");
        if (policy == null) return " CROSS JOIN LATERAL (SELECT NULL::bigint AS historical_selected, NULL::bigint AS actionable_queued, NULL::bigint AS transferred, NULL::text AS migration_references) inventory ";
        return """
            CROSS JOIN LATERAL (
              SELECT counts.*, (SELECT coalesce(jsonb_agg(jsonb_build_object(
                'migrationId',m.id,'sourceCampaignId',m.source_campaign_id,'targetCampaignId',m.target_campaign_id)
                ORDER BY m.id,m.source_campaign_id,m.target_campaign_id),'[]'::jsonb)::text
                FROM campaign_template_migrations m WHERE m.status='MIGRATED' AND m.target_campaign_id IS NOT NULL
                AND (m.source_campaign_id=CAMPAIGN_ID OR m.target_campaign_id=CAMPAIGN_ID)) AS migration_references
              FROM (
                SELECT count(*) AS historical_selected,
                  count(*) FILTER(WHERE outgoing.source_recipient_id IS NOT NULL) AS transferred,
                  count(*) FILTER(WHERE outgoing.source_recipient_id IS NULL AND
                    c.status IN ('DRAFT','PAUSED') AND c.generation_status NOT IN ('QUEUED','RUNNING')
                    AND NOT EXISTS(SELECT 1 FROM campaign_recipients active WHERE active.campaign_id=c.id AND active.personalization_status IN ('QUEUED','RUNNING'))
                    AND PARENT_CLEAR
                    AND r.status='QUEUED' AND r.attempt_count=0 AND FORMAL_CLEAR
                    AND NOT EXISTS(SELECT 1 FROM campaign_safety_runs sr WHERE sr.campaign_id=c.id AND sr.status IN ('QUEUED','RUNNING'))
                    AND NOT EXISTS(SELECT 1 FROM campaign_safety_messages sm JOIN campaign_safety_runs sr ON sr.id=sm.run_id
                      WHERE sr.campaign_id=c.id AND (sm.status IN ('QUEUED','CONNECTING','TEMPORARY_FAILURE','OUTCOME_UNKNOWN') OR sm.delivery_lease_hash IS NOT NULL OR sm.outcome_unknown_at IS NOT NULL))
                    AND NOT EXISTS(SELECT 1 FROM campaign_safety_attempts sa JOIN campaign_safety_messages sm ON sm.id=sa.safety_message_id
                      JOIN campaign_safety_runs sr ON sr.id=sm.run_id WHERE sr.campaign_id=c.id
                      AND (sa.status IN ('CONNECTING','OUTCOME_UNKNOWN') OR sa.outcome_unknown_reason IS NOT NULL))
                    AND contact.id IS NOT NULL AND contact.deleted_at IS NULL AND contact.syntax_valid AND NOT contact.example_address
                    AND contact.suppression_status='ACTIVE' AND contact.email_hmac=r.email_hmac
                    AND encode(r.email_hmac,'hex')<>ALL(:inventoryExcluded)
                    AND p.id IS NOT NULL AND evidence.id IS NOT NULL AND evidence.verification_status<>'REJECTED'
                    AND EXISTS(SELECT 1 FROM extraction_evidence ee WHERE ee.paper_author_contact_id=evidence.id)
                    AND length(trim(r.paper_title_snapshot)) BETWEEN 1 AND 2000 AND length(r.paper_title_snapshot)<=2000
                    AND length(trim(inputs.abstract_text)) BETWEEN 1 AND 20000 AND length(inputs.abstract_text)<=20000
                    AND inputs.arxiv_id ~ '^([0-9]{4}[.][0-9]{4,5}|[A-Za-z0-9.-]{1,40}/[0-9]{7})$'
                    AND NOT EXISTS(SELECT 1 FROM suppression_entries se WHERE se.email_hmac=r.email_hmac AND (se.expires_at IS NULL OR se.expires_at>now()))
                    AND NOT EXISTS(SELECT 1 FROM unsubscribe_records u WHERE u.email_hmac=r.email_hmac)
                    AND NOT EXISTS(SELECT 1 FROM campaign_exclusions ce WHERE ce.campaign_id=c.id AND ce.email_hmac=r.email_hmac)
                    AND NOT EXISTS(SELECT 1 FROM recipient_delivery_cooldowns cd WHERE cd.email_hmac=r.email_hmac AND cd.last_smtp_accepted_at>=:inventoryCutoff)
                    AND (incoming.target_recipient_id IS NULL OR proven.source_recipient_id IS NOT NULL)
                    AND NOT EXISTS(SELECT 1 FROM campaign_recipients other JOIN campaigns oc ON oc.id=other.campaign_id
                      WHERE other.email_hmac=r.email_hmac AND other.id<>r.id
                      AND other.id IS DISTINCT FROM proven.source_recipient_id
                      AND (other.status IN ('CONNECTING','OUTCOME_UNKNOWN') OR (oc.recipient_policy='PAPER_CONTACT'
                        AND oc.status IN ('DRAFT','READY_FOR_REVIEW','APPROVED','REJECTED','SCHEDULED','RUNNING','PAUSED')
                        AND other.status IN ('QUEUED','TEMPORARY_FAILURE'))))
                    AND NOT EXISTS(SELECT 1 FROM bulk_outreach_reservations br WHERE br.email_hmac=r.email_hmac
                      AND (br.campaign_id IS DISTINCT FROM coalesce(proven.source_campaign_id,r.campaign_id)
                        OR br.recipient_id IS DISTINCT FROM coalesce(proven.source_recipient_id,r.id) OR br.outcome IS DISTINCT FROM 'QUEUED'))
                    AND NOT EXISTS(SELECT 1 FROM bulk_outreach_children child WHERE child.campaign_id=coalesce(proven.source_campaign_id,r.campaign_id)
                      AND NOT EXISTS(SELECT 1 FROM bulk_outreach_reservations br WHERE br.email_hmac=r.email_hmac
                        AND br.run_id=child.run_id AND br.campaign_id=child.campaign_id
                        AND br.recipient_id=coalesce(proven.source_recipient_id,r.id) AND br.outcome='QUEUED'))
                  ) AS actionable_queued
                FROM campaign_recipients r JOIN campaigns c ON c.id=r.campaign_id
                LEFT JOIN contacts contact ON contact.id=r.contact_id
                LEFT JOIN papers p ON p.id=r.paper_id AND p.deleted_at IS NULL
                CROSS JOIN LATERAL (SELECT CASE WHEN nullif(trim(r.paper_abstract_snapshot),'') IS NULL THEN p.abstract_text ELSE r.paper_abstract_snapshot END AS abstract_text,
                  CASE WHEN nullif(trim(r.arxiv_id_snapshot),'') IS NULL THEN p.arxiv_id ELSE r.arxiv_id_snapshot END AS arxiv_id) inputs
                LEFT JOIN LATERAL (SELECT pac.* FROM paper_author_contacts pac JOIN extraction_runs er ON er.id=pac.extraction_run_id
                  AND er.paper_id=pac.paper_id AND er.status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED')
                  WHERE pac.contact_id=r.contact_id AND pac.paper_id=r.paper_id
                  ORDER BY er.completed_at DESC NULLS LAST,pac.created_at DESC,pac.id LIMIT 1) evidence ON true
                LEFT JOIN campaign_recipient_transfers outgoing ON outgoing.source_recipient_id=r.id
                LEFT JOIN campaign_recipient_transfers incoming ON incoming.target_recipient_id=r.id
                LEFT JOIN LATERAL (
                  SELECT t.source_recipient_id,t.source_campaign_id FROM campaign_recipient_transfers t
                  JOIN campaign_template_migrations m ON m.id=t.migration_id AND m.source_campaign_id=t.source_campaign_id
                    AND m.target_campaign_id=t.target_campaign_id AND m.status='MIGRATED'
                  JOIN campaign_recipients s ON s.id=t.source_recipient_id AND s.campaign_id=t.source_campaign_id
                  JOIN campaigns sc ON sc.id=s.campaign_id
                  JOIN outreach_content_reviews review ON review.campaign_id=r.campaign_id AND review.recipient_id=r.id
                  WHERE t.target_recipient_id=r.id AND t.target_campaign_id=r.campaign_id
                    AND c.template_id=m.target_template_id AND c.template_version_id=m.target_template_version_id
                    AND c.recipient_policy='PAPER_CONTACT' AND c.generation_provider='TEMPLATE' AND c.generation_model='exact-v1'
                    AND r.personalization_status='GENERATED' AND s.status='QUEUED' AND s.attempt_count=0
                    AND sc.status IN ('DRAFT','PAUSED') AND SOURCE_PARENT_CLEAR AND SOURCE_FORMAL_CLEAR
                    AND s.contact_id=r.contact_id AND s.paper_id=r.paper_id AND s.email_hmac=r.email_hmac
                    AND s.paper_title_snapshot IS NOT DISTINCT FROM r.paper_title_snapshot
                    AND (nullif(trim(s.paper_abstract_snapshot),'') IS NULL OR s.paper_abstract_snapshot=r.paper_abstract_snapshot)
                    AND (nullif(trim(s.arxiv_id_snapshot),'') IS NULL OR s.arxiv_id_snapshot=r.arxiv_id_snapshot)
                    AND s.category_snapshot IS NOT DISTINCT FROM r.category_snapshot
                    AND t.source_hash IS NOT NULL AND t.content_hash IS NOT NULL AND t.provenance IS NOT NULL
                    AND t.provenance->>'paperId'=r.paper_id::text AND length(t.provenance->>'capturedAt')>0
                    AND t.provenance->>'capturedAt' ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,9})?Z$'
                    AND pg_input_is_valid(t.provenance->>'capturedAt','timestamp with time zone')
                    AND EXISTS(SELECT 1 FROM paper_author_contacts captured WHERE captured.id::text=t.provenance->>'evidenceId'
                      AND captured.paper_id=r.paper_id AND captured.contact_id=r.contact_id)
                    AND review.policy_version='exact-v1' AND review.source_hash=t.source_hash AND review.content_hash=t.content_hash
                ) proven ON true
                WHERE r.campaign_id=CAMPAIGN_ID
              ) counts
            ) inventory
            """.replace("SOURCE_PARENT_CLEAR", parentClear("sc")).replace("PARENT_CLEAR", parentClear("c"))
                .replace("SOURCE_FORMAL_CLEAR", formalClear("s")).replace("FORMAL_CLEAR", formalClear("r"))
                .replaceAll("\\bc\\.", "owner.").replace("campaigns c ON", "campaigns owner ON")
                .replace("CAMPAIGN_ID", campaignId);
    }

    private static String parentClear(String alias) {
        return "NOT EXISTS(SELECT 1 FROM outreach_runs root WHERE root.campaign_id="+alias+".id AND root.status IN ('ACTIVE','SENDING')) "
            +"AND NOT EXISTS(SELECT 1 FROM bulk_outreach_children child JOIN bulk_outreach_runs root ON root.id=child.run_id WHERE child.campaign_id="+alias+".id AND root.status IN ('ACTIVE','SENDING'))";
    }
    private static String formalClear(String alias) {
        return """
            R.first_attempt_at IS NULL AND R.smtp_accepted_at IS NULL AND R.final_failure_at IS NULL
            AND R.delivery_lease_hash IS NULL AND R.delivery_lease_expires_at IS NULL AND R.rfc_message_id IS NULL
            AND R.outcome_unknown_at IS NULL AND R.outcome_unknown_reason IS NULL AND R.replied_at IS NULL
            AND R.first_open_at IS NULL AND R.first_click_at IS NULL AND R.partner_invite_hold_reason IS NULL
            AND NOT EXISTS(SELECT 1 FROM delivery_attempts a WHERE a.campaign_recipient_id=R.id)
            AND NOT EXISTS(SELECT 1 FROM tracking_tokens tok WHERE tok.campaign_recipient_id=R.id)
            AND NOT EXISTS(SELECT 1 FROM tracking_events evt WHERE evt.campaign_recipient_id=R.id)
            AND NOT EXISTS(SELECT 1 FROM mailbox_inbound_events evt WHERE evt.campaign_recipient_id=R.id)
            """.replace("R.",alias+".");
    }
    public static SelectionCounts counts(Row row) {
        Long historical=row.get("historical_selected",Long.class);
        if(historical==null) return null;
        long actionable=row.get("actionable_queued",Long.class),transferred=row.get("transferred",Long.class);
        return new SelectionCounts(historical,actionable,transferred,historical-transferred-actionable);
    }
    public static List<MigrationReference> references(Row row) {
        String json=row.get("migration_references",String.class);
        if(json==null) return null;
        try { return JSON.readValue(json,new TypeReference<List<MigrationReference>>(){}); }
        catch(Exception e) { throw new IllegalStateException("Invalid administrative migration references"); }
    }
    public static RecipientTransfer transfer(Row row) {
        UUID migration=row.get("transfer_migration_id",UUID.class);
        return migration==null?null:new RecipientTransfer(row.get("transfer_status",String.class),migration,
            row.get("transfer_source",UUID.class),row.get("transfer_target",UUID.class));
    }
    public record MigrationReference(UUID migrationId,UUID sourceCampaignId,UUID targetCampaignId) { }
    public record RecipientTransfer(String status,UUID migrationId,UUID sourceCampaignId,UUID targetCampaignId) { }
}
