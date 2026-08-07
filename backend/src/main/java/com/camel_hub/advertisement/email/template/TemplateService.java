package com.camel_hub.advertisement.email.template;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class TemplateService {

	private final TemplateRepository repository;
	private final TemplateEngine engine;
	private final TemplateAssetCopyService assetCopyService;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;
	private final TransactionalOperator transactions;

	public TemplateService(
			TemplateRepository repository, TemplateEngine engine, TemplateAssetCopyService assetCopyService,
			AuditService auditService,
			SensitiveValueHasher hasher, TransactionalOperator transactions
	) {
		this.repository = repository;
		this.engine = engine;
		this.assetCopyService = assetCopyService;
		this.auditService = auditService;
		this.hasher = hasher;
		this.transactions = transactions;
	}

	public Mono<PageResponse<TemplateView>> list(int page, int pageSize) {
		if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100) {
			return Mono.error(new TemplateValidationException("Template page is invalid"));
		}
		return Mono.zip(repository.list(Math.multiplyExact(page - 1, pageSize), pageSize).map(this::view).collectList(),
				repository.count()).map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<TemplateView> get(UUID id) {
		return repository.find(id).switchIfEmpty(Mono.error(new TemplateNotFoundException())).map(this::view);
	}

	public Mono<TemplateView> create(
			UUID actorId, TemplateCommand command, AuthenticationRequestContext context
	) {
		NormalizedCommand normalized = normalize(command);
		return repository.createTemplate(normalized.name(), normalized.description(), normalized.status(), actorId)
				.flatMap(metadata -> repository.insertVersion(
						metadata.id(), metadata.currentVersion(), normalized.prepared(), actorId).thenReturn(metadata))
				.flatMap(metadata -> repository.find(metadata.id()))
				.flatMap(created -> audit("EMAIL_TEMPLATE_CREATED", created, actorId, context).thenReturn(created))
				.map(this::view).as(transactions::transactional)
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new TemplateConflictException("An active template with this name already exists"));
	}

	public Mono<TemplateView> update(
			UUID actorId, UUID id, long expectedLockVersion, TemplateCommand command,
			AuthenticationRequestContext context
	) {
		NormalizedCommand normalized = normalize(command);
		return repository.find(id).switchIfEmpty(Mono.error(new TemplateNotFoundException()))
				.then(repository.advanceHead(id, expectedLockVersion, normalized.name(), normalized.description(),
						normalized.status(), actorId))
				.switchIfEmpty(Mono.error(new TemplateConflictException("Template changed; refresh before saving")))
				.flatMap(metadata -> repository.insertVersion(
						metadata.id(), metadata.currentVersion(), normalized.prepared(), actorId).thenReturn(metadata))
				.flatMap(metadata -> repository.find(metadata.id()))
				.flatMap(updated -> audit("EMAIL_TEMPLATE_UPDATED", updated, actorId, context).thenReturn(updated))
				.map(this::view).as(transactions::transactional)
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new TemplateConflictException("An active template with this name already exists"));
	}

	public Mono<List<TemplateVersionView>> versions(UUID id) {
		return repository.find(id).switchIfEmpty(Mono.error(new TemplateNotFoundException()))
				.thenMany(repository.versions(id)).map(this::versionView).collectList();
	}

	public Mono<TemplateView> restore(
			UUID actorId, UUID id, int versionNumber, long expectedLockVersion,
			AuthenticationRequestContext context
	) {
		return Mono.zip(
				repository.find(id).switchIfEmpty(Mono.error(new TemplateNotFoundException())),
				repository.findVersion(id, versionNumber).switchIfEmpty(Mono.error(new TemplateNotFoundException())))
				.flatMap(tuple -> {
					TemplateRepository.TemplateRecord current = tuple.getT1();
					TemplateRepository.TemplateVersionRecord old = tuple.getT2();
					var prepared = new TemplateModels.PreparedTemplate(
							old.subjectTemplate(), old.fromNameTemplate(), old.replyTo(), old.htmlContent(),
							old.textContent(), old.autoGenerateText(), old.contentSizeBytes(), old.validation());
					return repository.advanceHead(id, expectedLockVersion, current.name(), current.description(),
							current.status(), actorId).switchIfEmpty(Mono.error(
								new TemplateConflictException("Template changed; refresh before restoring")))
							.flatMap(metadata -> repository.insertVersion(
									metadata.id(), metadata.currentVersion(), prepared, actorId).thenReturn(metadata));
				})
				.flatMap(metadata -> repository.find(metadata.id()))
				.flatMap(restored -> audit("EMAIL_TEMPLATE_RESTORED", restored, actorId, context).thenReturn(restored))
				.map(this::view).as(transactions::transactional);
	}

	public Mono<TemplateView> copy(
			UUID actorId, UUID id, String copyName, AuthenticationRequestContext context
	) {
		AtomicReference<List<String>> copiedObjectKeys = new AtomicReference<>(List.of());
		return repository.find(id).switchIfEmpty(Mono.error(new TemplateNotFoundException()))
				.flatMap(source -> {
					String name = normalizedName(copyName);
					return repository.createTemplate(name, source.description(), TemplateRepository.TemplateStatus.DRAFT, actorId)
							.flatMap(metadata -> assetCopyService.copyReferencedAssets(
									source.id(), metadata.id(), source.htmlContent(), actorId)
									.doOnNext(result -> copiedObjectKeys.set(result.objectKeys()))
									.flatMap(result -> repository.insertVersion(
											metadata.id(), metadata.currentVersion(), copiedContent(source, result.html()),
											actorId).thenReturn(metadata)));
				})
				.flatMap(metadata -> repository.find(metadata.id()))
				.flatMap(copied -> audit("EMAIL_TEMPLATE_COPIED", copied, actorId, context).thenReturn(copied))
				.map(this::view).as(transactions::transactional)
				.onErrorResume(error -> assetCopyService.cleanup(copiedObjectKeys.get()).then(Mono.error(error)))
				.onErrorMap(DataIntegrityViolationException.class,
						exception -> new TemplateConflictException("An active template with this name already exists"));
	}

	private TemplateModels.PreparedTemplate copiedContent(
			TemplateRepository.TemplateRecord source, String copiedHtml
	) {
		return engine.prepare(new TemplateModels.TemplateDraft(
				source.subjectTemplate(), source.fromNameTemplate(), source.replyTo(), copiedHtml,
				source.textContent(), source.autoGenerateText()));
	}

	public Mono<Void> delete(
			UUID actorId, UUID id, long expectedLockVersion, AuthenticationRequestContext context
	) {
		return repository.find(id).switchIfEmpty(Mono.error(new TemplateNotFoundException()))
				.flatMap(existing -> repository.referencedCampaigns(id).flatMap(references -> {
					if (references > 0) return Mono.error(new TemplateConflictException("Template is referenced by a campaign"));
					return repository.softDelete(id, expectedLockVersion, actorId)
							.flatMap(rows -> rows == 1
									? audit("EMAIL_TEMPLATE_ARCHIVED", existing, actorId, context)
									: Mono.error(new TemplateConflictException("Template changed; refresh before archiving")));
				})).as(transactions::transactional);
	}

	public PreviewView preview(TemplateCommand command, Map<String, String> variables) {
		NormalizedCommand normalized = normalize(command);
		if (!normalized.prepared().validation().valid()) {
			throw new TemplateValidationException(String.join("; ", normalized.prepared().validation().errors()));
		}
		return new PreviewView(engine.render(normalized.prepared(), variables), normalized.prepared().validation(),
				normalized.prepared().contentSizeBytes());
	}

	private NormalizedCommand normalize(TemplateCommand command) {
		if (command == null) throw new TemplateValidationException("Template command is required");
		String name = normalizedName(command.name());
		String description = command.description() == null ? null
				: Normalizer.normalize(command.description(), Normalizer.Form.NFKC).strip();
		if (description != null && description.length() > 500) {
			throw new TemplateValidationException("Template description must not exceed 500 characters");
		}
		TemplateRepository.TemplateStatus status;
		try {
			status = TemplateRepository.TemplateStatus.valueOf(command.status());
		}
		catch (RuntimeException exception) {
			throw new TemplateValidationException("Template status is invalid");
		}
		var prepared = engine.prepare(command.content());
		if (!prepared.validation().valid()) {
			throw new TemplateValidationException(String.join("; ", prepared.validation().errors()));
		}
		return new NormalizedCommand(name, description, status, prepared);
	}

	private String normalizedName(String value) {
		String name = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
		if (name.isEmpty() || name.length() > 160 || name.codePoints().anyMatch(Character::isISOControl)) {
			throw new TemplateValidationException("Template name must contain 1 to 160 safe characters");
		}
		return name;
	}

	private Mono<Void> audit(
			String action, TemplateRepository.TemplateRecord template, UUID actorId,
			AuthenticationRequestContext context
	) {
		return auditService.record(new AuditEvent(
				actorId, action, "EMAIL_TEMPLATE", template.id().toString(), hasher.hash(context.ipAddress()),
				context.userAgentSummary(), context.traceId(), Map.of(), Map.of(
						"name", template.name(), "status", template.status().name(),
						"version", template.currentVersion(), "contentSizeBytes", template.contentSizeBytes()),
				AuditResult.SUCCESS, null));
	}

	private TemplateView view(TemplateRepository.TemplateRecord value) {
		return new TemplateView(
				value.id(), value.name(), value.description(), value.status(), value.currentVersion(), value.lockVersion(),
				value.subjectTemplate(), value.fromNameTemplate(), value.replyTo(), value.htmlContent(), value.textContent(),
				value.autoGenerateText(), value.contentSizeBytes(), value.validation(), value.createdAt(), value.updatedAt(),
				value.versionCreatedAt());
	}

	private TemplateVersionView versionView(TemplateRepository.TemplateVersionRecord value) {
		return new TemplateVersionView(
				value.id(), value.versionNumber(), value.subjectTemplate(), value.fromNameTemplate(), value.replyTo(),
				value.htmlContent(), value.textContent(), value.autoGenerateText(), value.contentSizeBytes(), value.validation(),
				value.createdBy(), value.createdAt());
	}

	private record NormalizedCommand(
			String name, String description, TemplateRepository.TemplateStatus status,
			TemplateModels.PreparedTemplate prepared
	) { }

	public record TemplateCommand(
			String name, String description, String status, TemplateModels.TemplateDraft content
	) { }

	public record TemplateView(
			UUID id, String name, String description, TemplateRepository.TemplateStatus status,
			long currentVersion, long lockVersion, String subjectTemplate, String fromNameTemplate,
			String replyTo, String htmlContent, String textContent, boolean autoGenerateText, int contentSizeBytes,
			TemplateModels.ValidationResult validation, Instant createdAt, Instant updatedAt,
			Instant versionCreatedAt
	) { }

	public record TemplateVersionView(
			UUID id, long versionNumber, String subjectTemplate, String fromNameTemplate, String replyTo,
			String htmlContent, String textContent, boolean autoGenerateText, int contentSizeBytes,
			TemplateModels.ValidationResult validation, UUID createdBy, Instant createdAt
	) { }

	public record PreviewView(
			TemplateModels.RenderedTemplate rendered,
			TemplateModels.ValidationResult validation,
			int contentSizeBytes
	) { }
}
