package com.camel_hub.advertisement.email.template;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_template_test").withUsername("camel").withPassword("camel-test-only");
	private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final AuthenticationRequestContext CONTEXT =
			new AuthenticationRequestContext("127.0.0.1", "JUnit", "template-test");
	private static DatabaseClient databaseClient;
	private static ConnectionFactory connectionFactory;
	private TemplateService service;
	private TemplateRepository templateRepository;
	private TemplateAssetRepository assetRepository;
	private InMemoryAssetStore assetStore;
	private TemplateAssetSigner assetSigner;
	private AuditService audit;
	private SensitiveValueHasher hasher;

	@BeforeAll
	static void startDatabase() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.locations("classpath:db/migration").load().migrate();
		connectionFactory = ConnectionFactories.get(r2dbcUrl());
		databaseClient = DatabaseClient.create(connectionFactory);
	}

	@BeforeEach
	void setUp() {
		sql("TRUNCATE campaigns, email_template_versions, email_templates, audit_logs, users CASCADE");
		sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES ('10000000-0000-0000-0000-000000000001', 'template-admin',
				        'template-admin@example.invalid', 'hash', 'Template Admin')
				""");
		audit = mock(AuditService.class);
		hasher = mock(SensitiveValueHasher.class);
		when(audit.record(any())).thenReturn(Mono.empty());
		when(hasher.hash(any())).thenReturn(new byte[] {1, 2, 3});
		templateRepository = new TemplateRepository(databaseClient, new ObjectMapper());
		assetRepository = new TemplateAssetRepository(databaseClient);
		assetStore = new InMemoryAssetStore();
		assetSigner = new TemplateAssetSigner(
				"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "http://localhost:8080");
		service = new TemplateService(
				templateRepository, new TemplateEngine(102_400),
				new TemplateAssetCopyService(assetRepository, assetStore, assetSigner),
				audit, hasher, TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
	}

	@Test
	void createsImmutableVersionsRestoresByCreatingANewHeadAndRejectsStaleWrites() {
		var created = service.create(ACTOR, command("Research outreach", "First {{paper_title}}"), CONTEXT).block();
		assertThat(created.currentVersion()).isEqualTo(1);
		assertThat(created.lockVersion()).isZero();
		assertThat(created.htmlContent()).doesNotContain("onclick");

		var updated = service.update(ACTOR, created.id(), 0,
				command("Research outreach", "Second {{paper_title}}"), CONTEXT).block();
		assertThat(updated.currentVersion()).isEqualTo(2);
		assertThat(updated.lockVersion()).isEqualTo(1);
		assertThat(service.versions(created.id()).block()).extracting(TemplateService.TemplateVersionView::versionNumber)
				.containsExactly(2L, 1L);

		assertThatThrownBy(() -> service.update(ACTOR, created.id(), 0,
				command("Research outreach", "Stale"), CONTEXT).block())
				.isInstanceOf(TemplateConflictException.class);

		var restored = service.restore(ACTOR, created.id(), 1, 1, CONTEXT).block();
		assertThat(restored.currentVersion()).isEqualTo(3);
		assertThat(restored.subjectTemplate()).isEqualTo("First {{paper_title}}");
		assertThat(service.versions(created.id()).block()).hasSize(3);
	}

	@Test
	void copiesIndependentlyPreviewsEscapedValuesAndSoftDeletes() {
		var created = service.create(ACTOR, command("Original", "Paper {{paper_title}}"), CONTEXT).block();
		var copied = service.copy(ACTOR, created.id(), "Original copy", CONTEXT).block();

		assertThat(copied.id()).isNotEqualTo(created.id());
		assertThat(copied.status()).isEqualTo(TemplateRepository.TemplateStatus.DRAFT);
		var preview = service.preview(command("Preview", "Paper {{paper_title}}"), Map.of(
				"paper_title", "A < B", "author_name", "Ada & Bob",
				"paper_url", "https://arxiv.org/abs/1234.5678",
				"unsubscribe_url", "https://example.org/unsubscribe/1"));
		assertThat(preview.rendered().html()).contains("Ada &amp; Bob").contains("A &lt; B");

		service.delete(ACTOR, created.id(), 0, CONTEXT).block();
		assertThatThrownBy(() -> service.get(created.id()).block()).isInstanceOf(TemplateNotFoundException.class);
		assertThat(service.get(copied.id()).block()).isNotNull();
	}

	@Test
	void preservesAutoGeneratedTextModeAcrossVersionsRestoreAndCopy() {
		var created = service.create(ACTOR, autoTextCommand("Automatic", "First body"), CONTEXT).block();
		assertThat(created.autoGenerateText()).isTrue();
		assertThat(created.textContent()).contains("First body");

		var updated = service.update(ACTOR, created.id(), 0,
				autoTextCommand("Automatic", "Second body"), CONTEXT).block();
		assertThat(updated.autoGenerateText()).isTrue();
		assertThat(updated.textContent()).contains("Second body").doesNotContain("First body");
		assertThat(service.versions(created.id()).block())
				.extracting(TemplateService.TemplateVersionView::autoGenerateText)
				.containsOnly(true);

		var restored = service.restore(ACTOR, created.id(), 1, 1, CONTEXT).block();
		assertThat(restored.autoGenerateText()).isTrue();
		assertThat(restored.textContent()).contains("First body");
		var copied = service.copy(ACTOR, created.id(), "Automatic copy", CONTEXT).block();
		assertThat(copied.autoGenerateText()).isTrue();
	}

	@Test
	void deepCopiesReferencedImagesSoTheCopySurvivesSourceArchival() {
		var created = service.create(ACTOR, command("Original with image", "Paper {{paper_title}}"), CONTEXT).block();
		byte[] image = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};
		String sourceObjectKey = "templates/" + created.id() + "/source.png";
		assetStore.put(sourceObjectKey, "image/png", image).block();
		var sourceAsset = assetRepository.create(created.id(), sourceObjectKey, "figure.png", "image/png",
				image.length, sha256(image), ACTOR).block();
		String sourceUrl = assetSigner.path(created.id(), sourceAsset.id());
		var imageCommand = new TemplateService.TemplateCommand(
				created.name(), created.description(), "DRAFT",
				new TemplateModels.TemplateDraft(created.subjectTemplate(), created.fromNameTemplate(), created.replyTo(),
						"<p>Hello {{author_name}}</p><img src=\"" + sourceUrl
								+ "\"><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>",
						created.textContent(), false));
		var updated = service.update(ACTOR, created.id(), created.lockVersion(), imageCommand, CONTEXT).block();

		var copied = service.copy(ACTOR, updated.id(), "Independent image copy", CONTEXT).block();
		var copiedAsset = assetRepository.list(copied.id()).single().block();

		assertThat(copiedAsset).isNotNull();
		assertThat(copied.htmlContent()).contains(assetSigner.path(copied.id(), copiedAsset.id()))
				.doesNotContain(sourceUrl);
		service.delete(ACTOR, updated.id(), updated.lockVersion(), CONTEXT).block();
		var assetService = new TemplateAssetService(
				templateRepository, assetRepository, assetStore, audit, hasher, assetSigner, 5_242_880);
		assertThat(assetService.signedContent(copied.id(), copiedAsset.id(),
				assetSigner.signature(copied.id(), copiedAsset.id())).block().bytes()).isEqualTo(image);
	}

	private TemplateService.TemplateCommand command(String name, String subject) {
		return new TemplateService.TemplateCommand(name, "A reusable template", "DRAFT",
				new TemplateModels.TemplateDraft(subject, "Research Team", "reply@example.org",
						"<p onclick=\"bad()\">Hello {{author_name}} — {{paper_title}}</p>"
								+ "<a href=\"{{paper_url}}\">Paper</a>"
								+ "<a href=\"{{unsubscribe_url}}\">Unsubscribe</a>",
						"Hello {{author_name}} — {{paper_title}} {{paper_url}} {{unsubscribe_url}}", false));
	}

	private TemplateService.TemplateCommand autoTextCommand(String name, String body) {
		return new TemplateService.TemplateCommand(name, "Auto-generated plain text", "DRAFT",
				new TemplateModels.TemplateDraft("Subject", "Research Team", "reply@example.org",
						"<p>" + body + "</p><a href=\"{{unsubscribe_url}}\">Unsubscribe</a>", "", true));
	}

	private void sql(String statement) {
		databaseClient.sql(statement).fetch().rowsUpdated().block();
	}

	private byte[] sha256(byte[] value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static final class InMemoryAssetStore implements TemplateAssetObjectStore {
		private final Map<String, byte[]> values = new ConcurrentHashMap<>();

		@Override
		public Mono<Void> put(String objectKey, String contentType, byte[] bytes) {
			values.put(objectKey, Arrays.copyOf(bytes, bytes.length));
			return Mono.empty();
		}

		@Override
		public Mono<byte[]> get(String objectKey) {
			byte[] value = values.get(objectKey);
			return value == null ? Mono.empty() : Mono.just(Arrays.copyOf(value, value.length));
		}

		@Override
		public Mono<Void> remove(String objectKey) {
			values.remove(objectKey);
			return Mono.empty();
		}
	}

	private static String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
