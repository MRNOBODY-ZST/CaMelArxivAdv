package com.camel_hub.advertisement.contact;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.common.api.PageResponse;
import com.camel_hub.advertisement.contact.config.ContactDataProtectionProperties;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.contact.security.EmailDisclosurePolicy;
import com.camel_hub.advertisement.identity.domain.AuthenticatedUser;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContactServiceTest {

	private final UUID contactId = UUID.randomUUID();
	private final UUID mappingId = UUID.randomUUID();
	private ContactRepository repository;
	private AuditService audit;
	private ContactCrypto crypto;
	private ContactService service;
	private ContactRepository.ContactRow row;
	private TransactionalOperator transactions;

	@BeforeEach
	void setUp() {
		repository = mock(ContactRepository.class);
		audit = mock(AuditService.class);
		when(audit.record(any())).thenReturn(Mono.empty());
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(hasher.hash(any())).thenReturn(new byte[] {1});
		crypto = crypto();
		transactions = mock(TransactionalOperator.class);
		when(transactions.transactional(any(Mono.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		ContactCrypto.EncryptedValue display = crypto.encrypt("alice@university.edu");
		row = new ContactRepository.ContactRow(
				contactId, display.ciphertext(), display.nonce(), "university.edu", false,
				"ACTIVE", Instant.parse("2026-08-06T01:00:00Z"),
				mappingId, 0, "HIGH", true, "UNVERIFIED", false,
				UUID.randomUUID(), "2608.00001", "Source Paper", "Alice Example", "cs.AI",
				"DIRECT_AUTHOR_EMAIL");
		service = new ContactService(
				repository, crypto, new EmailDisclosurePolicy(), audit, hasher, transactions);
	}

	@Test
	void listsOnlyMaskedAddressesEvenForFullReaders() {
		when(repository.list(any(), any(Integer.class), any(Integer.class))).thenReturn(Flux.just(row));
		when(repository.count(any())).thenReturn(Mono.just(1L));

		PageResponse<ContactService.ContactSummary> page = service.list(
				1, 20, new ContactService.ContactFilter(null, null, null, null, null), fullUser()).block();

		assertThat(page.items()).singleElement().satisfies(item ->
				assertThat(item.email()).isEqualTo("al***@university.edu"));
	}

	@Test
	void explicitFullDisclosureRequiresPermissionAndIsAudited() {
		when(repository.find(contactId)).thenReturn(Mono.just(row));
		when(repository.evidence(mappingId)).thenReturn(Flux.just(
				new ContactRepository.EvidenceRow(
						"paper/main.tex", "DIRECT_AUTHOR_EMAIL", 4,
						"AUTHOR_FRONT_MATTER", "Email: al***@university.edu")));

		assertThatThrownBy(() -> service.get(contactId, true, maskedUser(), context()).block())
				.isInstanceOf(AccessDeniedException.class);
		ContactService.ContactDetail detail = service.get(contactId, true, fullUser(), context()).block();

		assertThat(detail.email()).isEqualTo("alice@university.edu");
		assertThat(detail.evidence()).singleElement().satisfies(item ->
				assertThat(item.maskedContext()).doesNotContain("alice@university.edu"));
		verify(audit).record(any());
	}

	@Test
	void verificationRequiresExpectedVersionAndReturnsUpdatedMaskedView() {
		when(repository.find(contactId)).thenReturn(Mono.just(row), Mono.just(new ContactRepository.ContactRow(
				row.id(), row.displayCiphertext(), row.displayNonce(), row.domain(), row.exampleAddress(),
				row.suppressionStatus(), row.lastExtractedAt(), row.mappingId(), 1, row.confidence(),
				row.corresponding(), "CONFIRMED", true, row.paperId(), row.arxivId(), row.paperTitle(),
				row.authorName(), row.categoryId(), row.ruleName())));
		when(repository.evidence(mappingId)).thenReturn(Flux.empty());
		when(repository.updateVerification(
				contactId, mappingId, 0, "CONFIRMED", fullUser().id())).thenReturn(Mono.just(true));

		ContactService.ContactDetail detail = service.verify(
				contactId, new ContactService.VerificationCommand(mappingId, 0, "CONFIRMED"),
				fullUser(), context()).block();

		assertThat(detail.verificationStatus()).isEqualTo("CONFIRMED");
		assertThat(detail.email()).isEqualTo("al***@university.edu");
	}

	@Test
	void batchVerificationUpdatesEveryItemInsideOneTransaction() {
		UUID secondContactId = UUID.randomUUID();
		UUID secondMappingId = UUID.randomUUID();
		ContactRepository.ContactRow second = contactRow(
				secondContactId, secondMappingId, 0, "UNVERIFIED", false);
		ContactRepository.ContactRow firstUpdated = contactRow(
				contactId, mappingId, 1, "CONFIRMED", true);
		ContactRepository.ContactRow secondUpdated = contactRow(
				secondContactId, secondMappingId, 1, "CONFIRMED", true);
		when(repository.find(contactId)).thenReturn(Mono.just(row), Mono.just(firstUpdated));
		when(repository.find(secondContactId)).thenReturn(Mono.just(second), Mono.just(secondUpdated));
		when(repository.evidence(mappingId)).thenReturn(Flux.empty());
		when(repository.evidence(secondMappingId)).thenReturn(Flux.empty());
		when(repository.updateVerification(contactId, mappingId, 0, "CONFIRMED", fullUser().id()))
				.thenReturn(Mono.just(true));
		when(repository.updateVerification(secondContactId, secondMappingId, 0, "CONFIRMED", fullUser().id()))
				.thenReturn(Mono.just(true));

		ContactService.BatchVerificationResult result = service.batchVerify(List.of(
				new ContactService.BatchVerificationItem(contactId, mappingId, 0),
				new ContactService.BatchVerificationItem(secondContactId, secondMappingId, 0)),
				"CONFIRMED", fullUser(), context()).block();

		assertThat(result).isEqualTo(new ContactService.BatchVerificationResult(2, "CONFIRMED"));
		verify(repository).updateVerification(contactId, mappingId, 0, "CONFIRMED", fullUser().id());
		verify(repository).updateVerification(
				secondContactId, secondMappingId, 0, "CONFIRMED", fullUser().id());
		verify(transactions).transactional(any(Mono.class));
	}

	@Test
	void batchVerificationRejectsDuplicateContactsBeforeDatabaseAccess() {
		assertThatThrownBy(() -> service.batchVerify(List.of(
				new ContactService.BatchVerificationItem(contactId, mappingId, 0),
				new ContactService.BatchVerificationItem(contactId, UUID.randomUUID(), 0)),
				"REJECTED", fullUser(), context()).block())
				.isInstanceOf(ContactValidationException.class)
				.hasMessageContaining("unique");

		verifyNoInteractions(repository);
	}

	@Test
	void batchVerificationRejectsMoreThanOneHundredItems() {
		List<ContactService.BatchVerificationItem> items = IntStream.rangeClosed(1, 101)
				.mapToObj(index -> new ContactService.BatchVerificationItem(
						UUID.randomUUID(), UUID.randomUUID(), index))
				.toList();

		assertThatThrownBy(() -> service.batchVerify(
				items, "CONFIRMED", fullUser(), context()).block())
				.isInstanceOf(ContactValidationException.class)
				.hasMessageContaining("between 1 and 100");

		verifyNoInteractions(repository);
	}

	@Test
	void unreadableLegacyCiphertextStillSupportsMaskedViewsButNotFullDisclosure() {
		ContactCrypto.EncryptedValue legacy = legacyCrypto().encrypt("alice@university.edu");
		row = new ContactRepository.ContactRow(
				row.id(), legacy.ciphertext(), legacy.nonce(), row.domain(), row.exampleAddress(),
				row.suppressionStatus(), row.lastExtractedAt(), row.mappingId(), row.version(),
				row.confidence(), row.corresponding(), row.verificationStatus(), row.humanVerified(),
				row.paperId(), row.arxivId(), row.paperTitle(), row.authorName(), row.categoryId(),
				row.ruleName());
		when(repository.list(any(), any(Integer.class), any(Integer.class))).thenReturn(Flux.just(row));
		when(repository.count(any())).thenReturn(Mono.just(1L));
		when(repository.find(contactId)).thenReturn(Mono.just(row));
		when(repository.evidence(mappingId)).thenReturn(Flux.empty());

		PageResponse<ContactService.ContactSummary> page = service.list(
				1, 20, new ContactService.ContactFilter(null, null, null, null, null), fullUser()).block();
		ContactService.ContactDetail masked = service.get(
				contactId, false, maskedUser(), context()).block();

		assertThat(page.items()).singleElement().extracting(ContactService.ContactSummary::email)
				.isEqualTo("***@university.edu");
		assertThat(masked.email()).isEqualTo("***@university.edu");
		assertThatThrownBy(() -> service.get(contactId, true, fullUser(), context()).block())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("authentication failed");
		verify(audit, never()).record(any());
	}

	private AuthenticatedUser maskedUser() {
		return new AuthenticatedUser(
				UUID.randomUUID(), "analyst", "Analyst", Set.of("DATA_ANALYST"),
				Set.of("contact:read_masked"), false, 0);
	}

	private AuthenticatedUser fullUser() {
		return new AuthenticatedUser(
				UUID.fromString("1e03bc8a-0065-4fa9-9d79-4f33b17ff879"), "admin", "Admin",
				Set.of("ADMIN"), Set.of("contact:read_masked", "contact:read_full", "contact:verify"),
				false, 0);
	}

	private AuthenticationRequestContext context() {
		return new AuthenticationRequestContext("192.0.2.55", "contact-test", "contact-trace-1234");
	}

	private ContactCrypto crypto() {
		return new ContactCrypto(new ContactDataProtectionProperties(
				key("0123456789abcdef0123456789abcdef"),
				key("abcdef0123456789abcdef0123456789")));
	}

	private ContactCrypto legacyCrypto() {
		return new ContactCrypto(new ContactDataProtectionProperties(
				key("fedcba9876543210fedcba9876543210"),
				key("9876543210fedcba9876543210fedcba")));
	}

	private String key(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private ContactRepository.ContactRow contactRow(
			UUID id,
			UUID selectedMappingId,
			long version,
			String verificationStatus,
			boolean humanVerified
	) {
		return new ContactRepository.ContactRow(
				id, row.displayCiphertext(), row.displayNonce(), row.domain(), row.exampleAddress(),
				row.suppressionStatus(), row.lastExtractedAt(), selectedMappingId, version,
				row.confidence(), row.corresponding(), verificationStatus, humanVerified,
				row.paperId(), row.arxivId(), row.paperTitle(), row.authorName(), row.categoryId(),
				row.ruleName());
	}
}
