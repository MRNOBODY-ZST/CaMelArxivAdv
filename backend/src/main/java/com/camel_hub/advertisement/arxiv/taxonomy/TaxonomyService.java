package com.camel_hub.advertisement.arxiv.taxonomy;

import com.camel_hub.advertisement.arxiv.api.ArxivTaxonomyDtos;
import com.camel_hub.advertisement.arxiv.importing.ArxivImportService;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TaxonomyService {

	private final TaxonomyRepository repository;
	private final TaxonomySnapshotLoader snapshotLoader;
	private final ArxivImportService importService;
	private final TransactionalOperator transactions;

	public TaxonomyService(
			TaxonomyRepository repository,
			TaxonomySnapshotLoader snapshotLoader,
			ArxivImportService importService,
			TransactionalOperator transactions
	) {
		this.repository = repository;
		this.snapshotLoader = snapshotLoader;
		this.importService = importService;
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
		return importService.createTaxonomySync(actorUserId, context)
				.map(job -> new ArxivTaxonomyDtos.TaxonomySyncResponse(
						job.jobId(), job.status(), job.created()));
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
