package com.camel_hub.advertisement.campaign;

import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Run-adapter metadata only: campaign membership and generation remain owned by CampaignRepository. */
public final class OutreachTemplateCampaignRepository {
	private final DatabaseClient db;

	public OutreachTemplateCampaignRepository(DatabaseClient db) { this.db = db; }

	public Mono<Sender> sender(UUID smtpId, UUID requestedMailboxId) {
		return db.sql("""
				SELECT smtp.default_from_name, smtp.from_email, smtp.reply_to, mailbox.id AS mailbox_id
				FROM smtp_accounts smtp JOIN mailbox_accounts mailbox
				  ON lower(trim(mailbox.username)) = lower(trim(smtp.reply_to))
				WHERE smtp.id=:smtpId AND smtp.enabled AND smtp.last_test_status='SUCCEEDED'
				  AND smtp.last_tested_at IS NOT NULL AND smtp.last_tested_at >= smtp.updated_at
				  AND mailbox.protocol='IMAP' AND mailbox.enabled AND mailbox.last_test_status='SUCCEEDED'
				  AND mailbox.last_tested_at IS NOT NULL AND mailbox.last_tested_at >= mailbox.updated_at
				  AND (:automatic OR mailbox.id=:mailboxId)
				ORDER BY mailbox.id LIMIT 2 FOR SHARE OF smtp, mailbox
				""").bind("smtpId", smtpId).bind("automatic", requestedMailboxId == null)
				.bind("mailboxId", requestedMailboxId == null ? new UUID(0, 0) : requestedMailboxId)
				.map((r,m) -> new Sender(r.get("default_from_name", String.class), r.get("from_email", String.class),
						r.get("reply_to", String.class), r.get("mailbox_id", UUID.class))).all().collectList()
				.flatMap(senders -> senders.size() == 1 ? Mono.just(senders.getFirst()) : Mono.error(new CampaignValidationException(
						"A successfully tested enabled SMTP account and exactly one matching tested IMAP mailbox are required")));
	}

	public Mono<Context> lockContext(UUID id) {
		return lockContext(id, true);
	}

	/** Caller transaction already holds the campaign tier; this method acquires only lower tiers. */
	public Mono<Context> lockContextAfterCampaignLock(UUID id) {
		return lockContext(id, false);
	}

	private Mono<Context> lockContext(UUID id, boolean lockCampaign) {
		return db.sql("""
				SELECT c.id, c.status, c.generation_status, c.generation_provider, c.generation_model,
				  c.recipient_policy, c.paper_contact_ids, c.paper_contact_recipient_count,
				  c.from_name, c.reply_to, v.subject_template, v.html_content, v.text_content,
				  jsonb_build_array(c.id,c.segment_id,segment.name,c.template_version_id,c.smtp_account_id,
				    c.mailbox_account_id,c.name,c.purpose,c.from_email,c.from_name,c.reply_to,
				    c.tracking_opens_enabled,c.tracking_clicks_enabled,c.unsubscribe_enabled,
				    c.paper_contact_ids,c.paper_contact_recipient_count,c.generation_job_id,
				    smtp.host,smtp.port,smtp.tls_mode,smtp.username,smtp.from_email,smtp.default_from_name,smtp.reply_to,
				    mailbox.protocol,mailbox.host,mailbox.port,mailbox.tls_mode,mailbox.username,mailbox.folder_name)::text AS provenance,
				  smtp.enabled AND smtp.last_test_status='SUCCEEDED'
				    AND smtp.last_tested_at IS NOT NULL AND smtp.last_tested_at >= smtp.updated_at
				    AND smtp.from_email=c.from_email AND smtp.reply_to=c.reply_to
				    AND mailbox.enabled AND mailbox.protocol='IMAP' AND mailbox.last_test_status='SUCCEEDED'
				    AND mailbox.last_tested_at IS NOT NULL AND mailbox.last_tested_at >= mailbox.updated_at
				    AND lower(trim(mailbox.username))=lower(trim(c.reply_to)) AS accounts_ready
				FROM campaigns c JOIN email_template_versions v ON v.id=c.template_version_id
				JOIN segments segment ON segment.id=c.segment_id
				JOIN smtp_accounts smtp ON smtp.id=c.smtp_account_id
				JOIN mailbox_accounts mailbox ON mailbox.id=c.mailbox_account_id
				WHERE c.id=:id
				""" + (lockCampaign ? " FOR UPDATE OF c" : "") + " FOR SHARE OF v, smtp, mailbox").bind("id", id).map((r,m) -> new Context(r.get("id", UUID.class), r.get("status", String.class),
					r.get("generation_status", String.class), r.get("generation_provider", String.class), r.get("generation_model", String.class),
					r.get("recipient_policy", String.class), papers(r.get("paper_contact_ids", UUID[].class)), r.get("paper_contact_recipient_count", Integer.class),
					Boolean.TRUE.equals(r.get("accounts_ready", Boolean.class)),
					new OutreachTemplateContentPolicy.TemplateSnapshot(r.get("provenance", String.class), r.get("subject_template", String.class),
						r.get("html_content", String.class), r.get("text_content", String.class), r.get("from_name", String.class), r.get("reply_to", String.class)))).one();
	}

	public Mono<Void> saveReview(UUID campaignId, UUID recipientId, byte[] sourceHash, byte[] contentHash) {
		return db.sql("""
				INSERT INTO outreach_content_reviews(campaign_id,recipient_id,policy_version,source_hash,content_hash,reviewed_at)
				VALUES (:campaign,:recipient,:version,:source,:content,now())
				""").bind("campaign", campaignId).bind("recipient", recipientId).bind("version", OutreachTemplateContentPolicy.VERSION)
				.bind("source", sourceHash).bind("content", contentHash).fetch().rowsUpdated().then();
	}

	public Mono<Review> review(UUID campaignId, UUID recipientId) {
		return db.sql("SELECT policy_version,source_hash,content_hash FROM outreach_content_reviews WHERE campaign_id=:campaign AND recipient_id=:recipient")
				.bind("campaign", campaignId).bind("recipient", recipientId).map((r,m) -> new Review(r.get("policy_version", String.class),
						r.get("source_hash", byte[].class), r.get("content_hash", byte[].class))).one();
	}

	public Mono<Long> reviewCount(UUID campaignId) {
		return db.sql("SELECT count(*) AS total FROM outreach_content_reviews WHERE campaign_id=:campaign")
				.bind("campaign", campaignId).map((r,m) -> r.get("total", Long.class)).one();
	}

	private List<UUID> papers(UUID[] ids) { return ids == null ? null : List.of(ids); }

	public record Sender(String fromName, String fromEmail, String replyTo, UUID mailboxId) { }
	public record Context(UUID campaignId, String status, String generationStatus, String provider, String model,
			String recipientPolicy, List<UUID> paperIds, Integer frozenCount, boolean accountsReady,
			OutreachTemplateContentPolicy.TemplateSnapshot template) { }
	public record Review(String policyVersion, byte[] sourceHash, byte[] contentHash) { }
}
