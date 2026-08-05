package com.camel_hub.advertisement.arxiv.importing;

import reactor.core.publisher.Mono;

import java.util.Set;

@FunctionalInterface
public interface ArxivImportCatalog {

	Mono<Set<String>> activeIdentifiers();
}
