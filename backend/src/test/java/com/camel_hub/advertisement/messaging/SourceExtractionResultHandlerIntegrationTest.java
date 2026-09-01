package com.camel_hub.advertisement.messaging;

import com.camel_hub.advertisement.arxiv.extraction.SourceExtractionResultRepository;
import com.camel_hub.advertisement.arxiv.paper.PaperQueryRepository;
import com.camel_hub.advertisement.arxiv.paper.PaperQueryService;
import com.camel_hub.advertisement.arxiv.paper.PaperRepository;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyRepository;
import com.camel_hub.advertisement.contact.ContactRepository;
import com.camel_hub.advertisement.contact.ContactService;
import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceExtractionResultHandlerIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_source_result_test").withUsername("camel").withPassword("test-only");
	private static final UUID ACTOR = UUID.fromString("8cd94cdf-79dd-4d50-a1fb-244e73f9a802");
	private static final UUID PAPER = UUID.fromString("615e263f-0041-48f6-9331-7a3e909344c6");
	private static final UUID AUTHOR = UUID.fromString("9727ea06-7c20-4c44-af99-9481f21d5df2");
	private DatabaseClient databaseClient;
	private ArxivResultHandler handler;
	private ContactCrypto crypto;
	private UUID jobId;

	@BeforeEach
	void setUp() {
		if (!POSTGRES.isRunning()) {
			POSTGRES.start();
			Flyway.configure().dataSource(
					POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration").load().migrate();
		}
		var connectionFactory = ConnectionFactories.get(r2dbcUrl());
		databaseClient = DatabaseClient.create(connectionFactory);
		clear();
		seed();
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		crypto = crypto();
		handler = new ArxivResultHandler(
				new ArxivResultRepository(databaseClient), new PaperRepository(databaseClient, mapper),
				new TaxonomyRepository(databaseClient, mapper),
				new SourceExtractionResultRepository(databaseClient, crypto, mapper), mapper,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
	}

	@Test
	void encryptsAndPersistsExtractionResultAtomicallyAndDeduplicatesReplay() {
		UUID messageId = UUID.randomUUID();
		String message = resultMessage(messageId, "[email redacted]");

		var first = handler.handle(message).block();
		var replay = handler.handle(message).block();

		assertThat(first.duplicate()).isFalse();
		assertThat(replay.duplicate()).isTrue();
		assertThat(count("extraction_runs")).isEqualTo(1);
		assertThat(count("contacts")).isEqualTo(1);
		assertThat(count("paper_author_contacts")).isEqualTo(1);
		assertThat(count("extraction_evidence")).isEqualTo(1);
		assertThat(text("SELECT source_status FROM papers WHERE id = '" + PAPER + "'"))
				.isEqualTo("PARSED");
		assertThat(text("SELECT status FROM job_items WHERE job_id = '" + jobId + "'"))
				.isEqualTo("SUCCEEDED");
		assertThat(text("SELECT confidence FROM paper_author_contacts LIMIT 1"))
				.isEqualTo("HIGH");
		assertThat(count("paper_authors")).isEqualTo(1);
		assertThat(text("SELECT raw_name FROM paper_authors WHERE paper_id = '" + PAPER + "'"))
				.isEqualTo("Alice Metadata");
		assertThat(text("SELECT masked_context FROM extraction_evidence LIMIT 1"))
				.isEqualTo("Corresponding author: [email redacted]")
				.doesNotContain("alice@university.edu", "@");
		assertThat(text("SELECT details::text FROM job_events WHERE event_type = 'ARXIV_EXTRACTION_RESULT'"))
				.doesNotContain("alice", "university.edu");
		assertThat(databaseClient.sql("""
				SELECT cleanup_confirmed AND cleanup_confirmed_at IS NOT NULL AS cleaned
				FROM extraction_runs LIMIT 1
				""").map((row, metadata) -> row.get("cleaned", Boolean.class)).one().block()).isTrue();

		byte[] ciphertext = bytes("email_ciphertext");
		byte[] nonce = bytes("email_nonce");
		byte[] displayNonce = bytes("display_nonce");
		assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain("alice@university.edu");
		assertThat(nonce).isNotEqualTo(displayNonce);
		assertThat(crypto.decrypt(new ContactCrypto.EncryptedValue(ciphertext, nonce)))
				.isEqualTo("alice@university.edu");

		var contacts = new ContactRepository(databaseClient);
		var rows = contacts.list(new ContactService.ContactFilter(
				"university.edu", "HIGH", "UNVERIFIED", true, PAPER), 0, 20)
				.collectList().block();
		assertThat(rows).hasSize(1);
		var contact = rows.getFirst();
		assertThat(contact.authorName()).isEqualTo("Alice Metadata");
		assertThat(contacts.find(contact.id()).block().paperId()).isEqualTo(PAPER);
		assertThat(contacts.evidence(contact.mappingId()).collectList().block())
				.singleElement().satisfies(item -> assertThat(item.maskedContext())
						.isEqualTo("Corresponding author: [email redacted]"));
		assertThat(contacts.updateVerification(
				contact.id(), contact.mappingId(), 0, "CONFIRMED", ACTOR).block()).isTrue();
		assertThat(contacts.updateVerification(
				contact.id(), contact.mappingId(), 0, "REJECTED", ACTOR).block()).isFalse();

		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		var papers = new PaperQueryService(new PaperQueryRepository(databaseClient, mapper));
		var paper = papers.get(PAPER).block();
		assertThat(paper.authors()).singleElement()
				.satisfies(author -> assertThat(author.corresponding()).isTrue());
		assertThat(paper.extractionRuns()).singleElement().satisfies(run -> {
			assertThat(run.status()).isEqualTo("SUCCEEDED");
			assertThat(run.contactsFound()).isEqualTo(1);
			assertThat(run.cleanupConfirmed()).isTrue();
		});
	}

	@Test
	void defersSourceCompletionUntilTheLateItemResultArrives() {
		String premature = completionMessage(UUID.randomUUID());

		handler.handle(premature).block();

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("RUNNING");
		assertThat(text("SELECT current_stage FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("AWAITING_ITEM_RESULTS");
		assertThat(number("SELECT processed_count FROM jobs WHERE id = '" + jobId + "'"))
				.isZero();
		assertThat(number("SELECT success_count FROM jobs WHERE id = '" + jobId + "'"))
				.isZero();
		assertThat(flag("SELECT ended_at IS NULL FROM jobs WHERE id = '" + jobId + "'"))
				.isTrue();
		assertThat(text("SELECT status FROM job_items WHERE job_id = '" + jobId + "'"))
				.isEqualTo("RUNNING");
		assertThat(number("SELECT count(*) FROM job_events WHERE job_id = '" + jobId
				+ "' AND event_type = 'ARXIV_JOB_COMPLETION_DEFERRED'"))
				.isEqualTo(1);
		assertThat(number("SELECT count(*) FROM job_events WHERE job_id = '" + jobId
				+ "' AND event_type = 'ARXIV_JOB_FAILED'"))
				.isZero();

		handler.handle(resultMessage(UUID.randomUUID(), "[email redacted]")).block();

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("SUCCEEDED");
		assertThat(text("SELECT current_stage FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("COMPLETED");
		assertThat(number("SELECT processed_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(1);
		assertThat(number("SELECT success_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(1);
		assertThat(flag("SELECT ended_at IS NOT NULL FROM jobs WHERE id = '" + jobId + "'"))
				.isTrue();
		assertThat(text("SELECT status FROM job_items WHERE job_id = '" + jobId + "'"))
				.isEqualTo("SUCCEEDED");
		assertThat(count("contacts")).isEqualTo(1);
		assertThat(number("SELECT count(*) FROM job_events WHERE job_id = '" + jobId
				+ "' AND event_type = 'ARXIV_JOB_COMPLETED'"))
				.isEqualTo(1);
	}

	@Test
	void deferredSourceJobIgnoresInflatedProgressAndRefreshesExactLateItemTotals() {
		UUID secondItem = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO job_items (job_id, external_key, status)
				VALUES (:jobId, :externalKey, 'RUNNING')
				""").bind("jobId", jobId).bind("externalKey", secondItem.toString())
				.fetch().rowsUpdated().block();
		databaseClient.sql("UPDATE jobs SET total_count = 2 WHERE id = :jobId")
				.bind("jobId", jobId).fetch().rowsUpdated().block();
		handler.handle(completionMessage(UUID.randomUUID())).block();

		handler.handle(progressMessage(UUID.randomUUID())).block();

		assertThat(text("SELECT current_stage FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("AWAITING_ITEM_RESULTS");
		assertThat(number("SELECT processed_count FROM jobs WHERE id = '" + jobId + "'"))
				.isZero();
		assertThat(number("SELECT success_count FROM jobs WHERE id = '" + jobId + "'"))
				.isZero();
		assertThat(number("SELECT total_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(2);

		String inflatedItem = resultMessage(UUID.randomUUID(), "[email redacted]")
				.replace("\"processedCount\":1,", "\"processedCount\":2,")
				.replace("\"successCount\":1,", "\"successCount\":2,")
				.replace("\"totalCount\":1,", "\"totalCount\":2,");
		handler.handle(inflatedItem).block();

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("RUNNING");
		assertThat(text("SELECT current_stage FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("AWAITING_ITEM_RESULTS");
		assertThat(number("SELECT processed_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(1);
		assertThat(number("SELECT success_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(1);
		assertThat(number("SELECT total_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(2);
		assertThat(number("SELECT progress_percent::bigint FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(50);
		assertThat(number("SELECT count(*) FROM job_events WHERE job_id = '" + jobId
				+ "' AND event_type = 'ARXIV_JOB_COMPLETED'"))
				.isZero();
	}

	@Test
	void staleDeferredCompletionFailsOnlyAfterTheReconciliationGracePeriod() {
		handler.handle(completionMessage(UUID.randomUUID())).block();
		Instant now = Instant.parse("2026-09-02T08:00:00Z");
		var repository = new ArxivResultRepository(databaseClient);
		var reconciliation = new SourceCompletionReconciliationJob(
				repository, new ArxivMessagingProperties(Duration.ofMinutes(15)),
				Clock.fixed(now, ZoneOffset.UTC));

		databaseClient.sql("UPDATE jobs SET last_message_at = :lastMessage WHERE id = :jobId")
				.bind("lastMessage", now.minus(Duration.ofMinutes(14))).bind("jobId", jobId)
				.fetch().rowsUpdated().block();
		assertThat(reconciliation.reconcileNow().block()).isZero();
		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("RUNNING");

		databaseClient.sql("UPDATE jobs SET last_message_at = :lastMessage WHERE id = :jobId")
				.bind("lastMessage", now.minus(Duration.ofMinutes(16))).bind("jobId", jobId)
				.fetch().rowsUpdated().block();
		var competingReconciliation = new SourceCompletionReconciliationJob(
				new ArxivResultRepository(databaseClient),
				new ArxivMessagingProperties(Duration.ofMinutes(15)),
				Clock.fixed(now, ZoneOffset.UTC));
		var outcomes = Mono.zip(
				reconciliation.reconcileNow(), competingReconciliation.reconcileNow()).block();
		assertThat(outcomes.getT1() + outcomes.getT2()).isEqualTo(1);

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("FAILED");
		assertThat(text("SELECT current_stage FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("FAILED");
		assertThat(number("SELECT processed_count FROM jobs WHERE id = '" + jobId + "'"))
				.isZero();
		assertThat(number("SELECT total_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(1);
		assertThat(flag("SELECT ended_at IS NOT NULL FROM jobs WHERE id = '" + jobId + "'"))
				.isTrue();
		assertThat(text("SELECT status FROM job_items WHERE job_id = '" + jobId + "'"))
				.isEqualTo("RUNNING");
		assertThat(text("SELECT details->>'errorCode' FROM job_events WHERE job_id = '"
				+ jobId + "' AND event_type = 'ARXIV_JOB_FAILED'"))
				.isEqualTo("SOURCE_RESULTS_INCOMPLETE");
		assertThat(number("SELECT count(*) FROM job_events WHERE job_id = '" + jobId
				+ "' AND event_type = 'ARXIV_JOB_FAILED'"))
				.isEqualTo(1);
		assertThat(reconciliation.reconcileNow().block()).isZero();
	}

	@Test
	void staleDeferredCompletionWithNoItemsFailsAfterTheReconciliationGracePeriod() {
		databaseClient.sql("DELETE FROM job_items WHERE job_id = :jobId")
				.bind("jobId", jobId).fetch().rowsUpdated().block();
		handler.handle(completionMessage(UUID.randomUUID())).block();
		Instant now = Instant.parse("2026-09-02T08:00:00Z");
		databaseClient.sql("UPDATE jobs SET last_message_at = :lastMessage WHERE id = :jobId")
				.bind("lastMessage", now.minus(Duration.ofMinutes(16))).bind("jobId", jobId)
				.fetch().rowsUpdated().block();
		var reconciliation = new SourceCompletionReconciliationJob(
				new ArxivResultRepository(databaseClient),
				new ArxivMessagingProperties(Duration.ofMinutes(15)),
				Clock.fixed(now, ZoneOffset.UTC));

		assertThat(reconciliation.reconcileNow().block()).isEqualTo(1);
		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("FAILED");
		assertThat(number("SELECT total_count FROM jobs WHERE id = '" + jobId + "'"))
				.isZero();
		assertThat(text("SELECT details->>'errorCode' FROM job_events WHERE job_id = '"
				+ jobId + "' AND event_type = 'ARXIV_JOB_FAILED'"))
				.isEqualTo("SOURCE_RESULTS_INCOMPLETE");
	}

	@Test
	void canceledDeferredJobRejectsBothWatchdogAndLateItemMutations() {
		handler.handle(completionMessage(UUID.randomUUID())).block();
		Instant now = Instant.parse("2026-09-02T08:00:00Z");
		databaseClient.sql("""
				UPDATE jobs SET status = 'CANCELED', current_stage = 'CANCELED_BY_USER',
				  cancel_requested = true, ended_at = :endedAt, last_message_at = :lastMessage
				WHERE id = :jobId
				""").bind("endedAt", now.minusSeconds(60))
				.bind("lastMessage", now.minus(Duration.ofMinutes(16))).bind("jobId", jobId)
				.fetch().rowsUpdated().block();
		var reconciliation = new SourceCompletionReconciliationJob(
				new ArxivResultRepository(databaseClient),
				new ArxivMessagingProperties(Duration.ofMinutes(15)),
				Clock.fixed(now, ZoneOffset.UTC));

		assertThat(reconciliation.reconcileNow().block()).isZero();
		handler.handle(resultMessage(UUID.randomUUID(), "[email redacted]")).block();

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("CANCELED");
		assertThat(text("SELECT current_stage FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("CANCELED_BY_USER");
		assertThat(count("contacts")).isZero();
		assertThat(text("SELECT status FROM job_items WHERE job_id = '" + jobId + "'"))
				.isEqualTo("RUNNING");
		assertThat(number("SELECT count(*) FROM job_events WHERE job_id = '" + jobId
				+ "' AND event_type IN ('ARXIV_JOB_FAILED','ARXIV_JOB_COMPLETED')"))
				.isZero();
	}

	@Test
	void sourceCompletionUsesPersistedItemTotalsInsteadOfInflatedProgress() {
		handler.handle(resultMessage(UUID.randomUUID(), "[email redacted]")).block();
		databaseClient.sql("""
				UPDATE jobs SET processed_count = 52, success_count = 52, total_count = 100,
				  progress_percent = 52 WHERE id = :jobId
				""").bind("jobId", jobId).fetch().rowsUpdated().block();

		handler.handle(completionMessage(UUID.randomUUID())).block();

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("SUCCEEDED");
		assertThat(number("SELECT processed_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(1);
		assertThat(number("SELECT success_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(1);
		assertThat(number("SELECT total_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(1);
		assertThat(number("SELECT progress_percent::bigint FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(100);
	}

	@Test
	void terminalFailureClosesAPartiallyProcessedSourceJobAndDeduplicatesReplay() {
		seedPartiallyProcessedItems();
		UUID messageId = UUID.randomUUID();
		String failure = failureMessage(messageId);

		var first = handler.handle(failure).block();
		var replay = handler.handle(failure).block();

		assertThat(first.duplicate()).isFalse();
		assertThat(replay.duplicate()).isTrue();
		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("FAILED");
		assertThat(text("SELECT current_stage FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("FAILED");
		assertThat(text("SELECT error_summary FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("Worker stopped before the source batch completed");
		assertThat(flag("SELECT ended_at IS NOT NULL FROM jobs WHERE id = '" + jobId + "'"))
				.isTrue();
		assertThat(number("SELECT processed_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(51);
		assertThat(number("SELECT success_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(51);
		assertThat(number("SELECT progress_percent::bigint FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(51);
		assertThat(number("SELECT count(*) FROM job_items WHERE job_id = '" + jobId
				+ "' AND status = 'PENDING'"))
				.isEqualTo(49);
		assertThat(number("SELECT count(*) FROM job_events WHERE job_id = '" + jobId
				+ "' AND event_type = 'ARXIV_JOB_FAILED'"))
				.isEqualTo(1);
		assertThat(text("SELECT details->>'errorCode' FROM job_events WHERE job_id = '"
				+ jobId + "' AND event_type = 'ARXIV_JOB_FAILED'"))
				.isEqualTo("WORKER_RETRY_EXHAUSTED");
	}

	@Test
	void lateCanceledCompletionCannotRewriteFailedSourceItems() {
		seedPartiallyProcessedItems();
		handler.handle(failureMessage(UUID.randomUUID())).block();

		handler.handle(canceledCompletionMessage(UUID.randomUUID())).block();

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("FAILED");
		assertThat(number("SELECT count(*) FROM job_items WHERE job_id = '" + jobId
				+ "' AND status = 'PENDING'"))
				.isEqualTo(49);
		assertThat(number("SELECT count(*) FROM job_items WHERE job_id = '" + jobId
				+ "' AND status = 'CANCELED'"))
				.isZero();
	}

	@Test
	void rejectsAnUnsafeFailureCodeBeforePersistingItInEventDetails() {
		String invalid = failureMessage(UUID.randomUUID())
				.replace("WORKER_RETRY_EXHAUSTED", "unsafe failure code");

		assertThatThrownBy(() -> handler.handle(invalid).block())
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("RUNNING");
		assertThat(count("processed_messages")).isZero();
		assertThat(number("SELECT count(*) FROM job_events WHERE job_id = '" + jobId
				+ "' AND event_type = 'ARXIV_JOB_FAILED'"))
				.isZero();
	}

	@Test
	void lateFailureCannotOverwriteACanceledJob() {
		databaseClient.sql("""
				UPDATE jobs SET status = 'CANCELED', current_stage = 'CANCELED_BY_USER',
				  error_summary = 'Canceled by the owner', ended_at = now(),
				  processed_count = 7, success_count = 7, total_count = 100,
				  progress_percent = 7
				WHERE id = :jobId
				""").bind("jobId", jobId).fetch().rowsUpdated().block();

		handler.handle(failureMessage(UUID.randomUUID())).block();

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("CANCELED");
		assertThat(text("SELECT current_stage FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("CANCELED_BY_USER");
		assertThat(text("SELECT error_summary FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("Canceled by the owner");
		assertThat(number("SELECT processed_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(7);
		assertThat(number("SELECT count(*) FROM job_events WHERE job_id = '" + jobId
				+ "' AND event_type = 'ARXIV_JOB_FAILED'"))
				.isZero();
	}

	@Test
	void canceledSourceCompletionDoesNotRequireSuccessfulItemResults() {
		handler.handle(canceledCompletionMessage(UUID.randomUUID())).block();

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("CANCELED");
		assertThat(text("SELECT current_stage FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("CANCELED");
		assertThat(flag("SELECT ended_at IS NOT NULL FROM jobs WHERE id = '" + jobId + "'"))
				.isTrue();
		assertThat(text("SELECT status FROM job_items WHERE job_id = '" + jobId + "'"))
				.isEqualTo("CANCELED");
	}

	@Test
	void canceledSourceCompletionUsesExactPersistedItemTotals() {
		seedPartiallyProcessedItems();

		handler.handle(canceledCompletionMessage(UUID.randomUUID())).block();

		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("CANCELED");
		assertThat(number("SELECT processed_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(51);
		assertThat(number("SELECT success_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(51);
		assertThat(number("SELECT total_count FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(100);
		assertThat(number("SELECT progress_percent::bigint FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo(51);
		assertThat(number("SELECT count(*) FROM job_items WHERE job_id = '" + jobId
				+ "' AND status = 'CANCELED'"))
				.isEqualTo(49);
	}

	@Test
	void reconcilesSourceAuthorByNameBeforeFallingBackToMetadataOrder() {
		String sourceAuthors = """
				"authors":[
				 {"order":1,"name":"Different Source Person","affiliations":[],
				  "corresponding":false},
				 {"order":2,"name":"Alice Metadata","affiliations":["Example Lab"],
				  "corresponding":true}],
				"contacts":""";
		String message = resultMessage(UUID.randomUUID(), "[email redacted]")
				.replaceFirst("(?s)\"authors\":\\[.*?],\\s*\"contacts\":", sourceAuthors)
				.replace("\"authorOrder\":1", "\"authorOrder\":2");

		handler.handle(message).block();

		assertThat(count("paper_authors")).isEqualTo(1);
		assertThat(text("""
				SELECT pa.author_id::text FROM paper_author_contacts pac
				JOIN paper_authors pa ON pa.id = pac.paper_author_id
				LIMIT 1
				""")).isEqualTo(AUTHOR.toString());
		assertThat(text("SELECT status FROM job_items WHERE job_id = '" + jobId + "'"))
				.isEqualTo("SUCCEEDED");
	}

	@Test
	void scopesLatestContactMappingToTheFilteredPaper() {
		handler.handle(resultMessage(UUID.randomUUID(), "[email redacted]")).block();
		UUID otherPaper = UUID.randomUUID();
		UUID otherAuthor = UUID.randomUUID();
		UUID otherPaperAuthor = UUID.randomUUID();
		UUID otherRun = UUID.randomUUID();

		databaseClient.sql("""
				INSERT INTO papers (
				  id, arxiv_id, title, abstract_text, primary_category_id,
				  submitted_at, updated_at, pdf_url)
				SELECT :paper, '2608.00002', 'Later Source Paper', 'Abstract', id,
				       now(), now(), 'https://arxiv.org/pdf/2608.00002'
				FROM arxiv_categories WHERE category_id = 'cs.AI'
				""").bind("paper", otherPaper).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO authors (id, normalized_name, display_name)
				VALUES (:author, 'later author', 'Later Author')
				""").bind("author", otherAuthor).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO paper_authors (
				  id, paper_id, author_id, author_order, raw_name, affiliation_data)
				VALUES (:paperAuthor, :paper, :author, 1, 'Later Author', '[]'::jsonb)
				""").bind("paperAuthor", otherPaperAuthor).bind("paper", otherPaper)
				.bind("author", otherAuthor).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO extraction_runs (
				  id, paper_id, parser_version, status, files_inspected, contacts_found,
				  started_at, completed_at, cleanup_confirmed, cleanup_confirmed_at)
				VALUES (:run, :paper, '0.1.0', 'SUCCEEDED', 1, 1,
				        now() + interval '1 minute', now() + interval '1 minute', true,
				        now() + interval '1 minute')
				""").bind("run", otherRun).bind("paper", otherPaper)
				.fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO paper_author_contacts (
				  paper_author_id, paper_id, contact_id, extraction_run_id, confidence,
				  corresponding_author, created_at)
				SELECT :paperAuthor, :paper, id, :run, 'MEDIUM', false, now() + interval '1 minute'
				FROM contacts LIMIT 1
				""").bind("paperAuthor", otherPaperAuthor).bind("paper", otherPaper)
				.bind("run", otherRun).fetch().rowsUpdated().block();

		var contacts = new ContactRepository(databaseClient);
		var originalRows = contacts.list(new ContactService.ContactFilter(
				null, null, null, null, PAPER), 0, 20).collectList().block();
		var laterRows = contacts.list(new ContactService.ContactFilter(
				null, null, null, null, otherPaper), 0, 20).collectList().block();

		assertThat(originalRows).singleElement()
				.satisfies(row -> assertThat(row.paperId()).isEqualTo(PAPER));
		assertThat(laterRows).singleElement()
				.satisfies(row -> assertThat(row.paperId()).isEqualTo(otherPaper));
		assertThat(contacts.count(new ContactService.ContactFilter(
				null, null, null, null, PAPER)).block()).isEqualTo(1);
	}

	@Test
	void rejectsUnmaskedEvidenceWithoutLeavingProcessedMarkerOrPartialRows() {
		String unsafe = resultMessage(UUID.randomUUID(), "alice@university.edu");

		assertThatThrownBy(() -> handler.handle(unsafe).block())
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(count("processed_messages")).isZero();
		assertThat(count("extraction_runs")).isZero();
		assertThat(text("SELECT source_status FROM papers WHERE id = '" + PAPER + "'"))
				.isEqualTo("UNKNOWN");
	}

	@Test
	void rejectsContactWithoutAPublicDnsDomainWithoutPartialRows() {
		String unsafe = resultMessage(UUID.randomUUID(), "[email redacted]")
				.replace("university.edu", "localhost");

		assertThatThrownBy(() -> handler.handle(unsafe).block())
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(count("processed_messages")).isZero();
		assertThat(count("extraction_runs")).isZero();
		assertThat(count("contacts")).isZero();
		assertThat(text("SELECT source_status FROM papers WHERE id = '" + PAPER + "'"))
				.isEqualTo("UNKNOWN");
	}

	@Test
	void acceptsMaximumLengthDnsDomain() {
		String domain = "a".repeat(63) + "." + "b".repeat(63) + "."
				+ "c".repeat(63) + "." + "d".repeat(61);
		assertThat(domain).hasSize(253);
		String safe = resultMessage(UUID.randomUUID(), "[email redacted]")
				.replace("alice@university.edu", "alice@" + domain)
				.replace("\"domain\":\"university.edu\"", "\"domain\":\"" + domain + "\"");

		handler.handle(safe).block();

		assertThat(count("processed_messages")).isEqualTo(1);
		assertThat(count("extraction_runs")).isEqualTo(1);
		assertThat(count("contacts")).isEqualTo(1);
		assertThat(count("paper_author_contacts")).isEqualTo(1);
		assertThat(count("extraction_evidence")).isEqualTo(1);
	}

	@Test
	void rejectsDomainLongerThanDnsMaximumWithoutPartialRows() {
		String domain = "a".repeat(63) + "." + "b".repeat(63) + "."
				+ "c".repeat(63) + "." + "d".repeat(62);
		assertThat(domain).hasSize(254);
		String unsafe = resultMessage(UUID.randomUUID(), "[email redacted]")
				.replace("alice@university.edu", "alice@" + domain)
				.replace("\"domain\":\"university.edu\"", "\"domain\":\"" + domain + "\"");

		assertThatThrownBy(() -> handler.handle(unsafe).block())
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(count("processed_messages")).isZero();
		assertThat(count("extraction_runs")).isZero();
		assertThat(count("contacts")).isZero();
		assertThat(count("paper_author_contacts")).isZero();
		assertThat(count("extraction_evidence")).isZero();
		assertThat(text("SELECT source_status FROM papers WHERE id = '" + PAPER + "'"))
				.isEqualTo("UNKNOWN");
	}

	@ParameterizedTest
	@ValueSource(strings = {"alice@2.1.7", "alice@example.c"})
	void rejectsContactWithoutAlphabeticMultiCharacterTopLevelDomainWithoutPartialRows(
			String normalizedEmail) {
		String domain = normalizedEmail.substring(normalizedEmail.indexOf('@') + 1);
		String unsafe = resultMessage(UUID.randomUUID(), "[email redacted]")
				.replace("alice@university.edu", normalizedEmail)
				.replace("\"domain\":\"university.edu\"", "\"domain\":\"" + domain + "\"");

		assertThatThrownBy(() -> handler.handle(unsafe).block())
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(count("processed_messages")).isZero();
		assertThat(count("extraction_runs")).isZero();
		assertThat(count("contacts")).isZero();
		assertThat(count("paper_author_contacts")).isZero();
		assertThat(count("extraction_evidence")).isZero();
		assertThat(text("SELECT source_status FROM papers WHERE id = '" + PAPER + "'"))
				.isEqualTo("UNKNOWN");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"alice@example.xn--a",
			"alice@example.xn--abc",
			"alice@example.xn--0"
	})
	void rejectsInvalidPunycodeLabelsWithoutPartialRows(String normalizedEmail) {
		String domain = normalizedEmail.substring(normalizedEmail.indexOf('@') + 1);
		String unsafe = resultMessage(UUID.randomUUID(), "[email redacted]")
				.replace("alice@university.edu", normalizedEmail)
				.replace("\"domain\":\"university.edu\"", "\"domain\":\"" + domain + "\"");

		assertThatThrownBy(() -> handler.handle(unsafe).block())
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(count("processed_messages")).isZero();
		assertThat(count("extraction_runs")).isZero();
		assertThat(count("contacts")).isZero();
		assertThat(count("paper_author_contacts")).isZero();
		assertThat(count("extraction_evidence")).isZero();
		assertThat(text("SELECT source_status FROM papers WHERE id = '" + PAPER + "'"))
				.isEqualTo("UNKNOWN");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"alice@lab2.university.edu",
			"alice@example.xn--p1ai",
			"alice@example.xn--80ak6aa92e"
	})
	void acceptsNumericSubdomainsAndPunycodeLabels(String normalizedEmail) {
		String domain = normalizedEmail.substring(normalizedEmail.indexOf('@') + 1);
		String safe = resultMessage(UUID.randomUUID(), "[email redacted]")
				.replace("alice@university.edu", normalizedEmail)
				.replace("\"domain\":\"university.edu\"", "\"domain\":\"" + domain + "\"");

		handler.handle(safe).block();

		assertThat(count("processed_messages")).isEqualTo(1);
		assertThat(count("extraction_runs")).isEqualTo(1);
		assertThat(count("contacts")).isEqualTo(1);
		assertThat(count("paper_author_contacts")).isEqualTo(1);
		assertThat(count("extraction_evidence")).isEqualTo(1);
	}

	@Test
	void rejectsContactWithAnIllegalDotLocalPartWithoutPartialRows() {
		String unsafe = resultMessage(UUID.randomUUID(), "[email redacted]")
				.replace("alice@university.edu", "alice..x@university.edu");

		assertThatThrownBy(() -> handler.handle(unsafe).block())
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(count("processed_messages")).isZero();
		assertThat(count("extraction_runs")).isZero();
		assertThat(count("contacts")).isZero();
	}

	@Test
	void rejectsEmailLikeTextOutsideEncryptedContactFields() {
		String safe = resultMessage(UUID.randomUUID(), "[email redacted]");
		var unsafeMessages = java.util.List.of(
				safe.replace("Alice Example", "alice＠example.edu"),
				safe.replace("Example Lab", "Example Lab; 用户@example.org"),
				safe.replace("paper/main.tex", "paper/bob@example.org.tex"),
				resultMessage(UUID.randomUUID(), "Contact josé@example.org"),
				safe.replace("TAR_GZIP", "alice@example.edu"),
				safe.replace("article", "用户@example.org"));

		for (String unsafe : unsafeMessages) {
			assertThatThrownBy(() -> handler.handle(unsafe).block())
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(count("processed_messages")).isZero();
		assertThat(count("extraction_runs")).isZero();
		assertThat(count("contacts")).isZero();
		assertThat(count("extraction_evidence")).isZero();
	}

	@Test
	void rejectsEmailLikeJobErrorSummaryWithoutAProcessedMarker() {
		String unsafe = failureMessage(UUID.randomUUID()).replace(
				"Worker\\u0001stopped before the source batch completed",
				"Contact alice@example.edu");

		assertThatThrownBy(() -> handler.handle(unsafe).block())
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(count("processed_messages")).isZero();
		assertThat(text("SELECT status FROM jobs WHERE id = '" + jobId + "'"))
				.isEqualTo("RUNNING");
	}

	private String resultMessage(UUID messageId, String context) {
		return """
				{"version":1,"messageId":"%s","type":"ARXIV_EXTRACTION_RESULT","jobId":"%s",
				 "idempotencyKey":"source-result:%s","traceId":"0123456789abcdef",
				 "occurredAt":"2026-08-06T01:00:00Z","payload":{
				 "status":"RUNNING","stage":"PERSISTING_EXTRACTION","processedCount":1,
				 "successCount":1,"skippedCount":0,"failedCount":0,"totalCount":1,
				 "progressPercent":100,"checkpoint":{},"papers":[],"extractions":[{
				 "paperId":"%s","arxivId":"2608.00001","parserVersion":"0.1.0",
				 "status":"SUCCEEDED","cleanupConfirmed":true,"sourceFormat":"TAR_GZIP",
				 "archiveSizeBytes":1200,"extractedSizeBytes":4000,"filesInspected":2,
				 "durationMs":85,"documentClass":"article",
				 "authors":[{"order":1,"name":"Alice Example","affiliations":["Example Lab"],
				             "corresponding":true}],
				 "contacts":[{"normalizedEmail":"alice@university.edu",
				 "displayEmail":"alice@university.edu","domain":"university.edu",
				 "syntaxValid":true,"exampleAddress":false,"authorOrder":1,
				 "confidence":"HIGH","corresponding":true,"evidence":[{
				 "sourceRelativePath":"paper/main.tex","ruleName":"DIRECT_AUTHOR_EMAIL",
				 "lineNumber":4,"logicalLocation":"AUTHOR_FRONT_MATTER",
				 "maskedContext":"Corresponding author: %s"}]}]}]}}
				""".formatted(messageId, jobId, messageId, PAPER, context);
	}

	private String completionMessage(UUID messageId) {
		return """
				{"version":1,"messageId":"%s","type":"ARXIV_JOB_COMPLETED","jobId":"%s",
				 "idempotencyKey":"source-complete:%s","traceId":"0123456789abcdef",
				 "occurredAt":"2026-08-06T01:00:01Z","payload":{
				 "status":"SUCCEEDED","stage":"COMPLETED","processedCount":1,
				 "successCount":1,"skippedCount":0,"failedCount":0,"totalCount":1,
				 "progressPercent":100,"checkpoint":{},"papers":[],"extractions":[]}}
				""".formatted(messageId, jobId, messageId);
	}

	private String canceledCompletionMessage(UUID messageId) {
		return """
				{"version":1,"messageId":"%s","type":"ARXIV_JOB_COMPLETED","jobId":"%s",
				 "idempotencyKey":"source-canceled:%s","traceId":"0123456789abcdef",
				 "occurredAt":"2026-08-06T01:00:01Z","payload":{
				 "status":"CANCELED","stage":"CANCELED","processedCount":0,
				 "successCount":0,"skippedCount":0,"failedCount":0,"totalCount":1,
				 "progressPercent":0,"checkpoint":{},"papers":[],"extractions":[]}}
				""".formatted(messageId, jobId, messageId);
	}

	private String progressMessage(UUID messageId) {
		return """
				{"version":1,"messageId":"%s","type":"ARXIV_JOB_PROGRESS","jobId":"%s",
				 "idempotencyKey":"source-progress:%s","traceId":"0123456789abcdef",
				 "occurredAt":"2026-08-06T01:00:01Z","payload":{
				 "status":"RUNNING","stage":"EXTRACTING_CONTACTS","processedCount":2,
				 "successCount":2,"skippedCount":0,"failedCount":0,"totalCount":2,
				 "progressPercent":100,"checkpoint":{},"papers":[],"extractions":[]}}
				""".formatted(messageId, jobId, messageId);
	}

	private String failureMessage(UUID messageId) {
		return """
				{"version":1,"messageId":"%s","type":"ARXIV_JOB_FAILED","jobId":"%s",
				 "idempotencyKey":"source-failed:%s","traceId":"0123456789abcdef",
				 "occurredAt":"2026-08-06T01:00:01Z","payload":{
				 "status":"FAILED","stage":"FAILED","processedCount":52,
				 "successCount":52,"skippedCount":0,"failedCount":0,"totalCount":100,
				 "progressPercent":52,"checkpoint":{},"papers":[],"extractions":[],
				 "errorCode":"WORKER_RETRY_EXHAUSTED",
				 "errorSummary":"Worker\\u0001stopped before the source batch completed"}}
				""".formatted(messageId, jobId, messageId);
	}

	private void seedPartiallyProcessedItems() {
		databaseClient.sql("""
				UPDATE job_items SET status = 'SUCCEEDED', completed_at = now()
				WHERE job_id = :jobId
				""").bind("jobId", jobId).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO job_items (job_id, external_key, status, completed_at)
				SELECT :jobId, 'checkpoint-item-' || value,
				       CASE WHEN value <= 50 THEN 'SUCCEEDED' ELSE 'PENDING' END,
				       CASE WHEN value <= 50 THEN now() ELSE NULL END
				FROM generate_series(1, 99) AS value
				""").bind("jobId", jobId).fetch().rowsUpdated().block();
		databaseClient.sql("""
				UPDATE jobs SET processed_count = 52, success_count = 52, total_count = 100,
				  progress_percent = 52, current_stage = 'EXTRACTING_CONTACTS'
				WHERE id = :jobId
				""").bind("jobId", jobId).fetch().rowsUpdated().block();
	}

	private void clear() {
		databaseClient.sql("DELETE FROM processed_messages").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM worker_heartbeats").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM outbox_messages").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM jobs").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM papers").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM contacts").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM authors").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_categories").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_archives").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM arxiv_groups").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();
	}

	private void seed() {
		databaseClient.sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES (:actor, 'result-user', 'result-user@example.invalid', 'hash', 'Result User')
				""").bind("actor", ACTOR).fetch().rowsUpdated().block();
		databaseClient.sql("""
				WITH g AS (
				  INSERT INTO arxiv_groups (group_id, group_name) VALUES ('cs', 'Computer Science') RETURNING id
				), a AS (
				  INSERT INTO arxiv_archives (group_ref_id, archive_id, archive_name)
				  SELECT id, 'cs', 'Computer Science' FROM g RETURNING id, group_ref_id
				)
				INSERT INTO arxiv_categories (
				  group_ref_id, archive_ref_id, group_id, group_name, archive_id, archive_name,
				  category_id, category_name)
				SELECT group_ref_id, id, 'cs', 'Computer Science', 'cs', 'Computer Science',
				       'cs.AI', 'Artificial Intelligence' FROM a
				""").fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO papers (
				  id, arxiv_id, title, abstract_text, primary_category_id,
				  submitted_at, updated_at, pdf_url)
				SELECT :paper, '2608.00001', 'Source Paper', 'Abstract', id,
				       now(), now(), 'https://arxiv.org/pdf/2608.00001'
				FROM arxiv_categories WHERE category_id = 'cs.AI'
				""").bind("paper", PAPER).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO authors (id, normalized_name, display_name)
				VALUES (:author, 'alice metadata', 'Alice Metadata')
				""").bind("author", AUTHOR).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO paper_authors (
				  paper_id, author_id, author_order, raw_name, affiliation_data)
				VALUES (:paper, :author, 1, 'Alice Metadata', '[]'::jsonb)
				""").bind("paper", PAPER).bind("author", AUTHOR).fetch().rowsUpdated().block();
		jobId = UUID.randomUUID();
		databaseClient.sql("""
				INSERT INTO jobs (
				  id, type, status, created_by, parameters, idempotency_key, total_count, current_stage)
				VALUES (:job, 'ARXIV_FETCH_AND_PARSE_SOURCE', 'RUNNING', :actor,
				        jsonb_build_object('targets', jsonb_build_array(jsonb_build_object(
				          'paperId', :paper, 'arxivId', '2608.00001')), 'parserVersion', '0.1.0'),
				        :key, 1, 'DOWNLOADING_SOURCE')
				""").bind("job", jobId).bind("actor", ACTOR).bind("paper", PAPER)
				.bind("key", "source-result-job:" + jobId).fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO job_items (job_id, external_key, status)
				VALUES (:job, :paper, 'RUNNING')
				""").bind("job", jobId).bind("paper", PAPER.toString()).fetch().rowsUpdated().block();
	}

	private ContactCrypto crypto() {
		return new ContactCrypto(new ContactDataProtectionProperties(
				key("0123456789abcdef0123456789abcdef"),
				key("abcdef0123456789abcdef0123456789")));
	}

	private String key(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private long count(String table) {
		return databaseClient.sql("SELECT count(*) AS total FROM " + table)
				.map((row, metadata) -> row.get("total", Long.class)).one().block();
	}

	private String text(String sql) {
		return databaseClient.sql(sql).map((row, metadata) -> row.get(0, String.class)).one().block();
	}

	private long number(String sql) {
		return databaseClient.sql(sql).map((row, metadata) -> row.get(0, Long.class)).one().block();
	}

	private boolean flag(String sql) {
		return databaseClient.sql(sql).map((row, metadata) -> row.get(0, Boolean.class)).one().block();
	}

	private byte[] bytes(String column) {
		return databaseClient.sql("SELECT " + column + " FROM contacts LIMIT 1")
				.map((row, metadata) -> row.get(0, byte[].class)).one().block();
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
