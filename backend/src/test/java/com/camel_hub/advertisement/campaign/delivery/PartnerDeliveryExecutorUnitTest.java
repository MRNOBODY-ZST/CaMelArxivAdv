package com.camel_hub.advertisement.campaign.delivery;

import com.camel_hub.advertisement.campaign.partner.*;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import com.camel_hub.advertisement.email.smtp.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PartnerDeliveryExecutorUnitTest {
	private final Instant now=Instant.parse("2026-09-30T00:00:00Z");
	private final Clock clock=mock(Clock.class);
	private final CampaignDeliveryRepository repository=mock(CampaignDeliveryRepository.class);
	private final PartnerInviteService invites=mock(PartnerInviteService.class);
	private final ContactCrypto crypto=mock(ContactCrypto.class);
	private final AtomicInteger sends=new AtomicInteger();
	private CampaignDeliveryRepository.ProductionClaim claim;
	private CampaignDeliveryExecutor executor;
	@BeforeEach void setup() {
		when(clock.instant()).thenReturn(now);
		String html="<a href=\"{{partner_claim_url}}\">Activate</a>", text="Activate {{partner_claim_url}}";
		claim=new CampaignDeliveryRepository.ProductionClaim(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,"key","<id@example.org>","correlation",new byte[32],new byte[32],new byte[12],new byte[32],"example.org",null,UUID.randomUUID(),"Team","sender@example.org","reply@example.org",false,false,true,"Invite",html,text);
		when(repository.claimNext(now)).thenReturn(Mono.just(claim));
		when(repository.productionEligible(claim,clock)).thenReturn(Mono.just(true));
		when(repository.beginSmtp(claim,clock)).thenReturn(Mono.just(true));
		when(repository.completeAccepted(any(),any(),any(),any(),eq(now))).thenReturn(Mono.just(true));
		when(repository.holdPartnerInvite(eq(claim),anyString(),eq(now),any())).thenReturn(Mono.just(true));
		when(crypto.decrypt(any())).thenReturn("author@example.org");
		when(invites.prepareProduction(any(),any())).thenReturn(Mono.just(new PartnerInviteService.Invite("https://api.camel-hub.cn/partner/claim?token=reusable",now.plusSeconds(60))));
		executor=new CampaignDeliveryExecutor(repository, ignored -> Mono.just(new CampaignOutboundPreparer.PreparedOutbound("Invite",html,text,Map.of("List-Unsubscribe","<https://example.org/u/safe>","List-Unsubscribe-Post","List-Unsubscribe=One-Click"))),null,null,crypto,(account,message)-> {
			sends.incrementAndGet();
			assertThat(message.html()).contains("token=reusable").doesNotContain("partner_claim_url","/t/c/");
			return new SmtpTransport.SmtpOutcome(CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,CampaignDeliveryModels.TransportStage.POST_DATA,250,"queued");
		},clock,new PartnerInviteOutboundFinalizer(invites,crypto,clock));
	}
	@Test void productionBindsOnlyBeforeSmtpAndNeverStoresFinalBodies() {
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		assertThat(sends.get()).isEqualTo(1);
		var order=inOrder(repository,invites);
		order.verify(repository,times(2)).productionEligible(claim,clock);
		order.verify(invites).prepareProduction(any(),eq("author@example.org"));
		order.verify(repository).beginSmtp(claim,clock);
	}
	@Test void invitationFailureIsAnUnsentHoldNotSmtpFailure() {
		when(invites.prepareProduction(any(),any())).thenReturn(Mono.error(new PartnerInviteException(PartnerInviteException.Reason.HELD)));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PARTNER_INVITE_HELD);
		assertThat(sends.get()).isZero();
		verify(repository).holdPartnerInvite(eq(claim),eq("HELD"),eq(now),argThat(d -> d.stage()==PreparationDiagnostic.PreparationStage.PARTNER_FINALIZE && d.exceptionType()==PreparationDiagnostic.SafeExceptionType.PARTNER));
		verify(repository,never()).completeFailureDetailed(any(),any(),any(),any(),any());
		verify(repository,never()).beginSmtp(any(),any());
	}
	@Test void cancellationBeforePartnerIoPreventsInviteAndSend() {
		when(repository.productionEligible(claim,clock)).thenReturn(Mono.just(false));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.NO_WORK);
		verifyNoInteractions(invites);
		assertThat(sends.get()).isZero();
	}
	@Test void nonPartnerFinalizeErrorRetainsOriginalTypeWhileHoldingUnavailable() {
		when(invites.prepareProduction(any(),any())).thenReturn(Mono.error(new org.springframework.dao.DataAccessResourceFailureException("synthetic-private")));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PARTNER_INVITE_HELD);
		verify(repository).holdPartnerInvite(eq(claim),eq("UNAVAILABLE"),eq(now),argThat(d -> d.stage()==PreparationDiagnostic.PreparationStage.PARTNER_FINALIZE && d.exceptionType()==PreparationDiagnostic.SafeExceptionType.PERSISTENCE));
		assertThat(sends).hasValue(0);
	}
	@Test void unexpectedFinalizerValidationFailureKeepsUnavailableSemantic() {
		Throwable error=catchThrowable(() -> new PartnerInviteOutboundFinalizer(invites,crypto,clock).finalizeOutbound(claim,null).block());
		assertThat(PreparationDiagnostic.original(error)).isInstanceOf(PartnerInviteException.class);
		assertThat(((PartnerInviteException)PreparationDiagnostic.original(error)).reason()).isEqualTo(PartnerInviteException.Reason.UNAVAILABLE);
		assertThat(PreparationDiagnostic.diagnostic(error).exceptionType()).isEqualTo(PreparationDiagnostic.SafeExceptionType.OTHER);
	}
	@Test void innerPreparationDiagnosticSurvivesInvalidInputPartnerRemap() {
		var diagnostic=new java.util.concurrent.atomic.AtomicReference<PreparationDiagnostic>();
		CampaignOutboundPreparer failing=ignored -> PreparationDiagnostic.<CampaignOutboundPreparer.PreparedOutbound>at(PreparationDiagnostic.PreparationStage.ARTIFACT_PERSIST,
			() -> Mono.error(new org.springframework.dao.DataAccessResourceFailureException("synthetic-private")))
			.doOnError(error -> diagnostic.set(PreparationDiagnostic.diagnostic(error)));
		var executor=new CampaignDeliveryExecutor(repository,failing,crypto,(account,message)->{throw new AssertionError("SMTP");},clock);
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PARTNER_INVITE_HELD);
		verify(repository).holdPartnerInvite(claim,"INVALID_INPUT",now,diagnostic.get());
		verifyNoInteractions(invites);
	}
	@Test void lostLeaseAfterInvitePreventsSend() {
		when(repository.beginSmtp(claim,clock)).thenReturn(Mono.just(false));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.NO_WORK);
		assertThat(sends.get()).isZero();
	}
	@Test void inviteExpiringDuringTransportQueuePreparationIsHeldWithoutSmtp() {
		AtomicInteger decrypts=new AtomicInteger();
		when(crypto.decrypt(any())).thenAnswer(call -> {
			if (decrypts.incrementAndGet()==2) when(clock.instant()).thenReturn(now.plusSeconds(61));
			return "author@example.org";
		});
		when(repository.beginSmtp(claim,clock)).thenReturn(Mono.just(true));
		when(repository.holdPartnerInvite(eq(claim),eq("EXPIRED"),any(),any())).thenReturn(Mono.just(true));
		when(repository.completeAccepted(any(),any(),any(),any(),any())).thenReturn(Mono.just(true));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PARTNER_INVITE_HELD);
		assertThat(sends.get()).isZero();
		verify(repository).holdPartnerInvite(eq(claim),eq("EXPIRED"),eq(now.plusSeconds(61)),argThat(d -> d.stage()==PreparationDiagnostic.PreparationStage.SMTP_FENCE));
	}
	@Test void inviteExpiringWhileFenceCommitsIsCompensatedOnlyBeforeSender() {
		when(repository.beginSmtp(claim,clock)).thenAnswer(call -> {
			when(clock.instant()).thenReturn(now.plusSeconds(61));
			return Mono.just(true);
		});
		when(repository.holdExpiredBeforeSmtp(eq(claim),eq(now.plusSeconds(61)),any())).thenReturn(Mono.just(true));
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.PARTNER_INVITE_HELD);
		assertThat(sends.get()).isZero();
		verify(repository).holdExpiredBeforeSmtp(eq(claim),eq(now.plusSeconds(61)),argThat(d -> d.stage()==PreparationDiagnostic.PreparationStage.SMTP_FENCE));
		verify(repository,never()).holdPartnerInvite(any(),any(),any());
		verify(repository,never()).completeFailureDetailed(any(),any(),any(),any(),any());
	}
	@Test void smtpFenceRunsOnTransportWorkerAfterLocalPreparationNotBeforeQueueing() {
		AtomicInteger decrypts=new AtomicInteger(), decryptsAtFence=new AtomicInteger();
		when(crypto.decrypt(any())).thenAnswer(call -> { decrypts.incrementAndGet(); return "author@example.org"; });
		when(repository.beginSmtp(claim,clock)).thenAnswer(call -> { decryptsAtFence.set(decrypts.get()); return Mono.just(true); });
		assertThat(executor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		assertThat(decryptsAtFence.get()).isEqualTo(2);
	}
	@Test void smtpTemporaryRetryReusesOneEncryptedCapabilityWithoutReissuing() {
		AtomicInteger posts=new AtomicInteger(), gets=new AtomicInteger(), transports=new AtomicInteger();
		var row=new java.util.concurrent.atomic.AtomicReference<PartnerInviteRepository.Row>();
		PartnerInviteRepository durable=new PartnerInviteRepository() {
			public Mono<Boolean> reserve(byte[] hmac,String hash,Instant time) { return Mono.fromSupplier(() -> row.compareAndSet(null,new Row(hash,State.REQUESTING,null,null))); }
			public Mono<Row> find(byte[] hmac) { return Mono.defer(() -> Mono.justOrEmpty(row.get())); }
			public Mono<Boolean> ready(byte[] hmac,String hash,byte[] encrypted,Instant expires,Instant time) { row.set(new Row(hash,State.READY,encrypted,expires)); return Mono.just(true); }
			public Mono<Void> transition(byte[] hmac,String hash,State state,Instant time) { return Mono.error(new AssertionError("No hold expected")); }
		};
		PartnerInviteClient client=new PartnerInviteClient() {
			public Mono<PendingInvite> create(String hash,String email) { posts.incrementAndGet(); return Mono.just(new PendingInvite("https://api.camel-hub.cn/partner/claim?token=reused_private",now.plusSeconds(600))); }
			public Mono<IdentityStatus> identity(String hash) { gets.incrementAndGet(); assertThat(hash).isEqualTo(row.get().hash()); return Mono.just(IdentityStatus.PENDING); }
		};
		var properties=new PartnerInviteProperties(true,"test-bearer",Base64.getEncoder().encodeToString(new byte[32]),null);
		var service=new PartnerInviteService(durable,client,new PartnerInviteCrypto(properties.encryptionKeyBase64()),properties,clock);
		when(repository.completeFailureDetailed(any(),any(),any(),any(),any())).thenReturn(Mono.just(new CampaignDeliveryRepository.FailureSettlement(true,CampaignDeliveryModels.AttemptStatus.TEMPORARY_FAILURE)));
		List<String> sent=new ArrayList<>();
		var retryExecutor=new CampaignDeliveryExecutor(repository, ignored -> Mono.just(new CampaignOutboundPreparer.PreparedOutbound("Invite",claim.renderedHtml(),claim.renderedText(),Map.of("List-Unsubscribe","<https://example.org/u/safe>","List-Unsubscribe-Post","List-Unsubscribe=One-Click"))),null,null,crypto,(account,message) -> {
			sent.add(message.html());
			if (transports.incrementAndGet()==1) throw new SmtpTransportException(SmtpTransportException.FailureCategory.SMTP_REJECTED,CampaignDeliveryModels.AttemptStatus.TEMPORARY_FAILURE,CampaignDeliveryModels.TransportStage.RCPT_TO,450,"temporary",true);
			return new SmtpTransport.SmtpOutcome(CampaignDeliveryModels.AttemptStatus.SMTP_ACCEPTED,CampaignDeliveryModels.TransportStage.POST_DATA,250,"queued");
		},clock,new PartnerInviteOutboundFinalizer(service,crypto,clock));
		assertThat(retryExecutor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.TEMPORARY_FAILURE);
		assertThat(retryExecutor.pumpOnce().block()).isEqualTo(CampaignDeliveryExecutor.PumpResult.SMTP_ACCEPTED);
		assertThat(posts.get()).isEqualTo(1);
		assertThat(gets.get()).isEqualTo(1);
		assertThat(sent).hasSize(2);
		assertThat(sent.get(0)).isEqualTo(sent.get(1)).contains("token=reused_private");
		assertThat(new String(row.get().encryptedUrl(),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("reused_private");
	}
}
