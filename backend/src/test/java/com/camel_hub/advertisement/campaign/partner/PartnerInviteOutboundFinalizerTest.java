package com.camel_hub.advertisement.campaign.partner;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository.ProductionClaim;
import com.camel_hub.advertisement.campaign.delivery.CampaignOutboundPreparer.PreparedOutbound;
import com.camel_hub.advertisement.campaign.delivery.PreparationDiagnostic;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PartnerInviteOutboundFinalizerTest {
	private static final Instant NOW = Instant.parse("2026-09-30T00:00:00Z");
	private static final String URL = "https://api.camel-hub.cn/partner/claim?token=private_token";
	private final PartnerInviteService invites = mock(PartnerInviteService.class);
	private final ContactCrypto crypto = mock(ContactCrypto.class);
	private final PartnerInviteOutboundFinalizer finalizer = new PartnerInviteOutboundFinalizer(invites, crypto, Clock.fixed(NOW, ZoneOffset.UTC));
	@Test void replacesExactBodyPlaceholdersTransientlyAndLeavesHeadersUntouched() {
		when(crypto.decrypt(any())).thenReturn("author@example.org");
		when(invites.prepareProduction(any(), eq("author@example.org"))).thenReturn(Mono.just(new PartnerInviteService.Invite(URL, NOW.plusSeconds(60))));
		var source = outbound("<a href=\"{{partner_claim_url}}\">Activate</a>", "Activate {{partner_claim_url}}");
		var result = finalizer.finalizeOutbound(claim(), source).block();
		assertThat(result.html()).isEqualTo("<a href=\"" + URL + "\">Activate</a>");
		assertThat(result.text()).isEqualTo("Activate " + URL);
		assertThat(result.headers()).isEqualTo(source.headers());
		assertThat(result.partnerInviteExpiresAt()).isEqualTo(NOW.plusSeconds(60));
		assertThat(source.partnerInviteExpiresAt()).isNull();
		assertThat(source.html()).contains("{{partner_claim_url}}");
		assertThat(result.toString()).doesNotContain("private_token");
		assertThat(new com.camel_hub.advertisement.email.smtp.SmtpTransport.OutboundMessage("author@example.org", "Invite", "Team", "reply@example.org",
				result.html(), result.text(), "correlation", "sender@example.org", "<id@example.org>", result.headers()).toString()).doesNotContain("private_token");
		verify(invites).prepareProduction(any(ProductionClaim.class), eq("author@example.org"));
	}
	@Test void legacyMailPassesUntouchedWithoutInvitesOrDecryption() {
		var source = outbound("<p>Hello</p>", "Hello");
		assertThat(finalizer.finalizeOutbound(claim(), source).block()).isSameAs(source);
		verifyNoInteractions(invites, crypto);
	}
	@Test void missingOptionalServiceAndMalformedPlaceholderFailClosed() {
		var unavailable = new PartnerInviteOutboundFinalizer(null, crypto, Clock.fixed(NOW, ZoneOffset.UTC));
		assertThatThrownBy(() -> unavailable.finalizeOutbound(claim(), outbound("<a href=\"{{partner_claim_url}}\">Activate</a>", "{{partner_claim_url}}")).block())
				.satisfies(error -> assertPartnerFailure(error, "PARTNER_INVITE_DISABLED"));
		assertThatThrownBy(() -> finalizer.finalizeOutbound(claim(), outbound("<a href=\"{{partner_claim_url}}\">Activate</a>", "missing placeholder")).block())
				.satisfies(error -> assertPartnerFailure(error, "PARTNER_INVITE_INVALID_INPUT"));
		verifyNoInteractions(invites, crypto);
	}
	@Test void disabledOrExpiredInvitesFailClosed() {
		when(crypto.decrypt(any())).thenReturn("author@example.org");
		when(invites.prepareProduction(any(), any())).thenReturn(Mono.error(new PartnerInviteException(PartnerInviteException.Reason.DISABLED)));
		assertThatThrownBy(() -> finalizer.finalizeOutbound(claim(), outbound("<a href=\"{{partner_claim_url}}\">Activate</a>", "{{partner_claim_url}}")).block())
				.satisfies(error -> assertPartnerFailure(error, "PARTNER_INVITE_DISABLED"));
		when(invites.prepareProduction(any(), any())).thenReturn(Mono.just(new PartnerInviteService.Invite(URL, NOW)));
		assertThatThrownBy(() -> finalizer.finalizeOutbound(claim(), outbound("<a href=\"{{partner_claim_url}}\">Activate</a>", "{{partner_claim_url}}")).block())
				.satisfies(error -> assertPartnerFailure(error, "PARTNER_INVITE_EXPIRED"));
	}
	private static void assertPartnerFailure(Throwable failure, String reason) {
		assertThat(failure).isInstanceOf(PreparationDiagnostic.PreparationFailure.class)
				.hasMessage("PREP_PARTNER_FINALIZE_FAILED").hasNoCause();
		assertThat(PreparationDiagnostic.diagnostic(failure).stage()).isEqualTo(PreparationDiagnostic.PreparationStage.PARTNER_FINALIZE);
		// Invalid content keeps its original validation diagnostic when settlement becomes INVALID_INPUT.
		assertThat(PreparationDiagnostic.diagnostic(failure).exceptionType()).isEqualTo(reason.equals("PARTNER_INVITE_INVALID_INPUT")
				? PreparationDiagnostic.SafeExceptionType.VALIDATION : PreparationDiagnostic.SafeExceptionType.PARTNER);
		assertThat(PreparationDiagnostic.original(failure)).isInstanceOf(PartnerInviteException.class).hasMessage(reason);
	}
	private PreparedOutbound outbound(String html, String text) { return new PreparedOutbound("Invite", html, text,
			Map.of("List-Unsubscribe", "<https://example.org/u/safe>", "List-Unsubscribe-Post", "List-Unsubscribe=One-Click")); }
	private ProductionClaim claim() { return new ProductionClaim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
			"key", "<id@example.org>", "correlation", new byte[32], new byte[32], new byte[12], new byte[32], "example.org", null, UUID.randomUUID(),
			"Team", "sender@example.org", "reply@example.org", false, false, true, "Invite", "html", "text"); }
}
