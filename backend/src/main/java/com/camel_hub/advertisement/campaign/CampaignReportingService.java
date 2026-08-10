package com.camel_hub.advertisement.campaign;

import com.camel_hub.advertisement.common.api.PageResponse;
import reactor.core.publisher.Mono;

public final class CampaignReportingService {

	private final CampaignReportingRepository repository;

	public CampaignReportingService(CampaignReportingRepository repository) {
		this.repository = repository;
	}

	public Mono<PageResponse<CampaignReportingRepository.DeliveryView>> deliveries(int page, int pageSize) {
		validatePage(page, pageSize);
		return Mono.zip(repository.deliveries(offset(page, pageSize), pageSize).collectList(),
				repository.deliveryCount())
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<PageResponse<CampaignReportingRepository.CampaignAnalyticsView>> campaigns(
			int page, int pageSize
	) {
		validatePage(page, pageSize);
		return Mono.zip(repository.campaigns(offset(page, pageSize), pageSize).collectList(),
				repository.campaignCount())
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	public Mono<PageResponse<CampaignReportingRepository.LinkAnalyticsView>> links(int page, int pageSize) {
		validatePage(page, pageSize);
		return Mono.zip(repository.links(offset(page, pageSize), pageSize).collectList(), repository.linkCount())
				.map(tuple -> PageResponse.of(tuple.getT1(), page, pageSize, tuple.getT2()));
	}

	private int offset(int page, int pageSize) {
		return Math.multiplyExact(page - 1, pageSize);
	}

	private void validatePage(int page, int pageSize) {
		if (page < 1 || pageSize < 1 || pageSize > 100) {
			throw new CampaignValidationException("Page must be at least 1 and pageSize between 1 and 100");
		}
	}
}
