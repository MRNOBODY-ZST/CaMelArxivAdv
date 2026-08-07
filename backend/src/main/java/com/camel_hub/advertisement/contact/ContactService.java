package com.camel_hub.advertisement.contact;

import com.camel_hub.advertisement.audit.AuditEvent;
import com.camel_hub.advertisement.audit.AuditResult;
import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.contact.security.EmailDisclosurePolicy;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.security.Permission;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ContactService {

	private static final Set<String> CONFIDENCE = Set.of("HIGH", "MEDIUM", "LOW", "UNMAPPED");
	private static final Set<String> VERIFICATION = Set.of("UNVERIFIED", "CONFIRMED", "REJECTED");
	private static final Set<String> MASKED_PERMISSION = Set.of(Permission.CONTACT_READ_MASKED);

	private final ContactRepository repository;
	private final ContactCrypto crypto;
	private final EmailDisclosurePolicy disclosurePolicy;
	private final AuditService auditService;
	private final SensitiveValueHasher hasher;

	public ContactService(
			ContactRepository repository,
			ContactCrypto crypto,
			EmailDisclosurePolicy disclosurePolicy,
			AuditService auditService,
			SensitiveValueHasher hasher
	) {
		this.repository = repository;
		this.crypto = crypto;
		this.disclosurePolicy = disclosurePolicy;
		this.auditService = auditService;
		this.hasher = hasher;
	}

	public Mono<PageResponse<ContactSummary>> list(
			int page,
			int pageSize,
			ContactFilter input,
			AuthenticatedUser user
	) {
		if (page < 1 || page > 100_000 || pageSize < 1 || pageSize > 100) {
			return Mono.error(new IllegalArgumentException("Contact page is invalid"));
		}
		require(user, Permission.CONTACT_READ_MASKED);
		ContactFilter filter = normalize(input);
		int offset = Math.multiplyExact(page - 1, pageSize);
		return Mono.zip(
				repository.list(filter, offset, pageSize).map(this::summary).collectList(),
				repository.count(filter))
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<ContactDetail> get(
			UUID id,
			boolean full,
			AuthenticatedUser user,
			AuthenticationRequestContext context
	) {
		require(user, Permission.CONTACT_READ_MASKED);
		if (full) {
			require(user, Permission.CONTACT_READ_FULL);
		}
		return repository.find(id).switchIfEmpty(Mono.error(new ContactNotFoundException()))
				.flatMap(row -> repository.evidence(row.mappingId()).collectList()
						.map(evidence -> detail(row, evidence, full ? user.permissions() : MASKED_PERMISSION)))
				.flatMap(detail -> full
						? audit(user, id, "CONTACT_EMAIL_DISCLOSED", Map.of("disclosure", "FULL"), context)
								.thenReturn(detail)
						: Mono.just(detail));
	}

	public Mono<ContactDetail> verify(
			UUID contactId,
			VerificationCommand command,
			AuthenticatedUser user,
			AuthenticationRequestContext context
	) {
		require(user, Permission.CONTACT_VERIFY);
		if (command == null || command.mappingId() == null || command.expectedVersion() < 0
				|| command.status() == null
				|| !Set.of("CONFIRMED", "REJECTED").contains(command.status())) {
			return Mono.error(new IllegalArgumentException("Contact verification command is invalid"));
		}
		return repository.find(contactId).switchIfEmpty(Mono.error(new ContactNotFoundException()))
				.flatMap(before -> {
					if (!command.mappingId().equals(before.mappingId())) {
						return Mono.error(new ContactNotFoundException());
					}
					return repository.updateVerification(
							contactId, command.mappingId(), command.expectedVersion(),
							command.status(), user.id())
							.flatMap(updated -> updated ? Mono.just(before)
									: Mono.error(new ContactConflictException(
											"Contact verification changed concurrently")));
				})
				.flatMap(before -> audit(user, contactId, "CONTACT_VERIFICATION_UPDATED",
						Map.of("before", before.verificationStatus(), "after", command.status()), context))
				.then(get(contactId, false, user, context));
	}

	private ContactSummary summary(ContactRepository.ContactRow row) {
		return new ContactSummary(
				row.id(), disclose(row, MASKED_PERMISSION), row.domain(), row.exampleAddress(),
				row.suppressionStatus(), row.mappingId(), row.version(), row.confidence(),
				row.corresponding(), row.verificationStatus(), row.humanVerified(), row.paperId(),
				row.arxivId(), row.paperTitle(), row.authorName(), row.categoryId(), row.ruleName(),
				row.lastExtractedAt());
	}

	private ContactDetail detail(
			ContactRepository.ContactRow row,
			List<ContactRepository.EvidenceRow> evidence,
			Set<String> permissions
	) {
		ContactSummary base = new ContactSummary(
				row.id(), disclose(row, permissions), row.domain(), row.exampleAddress(),
				row.suppressionStatus(), row.mappingId(), row.version(), row.confidence(),
				row.corresponding(), row.verificationStatus(), row.humanVerified(), row.paperId(),
				row.arxivId(), row.paperTitle(), row.authorName(), row.categoryId(), row.ruleName(),
				row.lastExtractedAt());
		return new ContactDetail(
				base.id(), base.email(), base.domain(), base.exampleAddress(), base.suppressionStatus(),
				base.mappingId(), base.version(), base.confidence(), base.corresponding(),
				base.verificationStatus(), base.humanVerified(), base.paperId(), base.arxivId(),
				base.paperTitle(), base.authorName(), base.categoryId(), base.ruleName(),
				base.lastExtractedAt(), evidence.stream().map(item -> new EvidenceView(
						item.sourceRelativePath(), item.ruleName(), item.lineNumber(),
						item.logicalLocation(), item.maskedContext())).toList());
	}

	private String disclose(ContactRepository.ContactRow row, Set<String> permissions) {
		if (!permissions.contains(Permission.CONTACT_READ_FULL)) {
			try {
				return decryptAndDisclose(row, permissions);
			}
			catch (IllegalArgumentException | IllegalStateException exception) {
				return conservativeMask(row.domain());
			}
		}
		return decryptAndDisclose(row, permissions);
	}

	private String decryptAndDisclose(ContactRepository.ContactRow row, Set<String> permissions) {
		if (row.displayNonce() == null || row.displayCiphertext() == null) {
			throw new IllegalStateException("Contact display value is not decryptable");
		}
		String value = crypto.decrypt(new ContactCrypto.EncryptedValue(
				row.displayCiphertext(), row.displayNonce()));
		return disclosurePolicy.disclose(value, permissions);
	}

	private String conservativeMask(String domain) {
		if (domain == null || domain.isBlank() || domain.length() > 255
				|| !domain.matches("[A-Za-z0-9.-]+")) {
			return "***";
		}
		return "***@" + domain.toLowerCase(Locale.ROOT);
	}

	private ContactFilter normalize(ContactFilter input) {
		if (input == null) {
			return new ContactFilter(null, null, null, null, null);
		}
		String domain = text(input.domain(), 255, "domain");
		if (domain != null) {
			domain = domain.toLowerCase(Locale.ROOT);
			if (!domain.matches("[A-Za-z0-9.-]+")) {
				throw new IllegalArgumentException("Contact domain filter is invalid");
			}
		}
		String confidence = enumValue(input.confidence(), CONFIDENCE, "confidence");
		String verification = enumValue(
				input.verificationStatus(), VERIFICATION, "verification status");
		return new ContactFilter(domain, confidence, verification, input.corresponding(), input.paperId());
	}

	private String enumValue(String value, Set<String> allowed, String name) {
		String normalized = text(value, 30, name);
		if (normalized == null) {
			return null;
		}
		normalized = normalized.toUpperCase(Locale.ROOT);
		if (!allowed.contains(normalized)) {
			throw new IllegalArgumentException("Contact " + name + " filter is invalid");
		}
		return normalized;
	}

	private String text(String value, int maximum, String name) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.strip();
		if (normalized.length() > maximum
				|| normalized.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Contact " + name + " filter is invalid");
		}
		return normalized;
	}

	private Mono<Void> audit(
			AuthenticatedUser user,
			UUID contactId,
			String action,
			Map<String, Object> change,
			AuthenticationRequestContext context
	) {
		return auditService.record(new AuditEvent(
				user.id(), action, "CONTACT", contactId.toString(), hasher.hash(context.ipAddress()),
				context.userAgentSummary(), context.traceId(), Map.of(), change,
				AuditResult.SUCCESS, null));
	}

	private void require(AuthenticatedUser user, String permission) {
		if (user == null || !user.permissions().contains(permission)) {
			throw new AccessDeniedException("Contact permission is required");
		}
	}

	public record ContactFilter(
			String domain,
			String confidence,
			String verificationStatus,
			Boolean corresponding,
			UUID paperId
	) { }

	public record ContactSummary(
			UUID id, String email, String domain, boolean exampleAddress, String suppressionStatus,
			UUID mappingId, long version, String confidence, boolean corresponding,
			String verificationStatus, boolean humanVerified, UUID paperId, String arxivId,
			String paperTitle, String authorName, String categoryId, String ruleName,
			Instant lastExtractedAt
	) { }

	public record ContactDetail(
			UUID id, String email, String domain, boolean exampleAddress, String suppressionStatus,
			UUID mappingId, long version, String confidence, boolean corresponding,
			String verificationStatus, boolean humanVerified, UUID paperId, String arxivId,
			String paperTitle, String authorName, String categoryId, String ruleName,
			Instant lastExtractedAt, List<EvidenceView> evidence
	) { }

	public record EvidenceView(
			String sourceRelativePath, String ruleName, Integer lineNumber,
			String logicalLocation, String maskedContext
	) { }

	public record VerificationCommand(UUID mappingId, long expectedVersion, String status) { }
}
