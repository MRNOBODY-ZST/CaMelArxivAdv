package com.camel_hub.advertisement.arxiv.savedsearch;

import com.camel_hub.advertisement.arxiv.search.ArxivQueryNormalizer;
import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SavedSearchServiceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_saved_search_test")
			.withUsername("camel")
			.withPassword("camel-test-only");
	private static final UUID OWNER = UUID.fromString("e24aa469-78d0-4754-a664-181c425b8281");
	private static final UUID OTHER_OWNER = UUID.fromString("628010de-b400-4074-97d8-3859ae8c5d1f");
	private SavedSearchService service;

	@BeforeEach
	void setUp() {
		if (!POSTGRES.isRunning()) {
			POSTGRES.start();
			Flyway.configure().dataSource(
					POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration").load().migrate();
		}
		var connectionFactory = ConnectionFactories.get(r2dbcUrl());
		var databaseClient = DatabaseClient.create(connectionFactory);
		databaseClient.sql("DELETE FROM saved_searches").fetch().rowsUpdated().block();
		databaseClient.sql("DELETE FROM users").fetch().rowsUpdated().block();
		databaseClient.sql("""
				INSERT INTO users (id, username, email, password_hash, display_name)
				VALUES (:owner, 'saved-owner', 'owner@example.invalid', 'hash', 'Owner'),
				       (:other, 'saved-other', 'other@example.invalid', 'hash', 'Other')
				""").bind("owner", OWNER).bind("other", OTHER_OWNER).fetch().rowsUpdated().block();
		AuditService audit = mock(AuditService.class);
		when(audit.record(any())).thenReturn(Mono.empty());
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(hasher.hash(any())).thenReturn(new byte[] {1, 2, 3});
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		service = new SavedSearchService(
				new SavedSearchRepository(databaseClient, mapper),
				new ArxivQueryNormalizer(mapper, 200),
				() -> Mono.just(Set.of("cs.AI", "cs.LG")),
				audit, hasher, mapper,
				TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory)));
	}

	@Test
	void createsListsUpdatesAndDeletesAnOwnerScopedNormalizedSearch() {
		var created = service.create(OWNER, "  Agents  ", criteria(List.of("cs.LG", "cs.AI", "cs.AI")), context()).block();

		assertThat(created.name()).isEqualTo("Agents");
		assertThat(created.criteria().categoryIds()).containsExactly("cs.AI", "cs.LG");
		assertThat(created.criteriaHash()).hasSize(64);
		assertThat(service.list(OWNER, 1, 20).block().items()).extracting(SavedSearchService.SavedSearchView::id)
				.containsExactly(created.id());
		assertThatThrownBy(() -> service.get(OTHER_OWNER, created.id()).block())
				.isInstanceOf(SavedSearchNotFoundException.class);

		var updated = service.update(OWNER, created.id(), "Robust Agents", criteria(List.of("cs.AI")), context()).block();
		assertThat(updated.name()).isEqualTo("Robust Agents");
		assertThat(updated.criteriaHash()).isNotEqualTo(created.criteriaHash());

		service.delete(OWNER, created.id(), context()).block();
		assertThat(service.list(OWNER, 1, 20).block().items()).isEmpty();
	}

	@Test
	void rejectsAnUnknownCategoryBeforePersistence() {
		assertThatThrownBy(() -> service.create(OWNER, "Unknown", criteria(List.of("cs.UNKNOWN")), context()).block())
				.isInstanceOf(SavedSearchValidationException.class)
				.hasMessageContaining("inactive or unknown");
	}

	private ArxivSearchCriteria criteria(List<String> categories) {
		return new ArxivSearchCriteria(
				categories, ArxivSearchCriteria.CategoryMode.ANY,
				null, null, null, null, "agent", null, null,
				null, null, null, ArxivSearchCriteria.SortBy.RELEVANCE,
				ArxivSearchCriteria.SortOrder.DESCENDING, 1, 20);
	}

	private AuthenticationRequestContext context() {
		return new AuthenticationRequestContext("192.0.2.10", "saved-search-test", "saved-search-trace");
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
