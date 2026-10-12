package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.campaign.delivery.CampaignDeliveryProperties;
import com.camel_hub.advertisement.email.tracking.MailTrackingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PartnerPreflightUnitTest {
	@Test void disabledPartnerFeatureBlocksOnlyOptInContentWithoutRemoteCalls() {
		var repository=mock(CampaignWorkflowRepository.class);
		var record=mock(CampaignWorkflowRepository.PreflightRecord.class);
		var delivery=mock(CampaignDeliveryProperties.class);
		when(delivery.productionCooldown()).thenReturn(Duration.ofDays(30));
		when(record.campaignId()).thenReturn(UUID.randomUUID());
		when(record.partnerRequired()).thenReturn(true);
		when(repository.preflight(any(),any())).thenReturn(Mono.just(record));
		var service=new CampaignPreflightService(repository,delivery,mock(MailTrackingProperties.class),new ObjectMapper(),false);
		assertThat(service.preflight(UUID.randomUUID()).block().checks().get("PARTNER_INVITE_READY").passed()).isFalse();
		when(record.partnerRequired()).thenReturn(false);
		assertThat(service.preflight(UUID.randomUUID()).block().checks().get("PARTNER_INVITE_READY").passed()).isTrue();
		when(record.partnerHeld()).thenReturn(true);
		assertThat(service.preflight(UUID.randomUUID()).block().checks().get("PARTNER_INVITE_READY").passed()).isFalse();
	}
}
