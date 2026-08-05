package com.camel_hub.advertisement.arxiv.taxonomy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaxonomyRepositoryTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5-alpine")
			.withDatabaseName("camel_taxonomy_test")
			.withUsername("camel")
			.withPassword("camel-test-only");

	private TaxonomyRepository repository;
	private TransactionalOperator transactions;

	@BeforeEach
	void setUp() {
		if (!POSTGRES.isRunning()) {
			POSTGRES.start();
			Flyway.configure()
					.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration")
					.load()
					.migrate();
		}
		var connectionFactory = ConnectionFactories.get(r2dbcUrl());
		var databaseClient = DatabaseClient.create(connectionFactory);
		repository = new TaxonomyRepository(databaseClient, new ObjectMapper().findAndRegisterModules());
		transactions = TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
		databaseClient.sql("DELETE FROM arxiv_category_snapshots").fetch().rowsUpdated().block();
	}

	@Test
	void appliesASnapshotAndReturnsAStableTreeOrder() {
		repository.applySnapshot(snapshot("v1", List.of(
				category("math", "Mathematics", "math", "Mathematics", "math.NA", "Numerical Analysis"),
				category("cs", "Computer Science", "cs", "Computer Science", "cs.AI", "Artificial Intelligence"),
				alias("cs", "Computer Science", "cs", "Computer Science", "cs.NA", "Numerical Analysis", "math.NA"))))
				.as(transactions::transactional).block();

		TaxonomyRepository.TaxonomyData data = repository.loadActive().block();

		assertThat(data).isNotNull();
		assertThat(data.snapshotVersion()).isEqualTo("v1");
		assertThat(data.categories()).extracting(TaxonomyCategory::categoryId)
				.containsExactly("cs.AI", "cs.NA", "math.NA");
		assertThat(data.categories().get(1).aliasTarget()).isEqualTo("math.NA");
	}

	@Test
	void marksMissingCategoriesInactiveWithoutDeletingHistoricalRows() {
		repository.applySnapshot(snapshot("v1", List.of(
				category("math", "Mathematics", "math", "Mathematics", "math.NA", "Numerical Analysis"),
				category("cs", "Computer Science", "cs", "Computer Science", "cs.AI", "Artificial Intelligence"))))
				.as(transactions::transactional).block();
		repository.applySnapshot(snapshot("v2", List.of(
				category("math", "Mathematics", "math", "Mathematics", "math.NA", "Numerical Analysis"))))
				.as(transactions::transactional).block();

		TaxonomyRepository.TaxonomyData active = repository.loadActive().block();

		assertThat(active.snapshotVersion()).isEqualTo("v2");
		assertThat(active.categories()).extracting(TaxonomyCategory::categoryId)
				.containsExactly("math.NA");
		assertThat(repository.countCategoryRows("cs.AI").block()).isEqualTo(1L);
		assertThat(repository.isCategoryActive("cs.AI").block()).isFalse();
	}

	private TaxonomySnapshot snapshot(String version, List<TaxonomyCategory> categories) {
		return new TaxonomySnapshot(
				version, "OFFLINE_SNAPSHOT", List.of("https://arxiv.org/category_taxonomy"),
				Instant.parse("2026-08-05T00:00:00Z"), Instant.parse("2026-08-05T01:00:00Z"),
				String.format("%064d", Integer.parseInt(version.substring(1))), categories);
	}

	private TaxonomyCategory category(
			String groupId, String groupName, String archiveId, String archiveName,
			String categoryId, String categoryName
	) {
		return new TaxonomyCategory(
				groupId, groupName, archiveId, archiveName, categoryId, categoryName,
				"Official description", false, null);
	}

	private TaxonomyCategory alias(
			String groupId, String groupName, String archiveId, String archiveName,
			String categoryId, String categoryName, String target
	) {
		return new TaxonomyCategory(
				groupId, groupName, archiveId, archiveName, categoryId, categoryName,
				"Official alias", true, target);
	}

	private String r2dbcUrl() {
		return "r2dbc:postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
				+ "@" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
				+ "/" + POSTGRES.getDatabaseName();
	}
}
