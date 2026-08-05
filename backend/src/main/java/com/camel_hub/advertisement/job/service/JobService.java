package com.camel_hub.advertisement.job.service;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.camel_hub.advertisement.job.domain.JobAction;
import com.camel_hub.advertisement.job.domain.JobStateMachine;
import com.camel_hub.advertisement.job.domain.JobStatus;
import com.camel_hub.advertisement.job.persistence.JobRepository;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class JobService {

	private static final Pattern JOB_TYPE = Pattern.compile("^ARXIV_[A-Z0-9_]{1,52}$");
	private static final Duration HEARTBEAT_STALE_AFTER = Duration.ofSeconds(45);

	private final JobRepository repository;
	private final JobStateMachine stateMachine;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final TransactionalOperator transactions;

	public JobService(
			JobRepository repository,
			JobStateMachine stateMachine,
			AuditService auditService,
			SensitiveValueHasher hasher,
			TransactionalOperator transactions
	) {
		this.repository = repository;
		this.stateMachine = stateMachine;
		this.auditService = auditService;
		this.hasher = hasher;
		this.transactions = transactions;
	}

	public Mono<PageResponse<JobView>> list(
			int page, int pageSize, JobStatus status, String type
	) {
		validatePage(page, pageSize);
		String normalizedType = normalizeType(type);
		String normalizedStatus = status == null ? null : status.name();
		int offset = Math.multiplyExact(page - 1, pageSize);
		return Mono.zip(
				repository.list(offset, pageSize, normalizedStatus, normalizedType).map(this::view).collectList(),
				repository.count(normalizedStatus, normalizedType))
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<JobView> get(UUID id) {
		return repository.find(id).switchIfEmpty(Mono.error(new JobNotFoundException())).map(this::view);
	}

	public Mono<List<JobEventView>> events(UUID id, long afterId, int limit) {
		if (afterId < 0 || limit < 1 || limit > 500) {
			return Mono.error(new IllegalArgumentException("job event page is invalid"));
		}
		return repository.find(id).switchIfEmpty(Mono.error(new JobNotFoundException()))
				.then(repository.events(id, afterId, limit).map(this::eventView).collectList());
	}

	public Mono<JobView> control(
			UUID id,
			JobAction action,
			UUID actorUserId,
			AuthenticationRequestContext context
	) {
		return repository.find(id)
				.switchIfEmpty(Mono.error(new JobNotFoundException()))
				.flatMap(original -> action == JobAction.RETRY
						? retry(original, actorUserId, context)
						: transition(original, action, actorUserId, context))
				.as(transactions::transactional);
	}

	private Mono<JobView> transition(
			JobRepository.JobRecord original,
			JobAction action,
			UUID actorUserId,
			AuthenticationRequestContext context
	) {
		JobStatus target;
		try {
			target = stateMachine.transition(original.status(), action);
		}
		catch (IllegalStateException exception) {
			return Mono.error(new InvalidJobStateException(exception.getMessage()));
		}
		boolean paused = target == JobStatus.PAUSED;
		boolean canceled = target == JobStatus.CANCELED;
		return repository.updateStatus(original.id(), original.version(), target, paused, canceled)
				.flatMap(rows -> rows == 1
						? repository.appendEvent(
								original.id(), eventType(action), stage(target), eventMessage(action))
						: Mono.error(new JobConflictException("Job changed while the command was being applied")))
				.then(audit(original.id(), action, original.status(), target, actorUserId, context))
				.then(repository.find(original.id()))
				.map(this::view);
	}

	private Mono<JobView> retry(
			JobRepository.JobRecord original,
			UUID actorUserId,
			AuthenticationRequestContext context
	) {
		try {
			stateMachine.transition(original.status(), JobAction.RETRY);
		}
		catch (IllegalStateException exception) {
			return Mono.error(new InvalidJobStateException(exception.getMessage()));
		}
		return repository.createRetry(original, actorUserId)
				.switchIfEmpty(Mono.error(new JobConflictException("Retry job could not be created")))
				.flatMap(retry -> repository.appendEvent(
						original.id(), "JOB_RETRIED", original.currentStage(), "A retry job was created")
						.then(repository.appendEvent(
								retry.id(), "JOB_CREATED", "WAITING_FOR_WORKER", "Retry job created"))
						.then(audit(original.id(), JobAction.RETRY, original.status(), JobStatus.PENDING,
								actorUserId, context))
						.thenReturn(view(retry)));
	}

	private Mono<Void> audit(
			UUID id, JobAction action, JobStatus before, JobStatus after,
			UUID actorUserId, AuthenticationRequestContext context
	) {
		return auditService.record(new AuditEvent(
				actorUserId, "JOB_" + action.name(), "JOB", id.toString(),
				hasher.hash(context.ipAddress()), context.userAgentSummary(), context.traceId(),
				Map.of("status", before.name()), Map.of("status", after.name()),
				AuditResult.SUCCESS, null));
	}

	private JobView view(JobRepository.JobRecord job) {
		boolean stale = !job.status().isTerminal()
				&& (job.heartbeatAt() == null
				|| job.heartbeatAt().isBefore(Instant.now().minus(HEARTBEAT_STALE_AFTER)));
		return new JobView(
				job.id(), job.type(), job.status(), job.createdBy(), job.parentJobId(), job.rootJobId(),
				job.version(), job.totalCount(), job.processedCount(), job.successCount(),
				job.skippedCount(), job.failedCount(), job.currentStage(), job.progressPercent(),
				job.startedAt(), job.endedAt(), job.heartbeatAt(), job.createdAt(), job.updatedAt(),
				stale, job.errorSummary(), stateMachine.allowedActions(job.status()));
	}

	private JobEventView eventView(JobRepository.JobEventRecord event) {
		return new JobEventView(
				event.id(), event.eventType(), event.stage(), event.message(),
				event.details(), event.occurredAt());
	}

	private void validatePage(int page, int pageSize) {
		if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100) {
			throw new IllegalArgumentException("job page is invalid");
		}
	}

	private String normalizeType(String type) {
		if (type == null || type.isBlank()) {
			return null;
		}
		String normalized = type.strip().toUpperCase(java.util.Locale.ROOT);
		if (!JOB_TYPE.matcher(normalized).matches()) {
			throw new IllegalArgumentException("job type is invalid");
		}
		return normalized;
	}

	private String eventType(JobAction action) {
		return "JOB_" + switch (action) {
			case PAUSE -> "PAUSED";
			case RESUME -> "RESUMED";
			case CANCEL -> "CANCELED";
			case RETRY -> "RETRIED";
		};
	}

	private String stage(JobStatus status) {
		return switch (status) {
			case PAUSED -> "PAUSED_BY_USER";
			case QUEUED -> "WAITING_FOR_WORKER";
			case CANCELED -> "CANCELED_BY_USER";
			default -> status.name();
		};
	}

	private String eventMessage(JobAction action) {
		return switch (action) {
			case PAUSE -> "Job paused by user";
			case RESUME -> "Job resumed by user";
			case CANCEL -> "Job canceled by user";
			case RETRY -> "Job retry requested";
		};
	}

	public record JobView(
			UUID id, String type, JobStatus status, UUID createdBy,
			UUID parentJobId, UUID rootJobId, long version,
			long totalCount, long processedCount, long successCount, long skippedCount, long failedCount,
			String currentStage, double progressPercent, Instant startedAt, Instant endedAt,
			Instant heartbeatAt, Instant createdAt, Instant updatedAt,
			boolean workerStale, String errorSummary, Set<JobAction> allowedActions
	) {
	}

	public record JobEventView(
			long id, String eventType, String stage, String message, String details, Instant occurredAt
	) {
	}
}
