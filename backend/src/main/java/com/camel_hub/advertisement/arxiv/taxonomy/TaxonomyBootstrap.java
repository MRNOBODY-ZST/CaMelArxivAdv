package com.camel_hub.advertisement.arxiv.taxonomy;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.time.Duration;

public class TaxonomyBootstrap implements ApplicationRunner {

	private final TaxonomyService service;

	public TaxonomyBootstrap(TaxonomyService service) {
		this.service = service;
	}

	@Override
	public void run(ApplicationArguments arguments) {
		service.bootstrapOffline().block(Duration.ofSeconds(30));
	}
}
