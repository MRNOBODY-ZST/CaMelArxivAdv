package com.camel_hub.advertisement.campaign.partner;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryRepository.ProductionClaim;
import com.camel_hub.advertisement.campaign.delivery.CampaignOutboundPreparer.PreparedOutbound;
import com.camel_hub.advertisement.contact.security.ContactCrypto;
import reactor.core.publisher.Mono;
import java.time.Clock;
import static com.camel_hub.advertisement.campaign.delivery.PreparationDiagnostic.*;
import static com.camel_hub.advertisement.campaign.delivery.PreparationDiagnostic.PreparationStage.PARTNER_FINALIZE;

/** The only production boundary that binds an invite into transient MIME bodies. */
public final class PartnerInviteOutboundFinalizer {
	private final PartnerInviteService invites;
	private final ContactCrypto crypto;
	private final Clock clock;
	public PartnerInviteOutboundFinalizer(PartnerInviteService invites, ContactCrypto crypto, Clock clock) {
		this.invites = invites; this.crypto = crypto; this.clock = clock;
	}
	public Mono<PreparedOutbound> finalizeOutbound(ProductionClaim claim, PreparedOutbound outbound) {
		return at(PARTNER_FINALIZE, () -> {
			try { during(PARTNER_FINALIZE, () -> { PartnerContentPolicy.validate(outbound.subject(), outbound.html(), outbound.text()); return true; }); }
			catch (PreparationFailure invalid) {
				if (!(original(invalid) instanceof IllegalArgumentException)) throw invalid;
				return Mono.error(remap(invalid, new PartnerInviteException(PartnerInviteException.Reason.INVALID_INPUT)));
			}
			if (!PartnerContentPolicy.optedIn(outbound.html(), outbound.text())) return Mono.just(outbound);
			if (invites == null) return Mono.error(new PartnerInviteException(PartnerInviteException.Reason.DISABLED));
			String email = crypto(PARTNER_FINALIZE, () -> crypto.decrypt(new ContactCrypto.EncryptedValue(claim.emailCiphertext(), claim.emailNonce())));
			return invites.prepareProduction(claim, email)
					.switchIfEmpty(Mono.error(new PartnerInviteException(PartnerInviteException.Reason.UNAVAILABLE)))
					.map(invite -> {
						if (invite.expiresAt() == null || !invite.expiresAt().isAfter(clock.instant())) throw new PartnerInviteException(PartnerInviteException.Reason.EXPIRED);
						if (!HttpPartnerInviteClient.validClaimUrl(invite.claimUrl())) throw new PartnerInviteException(PartnerInviteException.Reason.HELD);
						return new PreparedOutbound(outbound.subject(),
								outbound.html().replace(PartnerContentPolicy.PLACEHOLDER, invite.claimUrl().replace("&", "&amp;").replace("\"", "&quot;")),
								outbound.text().replace(PartnerContentPolicy.PLACEHOLDER, invite.claimUrl()), outbound.headers(), invite.expiresAt());
					});
		}).onErrorMap(error -> original(error) instanceof PartnerInviteException ? error
				: remap(error, new PartnerInviteException(PartnerInviteException.Reason.UNAVAILABLE)));
	}
}
