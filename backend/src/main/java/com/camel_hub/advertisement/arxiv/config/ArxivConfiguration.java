package com.camel_hub.advertisement.arxiv.config;

import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomySnapshotLoader;
import com.camel_hub.advertisement.arxiv.client.GlobalArxivRateLease;
import com.camel_hub.advertisement.arxiv.client.RedisGlobalArxivRateLease;
import com.camel_hub.advertisement.arxiv.client.ArxivLegacyClient;
import com.camel_hub.advertisement.arxiv.client.AtomFeedParser;
import com.camel_hub.advertisement.arxiv.search.ArxivLegacyQueryBuilder;
import com.camel_hub.advertisement.arxiv.search.ArxivPreviewCache;
import com.camel_hub.advertisement.arxiv.search.ArxivPreviewService;
import com.camel_hub.advertisement.arxiv.search.ArxivQueryNormalizer;
import com.camel_hub.advertisement.arxiv.search.RedisArxivPreviewCache;
import com.camel_hub.advertisement.arxiv.savedsearch.SavedSearchCategoryCatalog;
import com.camel_hub.advertisement.arxiv.savedsearch.SavedSearchRepository;
import com.camel_hub.advertisement.arxiv.savedsearch.SavedSearchService;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportCatalog;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportRepository;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportService;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyBootstrap;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyRepository;
import com.camel_hub.advertisement.arxiv.taxonomy.TaxonomyService;
import com.camel_hub.advertisement.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(ArxivProperties.class)
public class ArxivConfiguration {

	@Bean
	TaxonomySnapshotLoader taxonomySnapshotLoader(ObjectMapper objectMapper, ArxivProperties properties) {
		return new TaxonomySnapshotLoader(objectMapper, properties.taxonomySnapshot());
	}

	@Bean
	@ConditionalOnBean(ReactiveStringRedisTemplate.class)
	GlobalArxivRateLease globalArxivRateLease(
			ReactiveStringRedisTemplate redis,
			ArxivProperties properties
	) {
		return new RedisGlobalArxivRateLease(redis, properties.minRequestInterval());
	}

	@Bean
	AtomFeedParser atomFeedParser() {
		return new AtomFeedParser();
	}

	@Bean
	ArxivLegacyQueryBuilder arxivLegacyQueryBuilder() {
		return new ArxivLegacyQueryBuilder();
	}

	@Bean
	ArxivQueryNormalizer arxivQueryNormalizer(ObjectMapper objectMapper, ArxivProperties properties) {
		return new ArxivQueryNormalizer(objectMapper, properties.previewMaxPageSize());
	}

	@Bean
	@ConditionalOnBean(GlobalArxivRateLease.class)
	ArxivLegacyClient arxivLegacyClient(
			GlobalArxivRateLease rateLease,
			ArxivProperties properties,
			AtomFeedParser parser
	) {
		ExchangeStrategies strategies = ExchangeStrategies.builder()
				.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
				.build();
		return new ArxivLegacyClient(
				WebClient.builder().exchangeStrategies(strategies).build(), rateLease, properties, parser);
	}

	@Bean
	@ConditionalOnBean(ReactiveStringRedisTemplate.class)
	ArxivPreviewCache arxivPreviewCache(
			ReactiveStringRedisTemplate redis,
			ObjectMapper objectMapper,
			ArxivProperties properties
	) {
		return new RedisArxivPreviewCache(redis, objectMapper, properties.previewCacheTtl());
	}

	@Bean
	@ConditionalOnBean({ArxivLegacyClient.class, ArxivPreviewCache.class})
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	ArxivPreviewService arxivPreviewService(
			TaxonomyRepository taxonomyRepository,
			ArxivQueryNormalizer normalizer,
			ArxivLegacyQueryBuilder queryBuilder,
			ArxivLegacyClient client,
			ArxivPreviewCache cache
	) {
		return new ArxivPreviewService(taxonomyRepository, normalizer, queryBuilder, client, cache);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TaxonomyRepository taxonomyRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new TaxonomyRepository(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TaxonomyService taxonomyService(
			TaxonomyRepository repository,
			TaxonomySnapshotLoader snapshotLoader,
			AuditService auditService,
			SensitiveValueHasher hasher,
			TransactionalOperator transactions
	) {
		return new TaxonomyService(repository, snapshotLoader, auditService, hasher, transactions);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	TaxonomyBootstrap taxonomyBootstrap(TaxonomyService service) {
		return new TaxonomyBootstrap(service);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	SavedSearchRepository savedSearchRepository(DatabaseClient databaseClient, ObjectMapper objectMapper) {
		return new SavedSearchRepository(databaseClient, objectMapper);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	SavedSearchCategoryCatalog savedSearchCategoryCatalog(TaxonomyRepository repository) {
		return repository::activeCategoryIds;
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	SavedSearchService savedSearchService(
			SavedSearchRepository repository,
			ArxivQueryNormalizer normalizer,
			SavedSearchCategoryCatalog categoryCatalog,
			AuditService auditService,
			SensitiveValueHasher hasher,
			ObjectMapper objectMapper,
			TransactionalOperator transactions
	) {
		return new SavedSearchService(
				repository, normalizer, categoryCatalog, auditService, hasher, objectMapper, transactions);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	ArxivImportRepository arxivImportRepository(DatabaseClient databaseClient) {
		return new ArxivImportRepository(databaseClient);
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	ArxivImportCatalog arxivImportCatalog(TaxonomyRepository repository) {
		return repository::activeImportIdentifiers;
	}

	@Bean
	@ConditionalOnProperty(
			prefix = "app.persistence", name = "enabled", havingValue = "true", matchIfMissing = true)
	ArxivImportService arxivImportService(
			ArxivImportRepository repository,
			ArxivQueryNormalizer normalizer,
			ArxivImportCatalog catalog,
			AuditService auditService,
			SensitiveValueHasher hasher,
			ObjectMapper objectMapper,
			ArxivProperties properties,
			TransactionalOperator transactions
	) {
		return new ArxivImportService(
				repository, normalizer, catalog, auditService, hasher, objectMapper,
				properties.importMaxPapers(), transactions);
	}
}
