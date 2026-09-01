package com.camel_hub.advertisement.arxiv.extraction;

import com.camel_hub.advertisement.audit.AuditService;
import com.camel_hub.advertisement.identity.security.SensitiveValueHasher;
import com.camel_hub.advertisement.identity.service.AuthenticationRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceExtractionServiceCommandSizeTest {

	private static final UUID ACTOR = UUID.fromString("c98ac60e-e560-4c1d-846d-40fc75912a3b");
	private static final int SAFE_ENVELOPE_BYTES = 768 * 1024;
	private SourceExtractionRepository repository;
	private SourceExtractionService service;
	private ObjectMapper objectMapper;

	@SuppressWarnings("unchecked")
	@BeforeEach
	void setUp() {
		repository = mock(SourceExtractionRepository.class);
		AuditService audit = mock(AuditService.class);
		when(audit.record(any())).thenReturn(Mono.empty());
		SensitiveValueHasher hasher = mock(SensitiveValueHasher.class);
		when(hasher.hash(any())).thenReturn(new byte[] {1, 2, 3});
		TransactionalOperator transactions = mock(TransactionalOperator.class);
		when(transactions.transactional(any(Mono.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		objectMapper = new ObjectMapper().findAndRegisterModules();
		service = new SourceExtractionService(
				repository, audit, hasher, objectMapper, "0.1.0", transactions);
	}

	@Test
	void boundsOneHundredMaximumAuthorListsBelowTheKafkaRecordLimit() throws Exception {
		List<String> maximumAuthors = IntStream.range(0, 500)
				.mapToObj(index -> "A".repeat(297) + String.format("%03d", index))
				.toList();
		List<SourceExtractionRepository.PaperTarget> targets = IntStream.range(0, 100)
				.mapToObj(index -> new SourceExtractionRepository.PaperTarget(
						new UUID(0, index + 1L), "2608." + String.format("%05d", index),
						maximumAuthors))
				.toList();

		SourceExtractionRepository.Command command = submit(targets);

		byte[] envelope = command.envelopeJson().getBytes(StandardCharsets.UTF_8);
		JsonNode payloadTargets = objectMapper.readTree(envelope).path("payload").path("targets");
		List<Integer> authorCounts = StreamSupport.stream(payloadTargets.spliterator(), false)
				.map(target -> target.path("metadataAuthors").size())
				.toList();
		assertThat(envelope.length).isLessThanOrEqualTo(SAFE_ENVELOPE_BYTES);
		assertThat(authorCounts).contains(0, 500)
				.allMatch(size -> size == 0 || size == 500);
	}

	@Test
	void preservesCompleteSmallAuthorLists() throws Exception {
		List<SourceExtractionRepository.PaperTarget> targets = List.of(
				new SourceExtractionRepository.PaperTarget(
						new UUID(0, 1), "2608.00001", List.of("Alice Example", "Bob Example")),
				new SourceExtractionRepository.PaperTarget(
						new UUID(0, 2), "2608.00002", List.of("Carol Example")));

		SourceExtractionRepository.Command command = submit(targets);

		JsonNode payloadTargets = objectMapper.readTree(command.envelopeJson())
				.path("payload").path("targets");
		assertThat(authorNames(payloadTargets.get(0)))
				.containsExactly("Alice Example", "Bob Example");
		assertThat(authorNames(payloadTargets.get(1))).containsExactly("Carol Example");
	}

	private SourceExtractionRepository.Command submit(
			List<SourceExtractionRepository.PaperTarget> targets
	) {
		List<UUID> paperIds = targets.stream()
				.map(SourceExtractionRepository.PaperTarget::paperId)
				.toList();
		when(repository.lockPapers(paperIds)).thenReturn(Flux.fromIterable(targets));
		when(repository.hasActiveExtraction(paperIds)).thenReturn(Mono.just(false));
		when(repository.create(any())).thenAnswer(invocation -> {
			SourceExtractionRepository.Command command = invocation.getArgument(0);
			return Mono.just(new SourceExtractionService.JobSubmission(command.jobId(), "PENDING"));
		});

		service.create(ACTOR, paperIds, context()).block();

		ArgumentCaptor<SourceExtractionRepository.Command> command =
				ArgumentCaptor.forClass(SourceExtractionRepository.Command.class);
		verify(repository).create(command.capture());
		return command.getValue();
	}

	private List<String> authorNames(JsonNode target) {
		return StreamSupport.stream(target.path("metadataAuthors").spliterator(), false)
				.map(JsonNode::asText)
				.toList();
	}

	private AuthenticationRequestContext context() {
		return new AuthenticationRequestContext("192.0.2.40", "source-test", "source-trace-1234");
	}
}
