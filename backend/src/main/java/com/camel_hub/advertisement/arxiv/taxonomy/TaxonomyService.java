package com.camel_hub.advertisement.arxiv.taxonomy;

import com.camel_hub.advertisement.arxiv.api.ArxivTaxonomyDtos;
import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TaxonomyService {

	private final TaxonomyRepository repository;
	private final TaxonomySnapshotLoader snapshotLoader;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final TransactionalOperator transactions;

	public TaxonomyService(
			TaxonomyRepository repository,
			TaxonomySnapshotLoader snapshotLoader,
			AuditService auditService,
			SensitiveValueHasher hasher,
			TransactionalOperator transactions
	) {
		this.repository = repository;
		this.snapshotLoader = snapshotLoader;
		this.auditService = auditService;
		this.hasher = hasher;
		this.transactions = transactions;
	}

	public Mono<Void> bootstrapOffline() {
		return repository.hasActiveSnapshot()
				.flatMap(present -> present
						? Mono.empty()
						: repository.applySnapshot(snapshotLoader.loadDefault()).as(transactions::transactional).then());
	}

	public Mono<ArxivTaxonomyDtos.TaxonomyResponse> tree() {
		return repository.loadActive().map(this::toResponse);
	}

	public Mono<ArxivTaxonomyDtos.TaxonomySyncResponse> requestSync(
			UUID actorUserId,
			AuthenticationRequestContext context
	) {
		String dayKey = LocalDate.now(ZoneOffset.UTC).toString();
		return repository.createOrFindDailySyncJob(actorUserId, dayKey)
				.flatMap(job -> auditService.record(new AuditEvent(
						actorUserId, "ARXIV_TAXONOMY_SYNC_REQUESTED", "JOB", job.id().toString(),
						hasher.hash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
						Map.of(), Map.of("jobStatus", job.status(), "created", job.created()),
						AuditResult.SUCCESS, null))
						.thenReturn(new ArxivTaxonomyDtos.TaxonomySyncResponse(
								job.id(), job.status(), job.created())))
				.as(transactions::transactional);
	}

	private ArxivTaxonomyDtos.TaxonomyResponse toResponse(TaxonomyRepository.TaxonomyData data) {
		Map<String, GroupBuilder> groups = new LinkedHashMap<>();
		for (TaxonomyCategory category : data.categories()) {
			GroupBuilder group = groups.computeIfAbsent(
					category.groupId(), ignored -> new GroupBuilder(category.groupId(), category.groupName()));
			ArchiveBuilder archive = group.archives.computeIfAbsent(
					category.archiveId(), ignored -> new ArchiveBuilder(category.archiveId(), category.archiveName()));
			archive.categories.add(new ArxivTaxonomyDtos.CategoryResponse(
					category.categoryId(), category.categoryName(), category.description(),
					category.alias(), category.aliasTarget()));
		}
		List<ArxivTaxonomyDtos.GroupResponse> groupResponses = groups.values().stream()
				.map(GroupBuilder::response)
				.toList();
		return new ArxivTaxonomyDtos.TaxonomyResponse(
				data.snapshotVersion(), data.sourceType(), data.sourceUrls(), data.sourceUpdatedAt(),
				data.syncedAt(), groupResponses);
	}

	private static final class GroupBuilder {
		private final String id;
		private final String name;
		private final Map<String, ArchiveBuilder> archives = new LinkedHashMap<>();

		private GroupBuilder(String id, String name) {
			this.id = id;
			this.name = name;
		}

		private ArxivTaxonomyDtos.GroupResponse response() {
			return new ArxivTaxonomyDtos.GroupResponse(
					id, name, archives.values().stream().map(ArchiveBuilder::response).toList());
		}
	}

	private static final class ArchiveBuilder {
		private final String id;
		private final String name;
		private final List<ArxivTaxonomyDtos.CategoryResponse> categories = new ArrayList<>();

		private ArchiveBuilder(String id, String name) {
			this.id = id;
			this.name = name;
		}

		private ArxivTaxonomyDtos.ArchiveResponse response() {
			return new ArxivTaxonomyDtos.ArchiveResponse(id, name, List.copyOf(categories));
		}
	}
}
