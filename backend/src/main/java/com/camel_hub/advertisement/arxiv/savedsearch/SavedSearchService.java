package com.camel_hub.advertisement.arxiv.savedsearch;

import com.camel_hub.advertisement.arxiv.search.ArxivQueryNormalizer;
import com.camel_hub.advertisement.arxiv.search.ArxivSearchCriteria;
import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.text.Normalizer;
import java.util.Map;
import java.util.UUID;

public class SavedSearchService {

	private final SavedSearchRepository repository;
	private final ArxivQueryNormalizer normalizer;
	private final SavedSearchCategoryCatalog categoryCatalog;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final TransactionalOperator transactions;

	public SavedSearchService(
			SavedSearchRepository repository,
			ArxivQueryNormalizer normalizer,
			SavedSearchCategoryCatalog categoryCatalog,
			AuditService auditService,
			SensitiveValueHasher hasher,
			ObjectMapper objectMapper,
			TransactionalOperator transactions
	) {
		this.repository = repository;
		this.normalizer = normalizer;
		this.categoryCatalog = categoryCatalog;
		this.auditService = auditService;
		this.hasher = hasher;
		this.transactions = transactions;
	}

	public Mono<PageResponse<SavedSearchView>> list(UUID ownerId, int page, int pageSize) {
		if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100) {
			return Mono.error(new SavedSearchValidationException("Saved search page is invalid"));
		}
		int offset = Math.multiplyExact(page - 1, pageSize);
		return Mono.zip(
				repository.list(ownerId, offset, pageSize).map(this::view).collectList(),
				repository.count(ownerId))
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<SavedSearchView> get(UUID ownerId, UUID id) {
		return repository.find(ownerId, id)
				.switchIfEmpty(Mono.error(new SavedSearchNotFoundException())).map(this::view);
	}

	public Mono<SavedSearchView> create(
			UUID ownerId, String name, ArxivSearchCriteria criteria, AuthenticationRequestContext context
	) {
		return normalized(name, criteria)
				.flatMap(value -> repository.create(
						ownerId, value.name(), value.query().canonicalJson(), value.query().queryHash()))
				.flatMap(created -> audit("SAVED_SEARCH_CREATED", created, ownerId, context).thenReturn(created))
				.map(this::view).as(transactions::transactional)
				.onErrorMap(DataIntegrityViolationException.class, exception -> new SavedSearchConflictException());
	}

	public Mono<SavedSearchView> update(
			UUID ownerId, UUID id, String name, ArxivSearchCriteria criteria,
			AuthenticationRequestContext context
	) {
		return repository.find(ownerId, id).switchIfEmpty(Mono.error(new SavedSearchNotFoundException()))
				.then(normalized(name, criteria))
				.flatMap(value -> repository.update(
						ownerId, id, value.name(), value.query().canonicalJson(), value.query().queryHash()))
				.switchIfEmpty(Mono.error(new SavedSearchNotFoundException()))
				.flatMap(updated -> audit("SAVED_SEARCH_UPDATED", updated, ownerId, context).thenReturn(updated))
				.map(this::view).as(transactions::transactional)
				.onErrorMap(DataIntegrityViolationException.class, exception -> new SavedSearchConflictException());
	}

	public Mono<Void> delete(UUID ownerId, UUID id, AuthenticationRequestContext context) {
		return repository.find(ownerId, id).switchIfEmpty(Mono.error(new SavedSearchNotFoundException()))
				.flatMap(existing -> repository.delete(ownerId, id)
						.flatMap(rows -> rows == 1
								? audit("SAVED_SEARCH_DELETED", existing, ownerId, context)
								: Mono.error(new SavedSearchNotFoundException())))
				.as(transactions::transactional);
	}

	private Mono<NormalizedSavedSearch> normalized(String rawName, ArxivSearchCriteria criteria) {
		return Mono.defer(() -> {
			String name = rawName == null ? "" : Normalizer.normalize(rawName, Normalizer.Form.NFKC).strip();
			if (name.isEmpty() || name.length() > 160 || name.codePoints().anyMatch(Character::isISOControl)) {
				return Mono.error(new SavedSearchValidationException(
						"Saved search name must contain between one and 160 safe characters"));
			}
			return categoryCatalog.activeCategoryIds()
					.map(categories -> new NormalizedSavedSearch(name, normalizer.normalize(criteria, categories)));
		}).onErrorMap(IllegalArgumentException.class,
				exception -> new SavedSearchValidationException(exception.getMessage()));
	}

	private Mono<Void> audit(
			String action, SavedSearchRepository.SavedSearchRecord search,
			UUID actorId, AuthenticationRequestContext context
	) {
		return auditService.record(new AuditEvent(
				actorId, action, "SAVED_SEARCH", search.id().toString(), hasher.hash(context.ipAddress()),
				context.userAgentSummary(), context.traceId(), Map.of(),
				Map.of("name", search.name(), "criteriaHash", search.criteriaHash()),
				AuditResult.SUCCESS, null));
	}

	private SavedSearchView view(SavedSearchRepository.SavedSearchRecord record) {
		return new SavedSearchView(
				record.id(), record.name(), record.criteria(), record.criteriaHash(),
				record.createdAt(), record.updatedAt());
	}

	private record NormalizedSavedSearch(String name, ArxivQueryNormalizer.NormalizedQuery query) {
	}

	public record SavedSearchView(
			UUID id, String name, ArxivSearchCriteria criteria, String criteriaHash,
			Instant createdAt, Instant updatedAt
	) {
	}
}
