package com.camel_hub.advertisement.arxiv.savedsearch;

import reactor.core.publisher.Mono;

import java.util.Set;

@FunctionalInterface
public interface SavedSearchCategoryCatalog {

	Mono<Set<String>> activeCategoryIds();
}
