package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.common.api.PageResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class SegmentService {

	private static final int SAMPLE_SIZE = 5;
	private final SegmentRepository repository;
	private final TransactionalOperator transactions;

	public SegmentService(SegmentRepository repository, TransactionalOperator transactions) {
		this.repository = repository;
		this.transactions = transactions;
	}

	public Mono<PageResponse<SegmentView>> list(int page, int pageSize) {
		validatePage(page, pageSize);
		int offset = Math.multiplyExact(page - 1, pageSize);
		return Mono.zip(repository.list(offset, pageSize).concatMap(header -> view(header.id())).collectList(),
				repository.count()).map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<SegmentView> get(UUID id) {
		return view(id);
	}

	public Mono<SegmentView> create(UUID actorId, SegmentCommand command) {
		SegmentCommand normalized = normalize(command);
		SegmentModels.criteria(normalized.rules());
		Mono<UUID> mutation = repository.create(normalized.name(), normalized.description(), actorId)
				.flatMap(segmentId -> Flux.fromIterable(normalized.rules()).index()
						.concatMap(indexed -> repository.insertRule(
								segmentId, Math.toIntExact(indexed.getT1()) + 1, indexed.getT2()))
						.then(Mono.just(segmentId)));
		return transactions.transactional(mutation).flatMap(this::view);
	}

	public Mono<PreviewView> preview(List<SegmentModels.RuleInput> rules) {
		SegmentModels.SegmentCriteria criteria = SegmentModels.criteria(rules);
		return Mono.zip(repository.eligibleCount(criteria), repository.eligibleSample(criteria, SAMPLE_SIZE).collectList())
				.map(tuple -> new PreviewView(tuple.getT1(), tuple.getT2()));
	}

	private Mono<SegmentView> view(UUID id) {
		return repository.find(id)
				.switchIfEmpty(Mono.error(new SegmentNotFoundException("Segment was not found")))
				.flatMap(header -> repository.rules(id).collectList().flatMap(rules -> {
					SegmentModels.SegmentCriteria criteria = SegmentModels.criteria(rules);
					return repository.eligibleCount(criteria).map(count -> new SegmentView(
							header.id(), header.name(), header.description(), rules, count,
							header.createdAt(), header.updatedAt()));
				}));
	}

	private SegmentCommand normalize(SegmentCommand command) {
		if (command == null || command.name() == null) {
			throw new SegmentValidationException("Segment name is required");
		}
		String name = command.name().strip();
		String description = command.description() == null ? null : command.description().strip();
		if (name.isEmpty() || name.length() > 160) {
			throw new SegmentValidationException("Segment name must contain 1 to 160 characters");
		}
		if (description != null && description.length() > 500) {
			throw new SegmentValidationException("Segment description must not exceed 500 characters");
		}
		return new SegmentCommand(name, description == null || description.isEmpty() ? null : description,
				command.rules() == null ? List.of() : List.copyOf(command.rules()));
	}

	private void validatePage(int page, int pageSize) {
		if (page < 1 || pageSize < 1 || pageSize > 100) {
			throw new SegmentValidationException("Page must be at least 1 and pageSize between 1 and 100");
		}
	}

	public record SegmentCommand(String name, String description, List<SegmentModels.RuleInput> rules) { }

	public record SegmentView(
			UUID id, String name, String description, List<SegmentModels.RuleInput> rules,
			long eligibleCount, Instant createdAt, Instant updatedAt
	) {
		public SegmentView {
			rules = List.copyOf(rules);
		}
	}

	public record PreviewView(long eligibleCount, List<SegmentRepository.EligibleContact> sample) {
		public PreviewView {
			sample = List.copyOf(sample);
		}
	}
}
